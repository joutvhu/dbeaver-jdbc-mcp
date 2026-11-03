package com.joutvhu.dbeaver.mcp.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ConnectionInfoTest {

    @Test
    public void testGettersAndSetters() {
        ConnectionInfo conn = new ConnectionInfo();
        conn.setId("id-1");
        conn.setName("Test Conn");
        conn.setDriver("postgres");
        conn.setProvider("postgresql");
        conn.setUrl("jdbc:postgresql://localhost:5432/db");
        conn.setHost("localhost");
        conn.setPort(5432);
        conn.setDatabase("db");
        conn.setUser("postgres");
        conn.setPassword("secret123");
        conn.setReadonly(true);
        conn.setFolder("Prod/EU");
        conn.setDescription("Production DB");

        Map<String, Object> props = new HashMap<>();
        props.put("ssl", "true");
        props.put("apiKey", "api-secret-key");
        conn.setProperties(props);

        assertEquals("id-1", conn.getId());
        assertEquals("Test Conn", conn.getName());
        assertEquals("postgres", conn.getDriver());
        assertEquals("postgresql", conn.getProvider());
        assertEquals("jdbc:postgresql://localhost:5432/db", conn.getUrl());
        assertEquals("localhost", conn.getHost());
        assertEquals(5432, conn.getPort());
        assertEquals("db", conn.getDatabase());
        assertEquals("postgres", conn.getUser());
        assertEquals("secret123", conn.getPassword());
        assertTrue(conn.isReadonly());
        assertEquals("Prod/EU", conn.getFolder());
        assertEquals("Production DB", conn.getDescription());
        assertEquals(2, conn.getProperties().size());
    }

    @Test
    public void testRedactedRemovesSecrets() {
        ConnectionInfo conn = new ConnectionInfo();
        conn.setId("id-1");
        conn.setName("My DB");
        conn.setUser("admin");
        conn.setPassword("SuperSecretPassword");
        conn.setUrl("jdbc:postgresql://admin:SuperSecretPassword@myhost:5432/proddb?password=ParamSecret");

        Map<String, Object> props = new HashMap<>();
        props.put("ssl", "true");
        props.put("databasePassword", "pass-val");
        props.put("secretToken", "token-val");
        props.put("apiKey", "key-val");
        props.put("normalOption", "normal-val");

        // Nested properties test
        Map<String, Object> nested = new HashMap<>();
        nested.put("innerPassword", "inner-pass");
        nested.put("innerToken", "inner-tok");
        nested.put("safeConfig", "safe-val");
        props.put("auth-properties", nested);

        conn.setProperties(props);

        ConnectionInfo redacted = conn.redacted();

        // Password should be null
        assertNull(redacted.getPassword());
        // URL should not contain password
        assertFalse(redacted.getUrl().contains("SuperSecretPassword"));
        assertFalse(redacted.getUrl().contains("ParamSecret"));
        assertTrue(redacted.getUrl().contains("***REDACTED***"));

        // Sensitive properties masked
        assertEquals("***REDACTED***", redacted.getProperties().get("databasePassword"));
        assertEquals("***REDACTED***", redacted.getProperties().get("secretToken"));
        assertEquals("***REDACTED***", redacted.getProperties().get("apiKey"));
        assertEquals("normal-val", redacted.getProperties().get("normalOption"));

        // Nested properties masked
        @SuppressWarnings("unchecked")
        Map<String, Object> redactedNested = (Map<String, Object>) redacted.getProperties().get("auth-properties");
        assertNotNull(redactedNested);
        assertEquals("***REDACTED***", redactedNested.get("innerPassword"));
        assertEquals("***REDACTED***", redactedNested.get("innerToken"));
        assertEquals("safe-val", redactedNested.get("safeConfig"));

        // Identity and other metadata preserved
        assertEquals("id-1", redacted.getId());
        assertEquals("My DB", redacted.getName());
        assertEquals("admin", redacted.getUser());
    }

    @Test
    public void testSanitizeUrlVariants() {
        assertNull(ConnectionInfo.sanitizeUrl(null));
        assertEquals("", ConnectionInfo.sanitizeUrl(""));

        // No credentials
        assertEquals("jdbc:postgresql://localhost:5432/mydb",
                ConnectionInfo.sanitizeUrl("jdbc:postgresql://localhost:5432/mydb"));

        // User and password in authority
        assertEquals("jdbc:postgresql://dbadmin:***REDACTED***@db.example.com:5432/mydb",
                ConnectionInfo.sanitizeUrl("jdbc:postgresql://dbadmin:mysecret@db.example.com:5432/mydb"));

        // Oracle thin URL credentials
        assertEquals("jdbc:oracle:thin:scott/***REDACTED***@localhost:1521:XE",
                ConnectionInfo.sanitizeUrl("jdbc:oracle:thin:scott/tiger@localhost:1521:XE"));
        assertEquals("jdbc:oracle:thin:admin/***REDACTED***@//db.oracle.com:1521/ORCL",
                ConnectionInfo.sanitizeUrl("jdbc:oracle:thin:admin/SecretPass123@//db.oracle.com:1521/ORCL"));

        // Password only (e.g. redis or mongo)
        assertEquals("redis://:***REDACTED***@localhost:6379",
                ConnectionInfo.sanitizeUrl("redis://:mysecret@localhost:6379"));

        // Query parameters
        assertEquals("jdbc:mysql://localhost/test?user=root&password=***REDACTED***",
                ConnectionInfo.sanitizeUrl("jdbc:mysql://localhost/test?user=root&password=supersecret"));

        assertEquals("jdbc:sqlserver://localhost;databaseName=test;pwd=***REDACTED***;user=sa",
                ConnectionInfo.sanitizeUrl("jdbc:sqlserver://localhost;databaseName=test;pwd=supersecret;user=sa"));

        assertEquals("jdbc:db://host/db?token=***REDACTED***&secret=***REDACTED***",
                ConnectionInfo.sanitizeUrl("jdbc:db://host/db?token=tok123&secret=sec456"));
    }
}
