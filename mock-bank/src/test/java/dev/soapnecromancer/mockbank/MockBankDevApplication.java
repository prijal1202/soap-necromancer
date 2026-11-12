package dev.soapnecromancer.mockbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone mock-bank for local dev and tests: {@code mvn -pl mock-bank spring-boot:test-run}.
 * Lives in test sources so it never clashes with the web module's @SpringBootApplication scan.
 */
@SpringBootApplication
public class MockBankDevApplication {
    public static void main(String[] args) {
        SpringApplication.run(MockBankDevApplication.class, args);
    }
}
