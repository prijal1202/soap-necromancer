package dev.soapnecromancer.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The generator's web app: the generator API, the UI (built from frontend/),
 * and the bundled mock-bank SOAP service,
 * all in one JVM on one port (Render forwards a single port; docs/decisions.md
 * section 3).
 */
@SpringBootApplication(scanBasePackages = { "dev.soapnecromancer.web", "dev.soapnecromancer.mockbank" })
public class NecromancerApplication {
    public static void main(String[] args) {
        SpringApplication.run(NecromancerApplication.class, args);
    }
}
