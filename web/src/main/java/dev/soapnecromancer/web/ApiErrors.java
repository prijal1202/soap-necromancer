package dev.soapnecromancer.web;

import dev.soapnecromancer.core.Warning;
import dev.soapnecromancer.core.WsdlParseException;
import dev.soapnecromancer.generators.GenerationLimitException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.List;

/**
 * RFC 7807 errors for the generator API. An advice rather than controller
 * methods because multipart
 * size errors are raised before Spring has picked a handler.
 */
@RestControllerAdvice
class ApiErrors {
    private final int maxUploadBytes;

    ApiErrors(@Value("${necromancer.max-upload-bytes}") int maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }

    @ExceptionHandler(BadUpload.class)
    ResponseEntity<ProblemDetail> badUpload(BadUpload e) {
        ResponseEntity<ProblemDetail> r = problem(e.status, "Upload rejected", e.getMessage());
        if (e.retryAfterSeconds <= 0)
            return r;
        return ResponseEntity.status(r.getStatusCode()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds)).body(r.getBody());
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

    @ExceptionHandler({ MissingServletRequestPartException.class, MissingServletRequestParameterException.class })
    ResponseEntity<ProblemDetail> missingPart(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Upload rejected", "Send the WSDL as the multipart field \"wsdl\".");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(HttpMediaTypeNotSupportedException e) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Upload rejected",
                "Send multipart/form-data with field \"wsdl\", or a raw body as text/xml or application/xml.");
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

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }
}
