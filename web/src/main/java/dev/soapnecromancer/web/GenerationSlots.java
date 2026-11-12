package dev.soapnecromancer.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Caps concurrent generations to protect the 512 MB budget. Extra requests wait up to
 * {@code necromancer.queue-wait} for a slot (a warm generation takes ~20 ms), then get 503.
 */
@Component
class GenerationSlots {
    private final Semaphore slots;
    private final Duration wait;

    GenerationSlots(@Value("${necromancer.max-concurrent}") int maxConcurrent,
                    @Value("${necromancer.queue-wait:5s}") Duration wait) {
        this.slots = new Semaphore(maxConcurrent);
        this.wait = wait;
    }

    /** Acquires a slot or throws a 503 BadUpload with a Retry-After hint. */
    void acquire() {
        try {
            if (slots.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw BadUpload.busy("The generator is busy. Try again in a few seconds.", RETRY_AFTER_SECONDS);
    }

    void release() {
        slots.release();
    }

    /** Test hook: take every slot, so the next request has to wait and then gets 503. */
    void drainForTest() {
        slots.drainPermits();
    }

    void refillForTest(int permits) {
        slots.release(permits);
    }

    static final int RETRY_AFTER_SECONDS = 5;
}
