package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Optional;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;

import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;

class ServerTlsTest {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final String HEALTH = "{\"healthy\": true, \"backupInterval\": \"PT1H\"}";

    @TempDir
    Path tempDir;

    private HttpsServer server;
    private Path certificate;
    private String authorization;

    private void run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException(String.join(" ", command) + " failed: " + output);
        }
    }

    @BeforeEach
    void startHttpsServer() throws Exception {
        Path keystore = tempDir.resolve("server.p12");
        certificate = tempDir.resolve("ca.pem");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        run(keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=MediaGarrd Test CA",
            "-ext", "san=ip:127.0.0.1", "-ext", "bc:c", "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", new String(PASSWORD));
        run(keytool, "-exportcert", "-rfc", "-alias", "server", "-keystore", keystore.toString(), "-storepass", new String(PASSWORD), "-file", certificate.toString());

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, PASSWORD);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = HEALTH.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopHttpsServer() {
        server.stop(0);
    }

    private String url() {
        return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    private ServerApiService api(ServerTls tls) {
        return new ServerApiService(Duration.ofHours(1), serverUrl -> Optional.of("tok"), tls);
    }

    @Test
    void aSelfSignedServerIsRefusedWithoutTheCa() {
        assertThrows(ResourceAccessException.class, () -> api(ServerTls.defaults()).health(url()));
    }

    @Test
    void theConfiguredCaMakesTheServerTrustedAndTheTokenTravelsOverTls() {
        ServerApiService api = api(new ServerTls(certificate.toString()));

        assertTrue(api.health(url()).healthy());
        assertEquals("Bearer tok", authorization);
    }

    @Test
    void hostnameVerificationStaysOnEvenWithTheCa() {
        String byName = "https://localhost:" + server.getAddress().getPort();

        ResourceAccessException e = assertThrows(ResourceAccessException.class, () -> api(new ServerTls(certificate.toString())).health(byName));

        StringBuilder chain = new StringBuilder();
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            chain.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage()).append('\n');
        }
        assertTrue(chain.toString().contains("SSLHandshakeException"), chain.toString());
        assertTrue(api(new ServerTls(certificate.toString())).health(url()).healthy());
    }

    @Test
    void anUnrelatedCaDoesNotTrustTheServer() throws Exception {
        Path other = tempDir.resolve("other.p12");
        Path otherPem = tempDir.resolve("other.pem");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        run(keytool, "-genkeypair", "-alias", "o", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Other", "-keystore", other.toString(), "-storetype", "PKCS12", "-storepass", new String(PASSWORD));
        run(keytool, "-exportcert", "-rfc", "-alias", "o", "-keystore", other.toString(), "-storepass", new String(PASSWORD), "-file", otherPem.toString());

        assertThrows(ResourceAccessException.class, () -> api(new ServerTls(otherPem.toString())).health(url()));
    }

    @Test
    void theDefaultTrustStillWorksWhenAnUnrelatedCaIsConfigured() throws Exception {
        Path otherPem = tempDir.resolve("other.pem");
        Path other = tempDir.resolve("other.p12");
        Path systemTrust = tempDir.resolve("system-trust.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        run(keytool, "-genkeypair", "-alias", "o", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Other", "-keystore", other.toString(), "-storetype", "PKCS12", "-storepass", new String(PASSWORD));
        run(keytool, "-exportcert", "-rfc", "-alias", "o", "-keystore", other.toString(), "-storepass", new String(PASSWORD), "-file", otherPem.toString());
        run(keytool, "-importcert", "-noprompt", "-alias", "system-ca", "-file", certificate.toString(), "-keystore", systemTrust.toString(), "-storetype", "PKCS12", "-storepass", new String(PASSWORD));
        assertThrows(ResourceAccessException.class, () -> api(new ServerTls(otherPem.toString())).health(url()));

        String path = System.getProperty("javax.net.ssl.trustStore");
        String type = System.getProperty("javax.net.ssl.trustStoreType");
        String password = System.getProperty("javax.net.ssl.trustStorePassword");
        System.setProperty("javax.net.ssl.trustStore", systemTrust.toString());
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        System.setProperty("javax.net.ssl.trustStorePassword", new String(PASSWORD));
        try {
            assertTrue(api(new ServerTls(otherPem.toString())).health(url()).healthy());
        } finally {
            restore("javax.net.ssl.trustStore", path);
            restore("javax.net.ssl.trustStoreType", type);
            restore("javax.net.ssl.trustStorePassword", password);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void discoveryProbesAnHttpsAddressWithTheSharedTrust() {
        MediaGarrdProperties configuration = new MediaGarrdProperties();
        configuration.setStateFile(tempDir.resolve("state.json").toString());
        ServerDiscoveryService discovery = new ServerDiscoveryService(configuration, mock(ClientStateService.class), JsonMapper.builder().build(), new ServerTls(certificate.toString()));

        Optional<String> found = discovery.resolveServerByIp(url(), null);

        assertEquals(Optional.of(url()), found);
    }

    @Test
    void discoveryDoesNotTrustAnUnknownCertificate() {
        MediaGarrdProperties configuration = new MediaGarrdProperties();
        ServerDiscoveryService discovery = new ServerDiscoveryService(configuration, mock(ClientStateService.class), JsonMapper.builder().build(), ServerTls.defaults());

        assertTrue(discovery.resolveServerByIp(url(), null).isEmpty());
    }

    @Test
    void anUnreadableCaFileIsAClearStartupError() {
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> new ServerTls(tempDir.resolve("nope.pem").toString()));
        Path junk = tempDir.resolve("junk.pem");
        try {
            Files.writeString(junk, "not a certificate");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        IllegalStateException invalid = assertThrows(IllegalStateException.class, () -> new ServerTls(junk.toString()));

        assertTrue(missing.getMessage().contains("MEDIAGARRD_SERVER_CA"), missing.getMessage());
        assertTrue(invalid.getMessage().contains("MEDIAGARRD_SERVER_CA"), invalid.getMessage());
    }

}
