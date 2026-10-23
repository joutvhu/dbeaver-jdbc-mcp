package com.dbeaver.mcp.db;

import com.dbeaver.mcp.driver.DriverRegistry;
import com.dbeaver.mcp.model.ConnectionInfo;
import com.dbeaver.mcp.security.QueryValidator;

import java.sql.*;
import java.util.*;

public class DatabaseService {

    public Connection openConnection(ConnectionInfo connInfo) throws SQLException {
        String url = resolveJdbcUrl(connInfo);
        if (url == null || url.trim().isEmpty()) {
            throw new SQLException("No JDBC URL defined or resolvable for connection: " + connInfo.getName());
        }

        Properties props = new Properties();
        if (connInfo.getUser() != null && !connInfo.getUser().isEmpty()) {
            props.put("user", connInfo.getUser());
        }
        if (connInfo.getPassword() != null && !connInfo.getPassword().isEmpty()) {
            props.put("password", connInfo.getPassword());
        }

        if (connInfo.getProperties() != null) {
            extractProperties(connInfo.getProperties(), props);
        }

        // Trust server cert for SQL Server by default if not set
        if (url.toLowerCase().contains("sqlserver") && !props.containsKey("trustServerCertificate")) {
            props.put("trustServerCertificate", "true");
        }

        // Apply timeout from environment if configured
        String timeoutEnv = System.getenv("DBEAVER_QUERY_TIMEOUT_SECONDS");
        if (timeoutEnv != null && !timeoutEnv.trim().isEmpty()) {
            try {
                DriverManager.setLoginTimeout(Integer.parseInt(timeoutEnv.trim()));
            } catch (Exception ignored) {}
        }

        return DriverRegistry.getInstance().connect(url, props);
    }

    public Map<String, Object> testConnection(ConnectionInfo connInfo) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connection_id", connInfo.getId());

        try (Connection conn = openConnection(connInfo)) {
            DatabaseMetaData meta = conn.getMetaData();
            String dbProduct = meta.getDatabaseProductName();
            String dbVersion = meta.getDatabaseProductVersion();

            result.put("success", true);
            result.put("response_time_ms", System.currentTimeMillis() - start);
            result.put("database_product", dbProduct);
            result.put("database_version", dbVersion);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("response_time_ms", System.currentTimeMillis() - start);
        }
        return result;
    }

    public List<Map<String, Object>> listTables(ConnectionInfo connInfo, String schema, boolean includeViews) throws SQLException {
        List<Map<String, Object>> tables = new ArrayList<>();
        String[] types = includeViews ? new String[]{"TABLE", "VIEW"} : new String[]{"TABLE"};

        try (Connection conn = openConnection(connInfo)) {
            DatabaseMetaData meta = conn.getMetaData();
            String schemaPattern = (schema != null && !schema.trim().isEmpty()) ? stripQuotes(schema.trim()) : null;

            try (ResultSet rs = meta.getTables(null, schemaPattern, "%", types)) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("table_name", rs.getString("TABLE_NAME"));
                    row.put("table_type", rs.getString("TABLE_TYPE"));
                    row.put("table_schema", rs.getString("TABLE_SCHEM"));
                    row.put("table_cat", rs.getString("TABLE_CAT"));
                    tables.add(row);
                }
            }
        }
        return tables;
    }

    public Map<String, Object> describeTable(ConnectionInfo connInfo, String tableName) throws SQLException {
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("table_name cannot be empty");
        }

        String catalog = null;
        String schema = null;
        String rawTable = tableName.trim();
        if (rawTable.contains(".")) {
            String[] parts = rawTable.split("\\.");
            if (parts.length == 2) {
                schema = stripQuotes(parts[0]);
                rawTable = stripQuotes(parts[1]);
            } else if (parts.length == 3) {
                catalog = stripQuotes(parts[0]);
                schema = stripQuotes(parts[1]);
                rawTable = stripQuotes(parts[2]);
            }
        } else {
            rawTable = stripQuotes(rawTable);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("table_name", tableName);

        try (Connection conn = openConnection(connInfo)) {
            DatabaseMetaData meta = conn.getMetaData();

            // Try exact case, then uppercase (Oracle/DB2/Snowflake), then lowercase (Postgres)
            List<String> tableCandidates = List.of(rawTable, rawTable.toUpperCase(), rawTable.toLowerCase());
            List<Map<String, Object>> columns = new ArrayList<>();
            Set<String> primaryKeys = new HashSet<>();

            for (String tableCandidate : tableCandidates) {
                // Primary Keys
                try (ResultSet pkRs = meta.getPrimaryKeys(catalog, schema, tableCandidate)) {
                    while (pkRs.next()) {
                        primaryKeys.add(pkRs.getString("COLUMN_NAME"));
                    }
                } catch (Exception ignored) {}

                // Columns
                try (ResultSet colRs = meta.getColumns(catalog, schema, tableCandidate, "%")) {
                    while (colRs.next()) {
                        Map<String, Object> col = new LinkedHashMap<>();
                        String colName = colRs.getString("COLUMN_NAME");
                        col.put("column_name", colName);
                        col.put("data_type", colRs.getString("TYPE_NAME"));
                        col.put("column_size", colRs.getInt("COLUMN_SIZE"));
                        col.put("decimal_digits", colRs.getInt("DECIMAL_DIGITS"));
                        col.put("is_nullable", "YES".equalsIgnoreCase(colRs.getString("IS_NULLABLE")));
                        col.put("column_default", colRs.getString("COLUMN_DEF"));
                        col.put("is_primary_key", primaryKeys.contains(colName));
                        columns.add(col);
                    }
                }

                if (!columns.isEmpty()) {
                    break;
                }
            }

            response.put("columns", columns);
        }
        return response;
    }

    public Map<String, Object> executeQuery(ConnectionInfo connInfo, String sql, int maxRows) throws Exception {
        QueryValidator.validateReadOnlyQuery(sql);

        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();

        try (Connection conn = openConnection(connInfo)) {
            if (connInfo.isReadonly()) {
                try {
                    conn.setReadOnly(true);
                } catch (Exception ignored) {}
            }

            try (Statement stmt = conn.createStatement()) {
                // Apply query timeout if set
                String timeoutEnv = System.getenv("DBEAVER_QUERY_TIMEOUT_SECONDS");
                if (timeoutEnv != null && !timeoutEnv.trim().isEmpty()) {
                    try {
                        stmt.setQueryTimeout(Integer.parseInt(timeoutEnv.trim()));
                    } catch (Exception ignored) {}
                }

                // Apply max rows limit from env if present
                String maxRowsEnv = System.getenv("DBEAVER_MAX_ROWS_LIMIT");
                int effectiveMaxRows = maxRows;
                if (maxRowsEnv != null && !maxRowsEnv.trim().isEmpty()) {
                    try {
                        int envLimit = Integer.parseInt(maxRowsEnv.trim());
                        if (effectiveMaxRows <= 0 || effectiveMaxRows > envLimit) {
                            effectiveMaxRows = envLimit;
                        }
                    } catch (Exception ignored) {}
                }

                if (effectiveMaxRows > 0) {
                    stmt.setMaxRows(effectiveMaxRows);
                }

                boolean hasResultSet = stmt.execute(sql);
                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int colCount = md.getColumnCount();
                        List<String> columnNames = new ArrayList<>();
                        for (int i = 1; i <= colCount; i++) {
                            columnNames.add(md.getColumnLabel(i));
                        }

                        List<List<Object>> rows = new ArrayList<>();
                        while (rs.next()) {
                            List<Object> row = new ArrayList<>(colCount);
                            for (int i = 1; i <= colCount; i++) {
                                row.add(formatJdbcValue(rs.getObject(i)));
                            }
                            rows.add(row);
                        }

                        result.put("columns", columnNames);
                        result.put("rows", rows);
                        result.put("row_count", rows.size());
                    }
                } else {
                    result.put("columns", Collections.emptyList());
                    result.put("rows", Collections.emptyList());
                    result.put("row_count", stmt.getUpdateCount());
                }
            }
        }

        result.put("execution_time_ms", System.currentTimeMillis() - start);
        return result;
    }

    public Map<String, Object> executeWrite(ConnectionInfo connInfo, String sql) throws Exception {
        if (connInfo.isReadonly()) {
            throw new IllegalArgumentException("Connection '" + connInfo.getName() + "' is configured as read-only in DBeaver. Write operations are blocked.");
        }

        QueryValidator.validateWriteQuery(sql);

        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();

        try (Connection conn = openConnection(connInfo)) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                String timeoutEnv = System.getenv("DBEAVER_QUERY_TIMEOUT_SECONDS");
                if (timeoutEnv != null && !timeoutEnv.trim().isEmpty()) {
                    try {
                        stmt.setQueryTimeout(Integer.parseInt(timeoutEnv.trim()));
                    } catch (Exception ignored) {}
                }

                int affectedRows = stmt.executeUpdate(sql);
                conn.commit();
                result.put("success", true);
                result.put("affected_rows", affectedRows);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }

        result.put("execution_time_ms", System.currentTimeMillis() - start);
        return result;
    }

    public Map<String, Object> getDatabaseStats(ConnectionInfo connInfo) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connection_id", connInfo.getId());

        try (Connection conn = openConnection(connInfo)) {
            DatabaseMetaData meta = conn.getMetaData();
            result.put("server_version", meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());

            int tableCount = 0;
            try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tableCount++;
                }
            }
            result.put("table_count", tableCount);
            result.put("connection_time_ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            result.put("server_version", "Unknown");
            result.put("table_count", 0);
            result.put("error", e.getMessage());
            result.put("connection_time_ms", System.currentTimeMillis() - start);
        }

        return result;
    }

    private String resolveJdbcUrl(ConnectionInfo connInfo) {
        String url = connInfo.getUrl();
        if (url != null && !url.trim().isEmpty()) {
            return url.trim();
        }

        String driver = (connInfo.getDriver() != null ? connInfo.getDriver() : "").toLowerCase();
        String provider = (connInfo.getProvider() != null ? connInfo.getProvider() : "").toLowerCase();
        String host = (connInfo.getHost() != null && !connInfo.getHost().isEmpty()) ? connInfo.getHost() : "localhost";
        Integer port = connInfo.getPort();
        String db = connInfo.getDatabase() != null ? connInfo.getDatabase() : "";

        if (driver.contains("postgres") || provider.contains("postgres")) {
            return "jdbc:postgresql://" + host + ":" + (port != null ? port : 5432) + "/" + db;
        } else if (driver.contains("mysql") || provider.contains("mysql")) {
            return "jdbc:mysql://" + host + ":" + (port != null ? port : 3306) + "/" + db;
        } else if (driver.contains("mariadb") || provider.contains("mariadb")) {
            return "jdbc:mariadb://" + host + ":" + (port != null ? port : 3306) + "/" + db;
        } else if (driver.contains("sqlserver") || driver.contains("microsoft") || provider.contains("sqlserver")) {
            return "jdbc:sqlserver://" + host + ":" + (port != null ? port : 1433) + ";databaseName=" + db;
        } else if (driver.contains("oracle") || provider.contains("oracle")) {
            return "jdbc:oracle:thin:@" + host + ":" + (port != null ? port : 1521) + ":" + db;
        } else if (driver.contains("sqlite") || provider.contains("sqlite")) {
            return "jdbc:sqlite:" + db;
        } else if (driver.contains("duckdb") || provider.contains("duckdb")) {
            return "jdbc:duckdb:" + db;
        } else if (driver.contains("clickhouse") || provider.contains("clickhouse")) {
            return "jdbc:clickhouse://" + host + ":" + (port != null ? port : 8123) + "/" + db;
        }
        return null;
    }

    private Object formatJdbcValue(Object val) throws SQLException {
        if (val == null) {
            return null;
        }
        if (val instanceof Clob) {
            Clob clob = (Clob) val;
            long len = clob.length();
            return clob.getSubString(1, (int) Math.min(len, 500_000));
        }
        if (val instanceof Blob) {
            Blob blob = (Blob) val;
            long len = blob.length();
            byte[] bytes = blob.getBytes(1, (int) Math.min(len, 50_000));
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (val instanceof java.sql.Array) {
            try {
                Object arr = ((java.sql.Array) val).getArray();
                if (arr instanceof Object[]) {
                    return Arrays.asList((Object[]) arr);
                }
            } catch (Exception ignored) {}
        }
        if (val instanceof byte[]) {
            return Base64.getEncoder().encodeToString((byte[]) val);
        }
        return val;
    }

    @SuppressWarnings("unchecked")
    private void extractProperties(Map<String, Object> source, Properties target) {
        if (source == null) return;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val == null || key == null || key.startsWith("@")) {
                continue;
            }
            if ("user".equalsIgnoreCase(key) || "password".equalsIgnoreCase(key)) {
                continue;
            }
            if (val instanceof Map) {
                extractProperties((Map<String, Object>) val, target);
            } else {
                target.put(key, val.toString());
                if ("sslTrustServerCertificate".equalsIgnoreCase(key)) {
                    target.put("trustServerCertificate", val.toString());
                }
            }
        }
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        String t = s.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) ||
                (t.startsWith("`") && t.endsWith("`")) ||
                (t.startsWith("[") && t.endsWith("]"))) {
            return t.substring(1, t.length() - 1).trim();
        }
        return t;
    }
}
