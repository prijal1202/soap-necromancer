package dev.soapnecromancer.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Per-client rate limits for the endpoints that cost CPU on the 0.1 CPU host: {@code POST
 * /api/generate} and {@code POST /mock-bank/ws}. Over the limit: 429 problem+json with
 * {@code Retry-After}. {@code /mock-bank/reset} has its own global cooldown.
 *
 * <p>The client key is the <em>last</em> {@code X-Forwarded-For} entry, the hop Render's proxy
 * appends; earlier entries come from the client and can be forged. Without the header (local runs)
 * it is the socket address. If the proxy chain ever made every request share one key, the limit
 * degrades to a global one, which the defaults keep usable for a demo.
 */
@Component
class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter generate;
    private final RateLimiter soap;
    private final ObjectMapper json;

    RateLimitFilter(@Value("${necromancer.rate-limit.generate-per-minute:30}") int generatePerMinute,
            @Value("${necromancer.rate-limit.mock-bank-per-minute:120}") int soapPerMinute, ObjectMapper json) {
        this.generate = new RateLimiter(generatePerMinute);
        this.soap = new RateLimiter(soapPerMinute);
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimiter limiter = limiterFor(request);
        if (limiter != null) {
            Duration wait = limiter.tryAcquire(clientKey(request), System.nanoTime());
            if (!wait.isZero()) {
                reject(response, wait);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private RateLimiter limiterFor(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()))
            return null;
        return switch (request.getRequestURI()) {
            case "/api/generate" -> generate;
            case "/mock-bank/ws" -> soap;
            default -> null;
        };
    }

    /**
     * Reads the header from the innermost request: Spring's ForwardedHeaderFilter may already have
     * wrapped this one and hidden X-Forwarded-*.
     */
    static String clientKey(HttpServletRequest request) {
        ServletRequest raw = request;
        while (raw instanceof ServletRequestWrapper w)
            raw = w.getRequest();
        String forwarded = raw instanceof HttpServletRequest h ? h.getHeader("X-Forwarded-For") : null;
        if (forwarded != null && !forwarded.isBlank()) {
            String last = forwarded.substring(forwarded.lastIndexOf(',') + 1).strip();
            if (!last.isEmpty())
                return last.length() > 64 ? last.substring(0, 64) : last;
        }
        return raw.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, Duration wait) throws IOException {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests from this client. Try again in " + seconds + " s.");
        p.setTitle("Rate limited");
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), p);
    }
}
