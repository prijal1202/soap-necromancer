package dev.wsdlforge.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BadUploadTest {

    @Test
    void sizesReadTheWayTheLimitsAreSet() {
        assertThat(BadUpload.readableSize(2_097_152)).isEqualTo("2 MB");
        assertThat(BadUpload.readableSize(65_536)).isEqualTo("64 KB");
        assertThat(BadUpload.readableSize(3_000_000)).isEqualTo("3,000,000 bytes");
        assertThat(BadUpload.readableSize(0)).isEqualTo("0 bytes");
    }

    @Test
    void tooLargeIs413WithTheLimit() {
        BadUpload e = BadUpload.tooLarge(2_097_152);
        assertThat(e.status.value()).isEqualTo(413);
        assertThat(e.getMessage()).isEqualTo("The upload is larger than the 2 MB limit.");
    }
}
