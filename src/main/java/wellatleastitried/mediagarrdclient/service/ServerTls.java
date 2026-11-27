package wellatleastitried.mediagarrdclient.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;

@Component
public class ServerTls {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerTls.class);

    private final SSLContext context;

    @Autowired
    public ServerTls(MediaGarrdProperties configuration) {
        this(configuration.getServerCa());
    }

    ServerTls(String caFile) {
        this.context = caFile == null || caFile.isBlank() ? platformDefault() : withExtraAuthorities(Path.of(caFile.trim()));
    }

    public static ServerTls defaults() {
        return new ServerTls("");
    }

    public SSLContext context() {
        return context;
    }

    private static SSLContext platformDefault() {
        try {
            return SSLContext.getDefault();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not load the default TLS configuration: " + e.getMessage(), e);
        }
    }

    private static SSLContext withExtraAuthorities(Path caFile) {
        try {
            X509ExtendedTrustManager extra = extraTrustManager(readCertificates(caFile));
            X509ExtendedTrustManager composite = new CompositeTrustManager(defaultTrustManager(), extra);
            SSLContext created = SSLContext.getInstance("TLS");
            created.init(null, new TrustManager[] {composite}, null);
            LOGGER.info("Trusting the certificate authorities in {} in addition to the default ones for MediaGarrd servers", caFile);
            return created;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("MEDIAGARRD_SERVER_CA " + caFile + " could not be used: " + e.getMessage(), e);
        }
    }

    private static Collection<? extends Certificate> readCertificates(Path caFile) throws IOException, CertificateException {
        try (InputStream in = Files.newInputStream(caFile)) {
            Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (certificates.isEmpty()) {
                throw new CertificateException("the file contains no PEM certificates");
            }
            return certificates;
        }
    }

    private static X509ExtendedTrustManager extraTrustManager(Collection<? extends Certificate> certificates) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        int index = 0;
        for (Certificate certificate : certificates) {
            store.setCertificateEntry("mediagarrd-ca-" + index++, certificate);
        }
        return extended(store);
    }

    private static X509ExtendedTrustManager defaultTrustManager() throws GeneralSecurityException {
        return extended(null);
    }

    private static X509ExtendedTrustManager extended(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509ExtendedTrustManager extended) {
                return extended;
            }
        }
        throw new GeneralSecurityException("No X509ExtendedTrustManager available");
    }

    private static final class CompositeTrustManager extends X509ExtendedTrustManager {

        private final X509ExtendedTrustManager primary;
        private final X509ExtendedTrustManager secondary;

        private CompositeTrustManager(X509ExtendedTrustManager primary, X509ExtendedTrustManager secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @FunctionalInterface
        private interface Check {
            void run(X509ExtendedTrustManager manager) throws CertificateException;
        }

        private static void either(X509ExtendedTrustManager first, X509ExtendedTrustManager second, Check check) throws CertificateException {
            try {
                check.run(first);
            } catch (CertificateException e) {
                try {
                    check.run(second);
                } catch (CertificateException ignored) {
                    throw e;
                }
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            either(primary, secondary, manager -> manager.checkServerTrusted(chain, authType, socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            either(primary, secondary, manager -> manager.checkServerTrusted(chain, authType, engine));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            either(primary, secondary, manager -> manager.checkServerTrusted(chain, authType));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("Client certificates are not used");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("Client certificates are not used");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Client certificates are not used");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] first = primary.getAcceptedIssuers();
            X509Certificate[] second = secondary.getAcceptedIssuers();
            X509Certificate[] all = java.util.Arrays.copyOf(first, first.length + second.length);
            System.arraycopy(second, 0, all, first.length, second.length);
            return all;
        }
    }
}
