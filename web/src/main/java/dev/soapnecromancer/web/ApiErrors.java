package dev.soapnecromancer.web;

import dev.soapnecromancer.core.Warning;
import dev.soapnecromancer.core.WsdlParseException;
import dev.soapnecromancer.generators.GenerationLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.List;

/**
 * RFC 7807 errors for the whole app. An advice rather than controller methods
 * because multipart
 * errors are raised before Spring has picked a handler. Every response is
 * problem+json with a fixed,
 * curated message: nothing from an exception's internals reaches the client.
 */
@RestControllerAdvice
class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    private final int maxUploadBytes;

    ApiErrors(@Value("${necromancer.max-upload-bytes}") int maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }

    @ExceptionHandler(BadUpload.class)
    ResponseEntity<ProblemDetail> badUpload(BadUpload e) {
        HttpHeaders headers = new HttpHeaders();
        if (e.retryAfterSeconds > 0)
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds));
        return problem(e.status, "Upload rejected", e.getMessage(), headers);
    }

    /**
     * Output that would be too large (generators Requirement 5.6): same 422 shape
     * as an unusable WSDL.
     */
    @ExceptionHandler(GenerationLimitException.class)
    ResponseEntity<ProblemDetail> outputTooLarge(GenerationLimitException e) {
        ResponseEntity<ProblemDetail> r = problem(HttpStatus.UNPROCESSABLE_ENTITY, "WSDL cannot be resurrected",
                e.getMessage() + ". The hosted generator refuses it to protect the service.");
        r.getBody().setProperty("warnings", List.of(new Warning(e.code(), e.getMessage(), "output")));
        return r;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Upload rejected",
                "The upload is larger than " + maxUploadBytes + " bytes.");
    }

    /**
     * Truncated or malformed multipart bodies (no boundary, cut-off part): the
     * client's fault.
     */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ProblemDetail> badMultipart(MultipartException e) {
        return problem(HttpStatus.BAD_REQUEST, "Upload rejected",
                "The multipart body could not be read. Send the WSDL as the multipart field \"wsdl\".");
    }

    @ExceptionHandler({ MissingServletRequestPartException.class, MissingServletRequestParameterException.class })
    ResponseEntity<ProblemDetail> missingPart(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Upload rejected", "Send the WSDL as the multipart field \"wsdl\".");
    }

    /**
     * Names what this endpoint accepts, so the message is right for every endpoint,
     * not just /api/generate.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(HttpMediaTypeNotSupportedException e) {
        List<String> accepted = e.getSupportedMediaTypes().stream().map(MediaType::toString).toList();
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type",
                accepted.isEmpty() ? "This endpoint does not accept that content type."
                        : "This endpoint accepts " + String.join(", ", accepted) + ".");
    }

    /**
     * Not XML, not WSDL 1.1, or no usable operation: 422 with every warning so the
     * user sees why.
     */
    @ExceptionHandler(WsdlParseException.class)
    ResponseEntity<ProblemDetail> unusable(WsdlParseException e) {
        ResponseEntity<ProblemDetail> r = problem(HttpStatus.UNPROCESSABLE_ENTITY, "WSDL cannot be resurrected",
                e.getMessage());
        r.getBody().setProperty("warnings", e.warnings());
        return r;
    }

    /**
     * Everything else. Spring MVC's own errors (404, 405, 406, ...) keep their
     * status and their
     * standard problem body; anything unexpected is logged and answered with a
     * fixed 500.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        if (e instanceof ErrorResponse er) {
            return ResponseEntity.status(er.getStatusCode()).headers(er.getHeaders())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(er.getBody());
        }
        log.error("unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "The generator failed unexpectedly. The error was logged.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String title, String detail) {
        return problem(status, title, detail, new HttpHeaders());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String title, String detail,
            HttpHeaders headers) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(p);
    }
}
