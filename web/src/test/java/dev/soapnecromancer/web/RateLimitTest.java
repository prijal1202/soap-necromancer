package dev.soapnecromancer.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-client limits on the CPU-heavy endpoints, keyed on the hop Render's proxy appends. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "necromancer.rate-limit.generate-per-minute=2", "necromancer.rate-limit.mock-bank-per-minute=2" })
class RateLimitTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private HttpResponse<String> generate(String forwardedFor) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/generate"))
                .header("Content-Type", "text/xml").header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString("not xml")).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void thirdRequestInAMinuteIs429AndForgedLeadingHopsDoNotHelp() throws Exception {
        // The client controls everything before the last comma; the proxy appends the real address.
        assertThat(generate("1.1.1.1, 203.0.113.7").statusCode()).isEqualTo(422);
        assertThat(generate("2.2.2.2, 203.0.113.7").statusCode()).isEqualTo(422);
        HttpResponse<String> limited = generate("3.3.3.3, 203.0.113.7");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).hasValueSatisfying(
                v -> assertThat(Integer.parseInt(v)).isBetween(1, 30));
        assertThat(limited.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
        assertThat(json.readTree(limited.body()).get("title").asText()).isEqualTo("Rate limited");

        // Another client is unaffected.
        assertThat(generate("203.0.113.8").statusCode()).isEqualTo(422);
    }

    @Test
    void bucketsRefillOverTime() {
        RateLimiter limiter = new RateLimiter(60); // one token per second
        long t = 0;
        for (int i = 0; i < 60; i++)
            assertThat(limiter.tryAcquire("c", t)).isZero();
        Duration wait = limiter.tryAcquire("c", t);
        assertThat(wait).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("c", t + Duration.ofSeconds(1).toNanos())).isZero();
        assertThat(limiter.tryAcquire("other", t)).isZero();
    }

    @Test
    void trackedClientsStayBounded() {
        RateLimiter limiter = new RateLimiter(1);
        for (int i = 0; i <= RateLimiter.MAX_TRACKED_CLIENTS + 10; i++)
            limiter.tryAcquire("client-" + i, 0);
        // After eviction the limiter still works and a fresh client gets a token.
        assertThat(limiter.tryAcquire("fresh", 0)).isZero();
    }
}
