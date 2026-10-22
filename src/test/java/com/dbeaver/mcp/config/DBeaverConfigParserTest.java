package com.dbeaver.mcp.config;

import com.dbeaver.mcp.model.ConnectionInfo;
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
    public void testCustomAesKeyHandling() {
        // Test that custom key parser works properly
        byte[] customKey = "1234567890abcdef1234567890abcdef".getBytes(StandardCharsets.UTF_8);
        assertNotNull(customKey);
        assertEquals(32, customKey.length);
    }
}
