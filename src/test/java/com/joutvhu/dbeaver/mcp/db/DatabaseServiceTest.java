package com.joutvhu.dbeaver.mcp.db;

import com.joutvhu.dbeaver.mcp.model.ConnectionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseServiceTest {

    private DatabaseService dbService;
    private ConnectionInfo h2ConnInfo;

    @BeforeEach
    public void setup() throws Exception {
        dbService = new DatabaseService();

        h2ConnInfo = new ConnectionInfo();
        h2ConnInfo.setId("h2-test");
        h2ConnInfo.setName("H2 In-Memory DB");
        h2ConnInfo.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        h2ConnInfo.setUser("sa");
        h2ConnInfo.setPassword("");

        // Initialize table and sample records
        try (Connection conn = DriverManager.getConnection(h2ConnInfo.getUrl(), "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS users");
            stmt.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(255), bio CLOB, avatar BLOB, data BINARY(4))");
            stmt.execute("INSERT INTO users (id, name, bio, data) VALUES (1, 'Alice', 'Software Engineer', X'01020304')");
        }
    }

    @AfterEach
    public void teardown() throws Exception {
        com.joutvhu.dbeaver.mcp.security.QueryValidator.setReadOnlyModeOverride(null);
        try (Connection conn = DriverManager.getConnection(h2ConnInfo.getUrl(), "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS users");
        }
    }

    @Test
    public void testOpenConnectionAndTestConnection() {
        Map<String, Object> testRes = dbService.testConnection(h2ConnInfo);
        assertTrue((Boolean) testRes.get("success"));
        assertEquals("h2-test", testRes.get("connection_id"));
        assertNotNull(testRes.get("database_product"));
        assertTrue(testRes.get("database_product").toString().contains("H2"));
    }

    @Test
    public void testTestConnectionFailure() {
        ConnectionInfo badConn = new ConnectionInfo();
        badConn.setId("bad-conn");
        badConn.setUrl("jdbc:h2:mem:bad;IFEXISTS=TRUE");

        Map<String, Object> testRes = dbService.testConnection(badConn);
        assertFalse((Boolean) testRes.get("success"));
        assertNotNull(testRes.get("error"));
    }

    @Test
    public void testListTables() throws Exception {
        List<Map<String, Object>> tables = dbService.listTables(h2ConnInfo, null, false);
        assertNotNull(tables);
        assertFalse(tables.isEmpty());
        assertTrue(tables.stream().anyMatch(t -> "USERS".equalsIgnoreCase((String) t.get("table_name"))));

        // Filter by schema
        List<Map<String, Object>> schemaTables = dbService.listTables(h2ConnInfo, "PUBLIC", false);
        assertTrue(schemaTables.stream().anyMatch(t -> "USERS".equalsIgnoreCase((String) t.get("table_name"))));
    }

    @Test
    public void testDescribeTable() throws Exception {
        // Plain table name
        Map<String, Object> desc = dbService.describeTable(h2ConnInfo, "users");
        assertEquals("users", desc.get("table_name"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) desc.get("columns");
        assertNotNull(cols);
        assertFalse(cols.isEmpty());

        Map<String, Object> idCol = cols.stream()
                .filter(c -> "ID".equalsIgnoreCase((String) c.get("column_name")))
                .findFirst()
                .orElse(null);
        assertNotNull(idCol);
        assertTrue((Boolean) idCol.get("is_primary_key"));

        // With schema
        Map<String, Object> descWithSchema = dbService.describeTable(h2ConnInfo, "PUBLIC", "users");
        assertFalse(((List<?>) descWithSchema.get("columns")).isEmpty());

        // Dot-separated schema.table
        Map<String, Object> descDot = dbService.describeTable(h2ConnInfo, "PUBLIC.users");
        assertFalse(((List<?>) descDot.get("columns")).isEmpty());
    }

    @Test
    public void testDescribeTableEmptyNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> dbService.describeTable(h2ConnInfo, ""));
        assertThrows(IllegalArgumentException.class, () -> dbService.describeTable(h2ConnInfo, "  "));
    }

    @Test
    public void testExecuteQuery() throws Exception {
        Map<String, Object> result = dbService.executeQuery(h2ConnInfo, "SELECT id, name, bio, data FROM users WHERE id = 1", 100);
        assertNotNull(result);

        @SuppressWarnings("unchecked")
        List<String> cols = (List<String>) result.get("columns");
        assertEquals(4, cols.size());

        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) result.get("rows");
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).get(0));
        assertEquals("Alice", rows.get(0).get(1));
        assertEquals("Software Engineer", rows.get(0).get(2)); // Clob formatted
        assertNotNull(rows.get(0).get(3)); // Binary formatted to base64
    }

    @Test
    public void testExecuteQueryMaxRows() throws Exception {
        try (Connection conn = DriverManager.getConnection(h2ConnInfo.getUrl(), "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, name) VALUES (2, 'Bob')");
            stmt.execute("INSERT INTO users (id, name) VALUES (3, 'Charlie')");
        }

        Map<String, Object> result = dbService.executeQuery(h2ConnInfo, "SELECT * FROM users", 2);
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) result.get("rows");
        assertEquals(2, rows.size());
    }

    @Test
    public void testExecuteWriteSuccess() throws Exception {
        com.joutvhu.dbeaver.mcp.security.QueryValidator.setReadOnlyModeOverride(false);
        // Insert
        Map<String, Object> insertRes = dbService.executeWrite(h2ConnInfo, "INSERT INTO users (id, name) VALUES (2, 'Bob')");
        assertTrue((Boolean) insertRes.get("success"));
        assertEquals(1, insertRes.get("affected_rows"));

        // Update with WHERE
        Map<String, Object> updateRes = dbService.executeWrite(h2ConnInfo, "UPDATE users SET name = 'Bobby' WHERE id = 2");
        assertTrue((Boolean) updateRes.get("success"));
        assertEquals(1, updateRes.get("affected_rows"));

        // Delete with WHERE
        Map<String, Object> deleteRes = dbService.executeWrite(h2ConnInfo, "DELETE FROM users WHERE id = 2");
        assertTrue((Boolean) deleteRes.get("success"));
        assertEquals(1, deleteRes.get("affected_rows"));
    }

    @Test
    public void testExecuteWriteBlockedOnReadOnlyConnection() {
        com.joutvhu.dbeaver.mcp.security.QueryValidator.setReadOnlyModeOverride(false);
        h2ConnInfo.setReadonly(true);
        assertThrows(IllegalArgumentException.class, () ->
                dbService.executeWrite(h2ConnInfo, "INSERT INTO users (id, name) VALUES (3, 'Charlie')"));
    }

    @Test
    public void testExecuteWriteRollbackOnError() {
        com.joutvhu.dbeaver.mcp.security.QueryValidator.setReadOnlyModeOverride(false);
        // Inserting duplicate primary key should roll back
        assertThrows(Exception.class, () ->
                dbService.executeWrite(h2ConnInfo, "INSERT INTO users (id, name) VALUES (1, 'Duplicate ID')"));
    }

    @Test
    public void testQueryArrayColumn() throws Exception {
        try (Connection conn = DriverManager.getConnection(h2ConnInfo.getUrl(), "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE array_test (id INT PRIMARY KEY, tags INT ARRAY)");
            stmt.execute("INSERT INTO array_test VALUES (1, ARRAY[10, 20, 30])");
        }

        Map<String, Object> res = dbService.executeQuery(h2ConnInfo, "SELECT tags FROM array_test", 10);
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) res.get("rows");
        assertEquals(1, rows.size());
        Object val = rows.get(0).get(0);
        assertTrue(val instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) val;
        assertEquals(3, list.size());
        assertEquals(10, list.get(0));

        try (Connection conn = DriverManager.getConnection(h2ConnInfo.getUrl(), "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS array_test");
        }
    }

    @Test
    public void testGetDatabaseStats() {
        Map<String, Object> stats = dbService.getDatabaseStats(h2ConnInfo);
        assertEquals("h2-test", stats.get("connection_id"));
        assertTrue(stats.get("server_version").toString().contains("H2"));
        assertTrue((Integer) stats.get("table_count") >= 1);
    }

    @Test
    public void testResolveJdbcUrlFromDriverAndHost() throws Exception {
        // Test URL resolution when URL is null
        testDriverUrl("postgres", "localhost", 5432, "mydb", "jdbc:postgresql://localhost:5432/mydb");
        testDriverUrl("mysql", "db.internal", 3306, "test", "jdbc:mysql://db.internal:3306/test");
        testDriverUrl("mariadb", "db.internal", 3306, "test", "jdbc:mariadb://db.internal:3306/test");
        testDriverUrl("sqlserver", "db.internal", 1433, "master", "jdbc:sqlserver://db.internal:1433;databaseName=master");
        testDriverUrl("oracle", "oracle.internal", 1521, "ORCL", "jdbc:oracle:thin:@oracle.internal:1521:ORCL");
        testDriverUrl("sqlite", null, null, "/path/to/db.sqlite", "jdbc:sqlite:/path/to/db.sqlite");
        testDriverUrl("duckdb", null, null, "local.duckdb", "jdbc:duckdb:local.duckdb");
        testDriverUrl("h2", "localhost", null, "mem:test", "jdbc:h2:mem:test");
        testDriverUrl("h2", "remotehost", null, "test", "jdbc:h2:tcp://remotehost/test");
        testDriverUrl("clickhouse", "ch.internal", 8123, "default", "jdbc:clickhouse://ch.internal:8123/default");
    }

    @Test
    public void testExtractProperties() throws Exception {
        ConnectionInfo conn = new ConnectionInfo();
        conn.setUrl("jdbc:h2:mem:testdb_props");
        Map<String, Object> props = new HashMap<>();
        props.put("sslTrustServerCertificate", "true");
        props.put("applicationName", "MCP");
        props.put("user", "ignored_user");
        props.put("password", "ignored_pass");
        props.put("@internal", "ignored_meta");

        Map<String, Object> nested = new HashMap<>();
        nested.put("nestedProp", "val1");
        props.put("nested", nested);

        conn.setProperties(props);

        // Open connection should parse properties safely without throwing
        try (Connection c = dbService.openConnection(conn)) {
            assertNotNull(c);
        }
    }

    private void testDriverUrl(String driver, String host, Integer port, String db, String expectedUrl) throws Exception {
        ConnectionInfo info = new ConnectionInfo();
        info.setDriver(driver);
        info.setHost(host);
        info.setPort(port);
        info.setDatabase(db);

        // Call openConnection with mock or verify via reflection / connect behavior
        java.lang.reflect.Method m = DatabaseService.class.getDeclaredMethod("resolveJdbcUrl", ConnectionInfo.class);
        m.setAccessible(true);
        String resolved = (String) m.invoke(dbService, info);
        assertEquals(expectedUrl, resolved);
    }
}
