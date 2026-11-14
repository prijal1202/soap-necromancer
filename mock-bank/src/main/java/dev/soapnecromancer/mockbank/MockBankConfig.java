package dev.soapnecromancer.mockbank;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on the scheduled ledger reset ({@link MockBankReset}) wherever mock-bank is mounted. */
@Configuration
@EnableScheduling
class MockBankConfig {
}
