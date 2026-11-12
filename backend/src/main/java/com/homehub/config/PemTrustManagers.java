package com.homehub.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Builds TrustManagers from a PEM CA file.
 *
 * <p>The emulator serves HTTPS with a CA it generates on every start. Rather
 * than replacing the JVM-wide truststore ({@code -Djavax.net.ssl.trustStore}),
 * which would affect every HTTPS connection the app makes, we trust that CA
 * only in the AWS SDK's HTTP client. On real AWS this is never configured:
 * AWS endpoints use publicly trusted certificates.
 */
public final class PemTrustManagers {

    private PemTrustManagers() {
    }

    public static TrustManager[] fromPem(Path pemFile) {
        try (InputStream in = Files.newInputStream(pemFile)) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                ks.setCertificateEntry("ca-" + i++, cert);
            }
            if (i == 0) {
                throw new IllegalStateException("No certificates found in " + pemFile);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            return tmf.getTrustManagers();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load CA bundle " + pemFile, e);
        }
    }
}
