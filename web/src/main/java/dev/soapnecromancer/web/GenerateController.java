package dev.soapnecromancer.web;

import dev.soapnecromancer.core.ParseResult;
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
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * The generator API. Stateless: {@code POST /api/generate} parses the uploaded
 * WSDL, generates every
 * artifact in memory and returns the zip in the same response. Nothing is
 * stored, nothing is written
 * to disk, and the parser never fetches imported documents (SSRF;
 * docs/decisions.md section 2).
 */
@RestController
public class GenerateController {
    private static final Logger log = LoggerFactory.getLogger(GenerateController.class);
    public static final MediaType ZIP = MediaType.parseMediaType("application/zip");
    static final java.time.Duration QUEUE_WAIT = java.time.Duration.ofSeconds(5);

    private final WsdlParser parser = new WsdlParser();
    private final Generator generator = new Generator();
    private final MockBankEndpoint mockBank;
    private final int maxUploadBytes;
    private final Semaphore slots;

    public GenerateController(MockBankEndpoint mockBank,
            @Value("${necromancer.max-upload-bytes}") int maxUploadBytes,
            @Value("${necromancer.max-concurrent}") int maxConcurrent) {
        this.mockBank = mockBank;
        this.maxUploadBytes = maxUploadBytes;
        this.slots = new Semaphore(maxConcurrent);
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
        // Bursts queue briefly: a generation takes ~20 ms warm, so failing fast only
        // hurts double-clicks.
        // The cap protects the 512 MB budget; 503 only when a slot stays busy for
        // QUEUE_WAIT.
        try {
            if (!slots.tryAcquire(QUEUE_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new BadUpload(HttpStatus.SERVICE_UNAVAILABLE,
                        "The generator is busy. Try again in a few seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadUpload(HttpStatus.SERVICE_UNAVAILABLE, "The generator is shutting down.");
        }
        try {
            long start = System.nanoTime();
            ParseResult result = parser.parse(wsdl);
            GeneratedFiles files = generator.generate(result);
            byte[] zip = files.zip();
            log.info("generated {} ({} operations, {} warnings, {} files, {} bytes) in {} ms",
                    result.model().name(), result.model().operations().size(), result.warnings().size(),
                    files.files().size(), zip.length, (System.nanoTime() - start) / 1_000_000);

            String filename = Names.artifactId(result.model().name(), "resurrected") + ".zip";
            return ResponseEntity.ok()
                    .contentType(ZIP)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(filename).build().toString())
                    .header("X-Generator-Service", ascii(result.model().name()))
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

    private static String ascii(String s) {
        return s.replaceAll("[^\\x20-\\x7E]", "?");
    }

}
