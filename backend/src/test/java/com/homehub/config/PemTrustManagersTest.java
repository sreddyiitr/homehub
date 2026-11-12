package com.homehub.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

class PemTrustManagersTest {

    private static Path resource(String name) throws Exception {
        return Path.of(PemTrustManagersTest.class.getResource("/" + name).toURI());
    }

    @Test
    void trustsTheCertificateInThePem() throws Exception {
        var managers = PemTrustManagers.fromPem(resource("test-ca.pem"));

        assertThat(managers).hasSize(1);
        var x509 = (X509TrustManager) managers[0];
        assertThat(x509.getAcceptedIssuers()).singleElement()
                .satisfies(c -> assertThat(c.getSubjectX500Principal().getName()).contains("homehub-test-ca"));
    }

    @Test
    void failsClearlyOnMissingFile() {
        assertThatThrownBy(() -> PemTrustManagers.fromPem(Path.of("does-not-exist.pem")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist.pem");
    }

    @Test
    void failsClearlyOnFileWithoutCertificates() throws Exception {
        assertThatThrownBy(() -> PemTrustManagers.fromPem(resource("not-a-cert.pem")))
                .isInstanceOf(IllegalStateException.class);
    }
}
