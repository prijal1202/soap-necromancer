package dev.soapnecromancer.web;

import dev.soapnecromancer.core.ParseResult;
import dev.soapnecromancer.core.Warning;
import dev.soapnecromancer.core.WsdlParseException;
import dev.soapnecromancer.core.WsdlParser;
import dev.soapnecromancer.generators.GeneratedFiles;
import dev.soapnecromancer.generators.Generator;
import dev.soapnecromancer.generators.Names;
import dev.soapnecromancer.mockbank.MockBankEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * The generator API. Stateless: {@code POST /api/generate} parses the uploaded
 * WSDL, generates every
 * artifact in memory and returns the zip in the same response. Nothing is
 * stored, nothing is written
 * to disk, and the parser never fetches imported documents (SSRF;
 * docs/decisions.md section 2).
 * Resource limits (parser Requirement 5, generators Requirement 5.6) turn
 * hostile or huge inputs into 422.
 */
@RestController
public class GenerateController {
    private static final Logger log = LoggerFactory.getLogger(GenerateController.class);
    public static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final WsdlParser parser = new WsdlParser();
    private final Generator generator;
    private final MockBankEndpoint mockBank;
    private final GenerationSlots slots;
    private final int maxUploadBytes;

    public GenerateController(MockBankEndpoint mockBank, GenerationSlots slots,
            @Value("${necromancer.max-upload-bytes}") int maxUploadBytes,
            @Value("${necromancer.max-output-bytes:" + Generator.DEFAULT_MAX_OUTPUT_BYTES + "}") long maxOutputBytes,
            @Value("${necromancer.max-tool-definitions:" + Generator.DEFAULT_MAX_TOOL_DEFINITIONS
                    + "}") int maxToolDefinitions) {
        this.mockBank = mockBank;
        this.slots = slots;
        this.maxUploadBytes = maxUploadBytes;
        this.generator = new Generator(maxOutputBytes, maxToolDefinitions);
    }

    /**
     * Browser / curl -F upload:
     * {@code curl -F wsdl=@bank.wsdl .../api/generate -o out.zip}.
     */
    @PostMapping(path = "/api/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> generateUpload(@RequestParam("wsdl") MultipartFile wsdl) throws IOException {
        try (InputStream in = wsdl.getInputStream()) {
            return generate(readCapped(in));
        }
    }

    /**
     * Raw body:
     * {@code curl --data-binary @bank.wsdl -H "Content-Type: text/xml" .../api/generate}.
     */
    @PostMapping(path = "/api/generate", consumes = { MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_XML_VALUE,
            "application/wsdl+xml" })
    public ResponseEntity<byte[]> generateRaw(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            return generate(readCapped(in));
        }
    }

    private ResponseEntity<byte[]> generate(byte[] wsdl) {
        if (wsdl.length == 0)
            throw new BadUpload(HttpStatus.BAD_REQUEST, "The upload is empty. Send a WSDL 1.1 document.");
        slots.acquire();
        try {
            long start = System.nanoTime();
            ParseResult result;
            GeneratedFiles files;
            try {
                result = parser.parse(wsdl);
                files = generator.generate(result);
            } catch (StackOverflowError e) {
                // Last resort. The parser limits should make this unreachable; a public
                // endpoint still
                // answers 422 instead of 500 if some construct slips past them.
                log.warn("generation overflowed the stack; reported as LIMIT_COMPLEXITY");
                throw new WsdlParseException("The WSDL is too complex to process",
                        List.of(new Warning("LIMIT_COMPLEXITY", "Too deeply nested to process", "definitions")));
            }
            byte[] zip = files.zip();
            log.info("generated {} ({} operations, {} warnings, {} files, {} bytes) in {} ms",
                    result.model().name(), result.model().operations().size(), result.warnings().size(),
                    files.files().size(), zip.length, (System.nanoTime() - start) / 1_000_000);

            String filename = truncate(Names.artifactId(result.model().name(), "resurrected"), 100) + ".zip";
            return ResponseEntity.ok()
                    .contentType(ZIP)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(filename).build().toString())
                    .header("X-Generator-Service", truncate(ascii(result.model().name()), MAX_HEADER_TEXT))
                    .header("X-Generator-Operations", String.valueOf(result.model().operations().size()))
                    .header("X-Generator-Warnings", String.valueOf(result.warnings().size()))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(zip);
        } finally {
            slots.release();
        }
    }

    /**
     * The bank WSDL, with soap:address pointing at this app's mock-bank (live demo
     * and UI sample).
     */
    @GetMapping(path = "/api/sample-wsdl", produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> sampleWsdl() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("bank.wsdl").build().toString())
                .body(mockBank.wsdl());
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /**
     * Reads at most maxUploadBytes; one byte more means the upload is too large
     * (raw bodies have no servlet limit).
     */
    private byte[] readCapped(InputStream in) throws IOException {
        byte[] data = in.readNBytes(maxUploadBytes + 1);
        if (data.length > maxUploadBytes) {
            throw new BadUpload(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The upload is larger than " + maxUploadBytes + " bytes.");
        }
        return data;
    }

    /**
     * Printable ASCII only: no CR/LF, so WSDL text can never split a response
     * header.
     */
    private static String ascii(String s) {
        return s.replaceAll("[^\\x20-\\x7E]", "?");
    }

    /** WSDL names are unbounded; Tomcat's response header buffer is 8 KB. */
    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static final int MAX_HEADER_TEXT = 200;
}
