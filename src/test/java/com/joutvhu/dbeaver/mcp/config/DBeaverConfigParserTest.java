package com.joutvhu.dbeaver.mcp.config;

import com.joutvhu.dbeaver.mcp.model.ConnectionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DBeaverConfigParserTest {

    private static final byte[] DEFAULT_KEY = new byte[]{
            (byte) 0xba, (byte) 0xbb, (byte) 0x4a, (byte) 0x9f,
            (byte) 0x77, (byte) 0x4a, (byte) 0xb8, (byte) 0x53,
            (byte) 0xc9, (byte) 0x6c, (byte) 0x2d, (byte) 0x65,
            (byte) 0x3d, (byte) 0xfe, (byte) 0x54, (byte) 0x4a
    };
    private static final byte[] DEFAULT_IV = new byte[16];

    @Test
    public void testParseMockWorkspace(@TempDir Path tempDir) throws Exception {
        Path generalDir = tempDir.resolve("General").resolve(".dbeaver");
        Files.createDirectories(generalDir);

        String dataSourcesJson = "{\n" +
                "  \"connections\": {\n" +
                "    \"mock-conn-1\": {\n" +
                "      \"name\": \"Mock Postgres\",\n" +
                "      \"driver\": \"postgres-jdbc\",\n" +
                "      \"configuration\": {\n" +
                "        \"host\": \"localhost\",\n" +
                "        \"port\": 5432,\n" +
                "        \"database\": \"testdb\",\n" +
                "        \"user\": \"postgres\"\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
        Files.writeString(generalDir.resolve("data-sources.json"), dataSourcesJson, StandardCharsets.UTF_8);

        // Create encrypted credentials file
        String credJson = "{\"mock-conn-1\":{\"#connection\":{\"user\":\"postgres\",\"password\":\"testpass123\"}}}";
        byte[] encryptedCreds = encryptCredentials(credJson, DEFAULT_KEY, DEFAULT_IV);
        Files.write(generalDir.resolve("credentials-config.json"), encryptedCreds);

        DBeaverConfigParser parser = new DBeaverConfigParser(tempDir.toString(), false);
        List<ConnectionInfo> connections = parser.parseConnections();

        assertEquals(1, connections.size());
        ConnectionInfo conn = connections.get(0);
        assertEquals("mock-conn-1", conn.getId());
        assertEquals("Mock Postgres", conn.getName());
        assertEquals("postgres-jdbc", conn.getDriver());
        assertEquals("localhost", conn.getHost());
        assertEquals(5432, conn.getPort());
        assertEquals("testdb", conn.getDatabase());
        assertEquals("postgres", conn.getUser());
        assertEquals("testpass123", conn.getPassword());

        // Verify redacted copy never exposes password
        ConnectionInfo redacted = conn.redacted();
        assertNull(redacted.getPassword());
        assertEquals("mock-conn-1", redacted.getId());
    }

    @Test
    public void testUrlCredentialsSanitization() {
        ConnectionInfo info = new ConnectionInfo();
        info.setId("test-url-cred");
        info.setUrl("jdbc:postgresql://postgres:SuperSecret123@db.example.com:5432/mydb?password=ParamSecret");

        ConnectionInfo redacted = info.redacted();
        assertFalse(redacted.getUrl().contains("SuperSecret123"), "URL should not expose embedded user:password credentials");
        assertFalse(redacted.getUrl().contains("ParamSecret"), "URL should not expose password in parameters");
        assertTrue(redacted.getUrl().contains("***REDACTED***"));
    }

    @Test
    public void testFillFromUrlWithEmbeddedCredentials(@TempDir Path tempDir) throws Exception {
        Path generalDir = tempDir.resolve("General").resolve(".dbeaver");
        Files.createDirectories(generalDir);

        String dataSourcesJson = "{\n" +
                "  \"connections\": {\n" +
                "    \"mock-conn-url\": {\n" +
                "      \"name\": \"Mock Cloud Postgres\",\n" +
                "      \"driver\": \"postgres-jdbc\",\n" +
                "      \"configuration\": {\n" +
                "        \"url\": \"jdbc:postgresql://dbadmin:p@ssword!@myhost.domain.com:5432/proddb\"\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
        Files.writeString(generalDir.resolve("data-sources.json"), dataSourcesJson, StandardCharsets.UTF_8);

        DBeaverConfigParser parser = new DBeaverConfigParser(tempDir.toString(), false);
        List<ConnectionInfo> connections = parser.parseConnections();

        assertEquals(1, connections.size());
        ConnectionInfo conn = connections.get(0);
        assertEquals("myhost.domain.com", conn.getHost(), "Host should be extracted properly without username");
        assertEquals(5432, conn.getPort());
        assertEquals("dbadmin", conn.getUser());
        assertEquals("proddb", conn.getDatabase());
    }

    @Test
    public void testSystemDBeaverDiscovery() {
        DBeaverConfigParser parser = new DBeaverConfigParser();
        List<ConnectionInfo> connections = parser.parseConnections();
        assertNotNull(connections, "Connections list should never be null");
        for (ConnectionInfo c : connections) {
            assertNotNull(c.getId());
            assertNotNull(c.getName());
        }
    }

    @Test
    public void testAesEncryptionRoundTrip() throws Exception {
        String plainJson = "{\"test-id\":{\"#connection\":{\"user\":\"admin\",\"password\":\"secret123\"}}}";
        byte[] encrypted = encryptCredentials(plainJson, DEFAULT_KEY, DEFAULT_IV);

        String decrypted = DBeaverConfigParser.decryptCredentials(encrypted, DEFAULT_KEY, DEFAULT_IV);
        assertNotNull(decrypted);
        assertEquals(plainJson, decrypted);
    }

    private byte[] encryptCredentials(String json, byte[] key, byte[] iv) throws Exception {
        byte[] plainBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] dataWithHeader = new byte[16 + plainBytes.length];
        System.arraycopy(plainBytes, 0, dataWithHeader, 16, plainBytes.length);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(dataWithHeader);
    }
}
