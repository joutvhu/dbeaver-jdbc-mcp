package com.joutvhu.dbeaver.mcp.config;

import com.joutvhu.dbeaver.mcp.model.ConnectionInfo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DBeaverConfigParserTest {

    @Test
    public void testParseRealConnections() {
        DBeaverConfigParser parser = new DBeaverConfigParser();
        List<ConnectionInfo> connections = parser.parseConnections();
        System.err.println("Parsed connections count: " + connections.size());
        for (ConnectionInfo c : connections) {
            System.err.println("Found connection: " + c.getName() + " (ID=" + c.getId() + ", Driver=" + c.getDriver() + ", Host=" + c.getHost() + ", User=" + c.getUser() + ")");
            assertNotNull(c.getId());
            assertNotNull(c.getName());
            if ("AER_DB".equals(c.getName())) {
                assertNotNull(c.getPassword(), "Should have decrypted password for AER_DB");
            }
        }
    }

    @Test
    public void testAesEncryptionRoundTrip() throws Exception {
        byte[] defaultKey = new byte[]{
                (byte) 0xba, (byte) 0xbb, (byte) 0x4a, (byte) 0x9f,
                (byte) 0x77, (byte) 0x4a, (byte) 0xb8, (byte) 0x53,
                (byte) 0xc9, (byte) 0x6c, (byte) 0x2d, (byte) 0x65,
                (byte) 0x3d, (byte) 0xfe, (byte) 0x54, (byte) 0x4a
        };
        byte[] iv = new byte[16];

        String plainJson = "{\"test-id\":{\"#connection\":{\"user\":\"admin\",\"password\":\"secret123\"}}}";
        byte[] plainBytes = plainJson.getBytes(StandardCharsets.UTF_8);

        // Prepend 16-byte random header as DBeaver does
        byte[] dataWithHeader = new byte[16 + plainBytes.length];
        System.arraycopy(plainBytes, 0, dataWithHeader, 16, plainBytes.length);

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(defaultKey, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(dataWithHeader);

        String decrypted = DBeaverConfigParser.decryptCredentials(encrypted, defaultKey, iv);
        assertNotNull(decrypted);
        assertEquals(plainJson, decrypted);
    }
}
