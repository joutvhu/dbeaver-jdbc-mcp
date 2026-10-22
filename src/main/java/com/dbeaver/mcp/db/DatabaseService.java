package com.dbeaver.mcp.db;

import com.dbeaver.mcp.driver.DriverRegistry;
import com.dbeaver.mcp.model.ConnectionInfo;
import com.dbeaver.mcp.security.QueryValidator;

import java.sql.*;
import java.util.*;

public class DatabaseService {

    public Connection openConnection(ConnectionInfo connInfo) throws SQLException {
        String url = connInfo.getUrl();
        if (url == null || url.trim().isEmpty()) {
            throw new SQLException("No JDBC URL defined for connection: " + connInfo.getName());
        }

        Properties props = new Properties();
        if (connInfo.getUser() != null && !connInfo.getUser().isEmpty()) {
            props.put("user", connInfo.getUser());
        }
        if (connInfo.getPassword() != null && !connInfo.getPassword().isEmpty()) {
            props.put("password", connInfo.getPassword());
        }

        if (connInfo.getProperties() != null) {
            for (Map.Entry<String, Object> entry : connInfo.getProperties().entrySet()) {
                if (entry.getValue() != null && !"user".equalsIgnoreCase(entry.getKey()) && !"password".equalsIgnoreCase(entry.getKey())) {
                    props.put(entry.getKey(), entry.getValue().toString());
                }
            }
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
            String schemaPattern = (schema != null && !schema.trim().isEmpty()) ? schema.trim() : null;

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

        String schema = null;
        String table = tableName.trim();
        if (table.contains(".")) {
            String[] parts = table.split("\\.");
            if (parts.length == 2) {
                schema = parts[0];
                table = parts[1];
            } else if (parts.length == 3) {
                schema = parts[1];
                table = parts[2];
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("table_name", tableName);

        try (Connection conn = openConnection(connInfo)) {
            DatabaseMetaData meta = conn.getMetaData();

            // Primary Keys
            Set<String> primaryKeys = new HashSet<>();
            try (ResultSet pkRs = meta.getPrimaryKeys(null, schema, table)) {
                while (pkRs.next()) {
                    primaryKeys.add(pkRs.getString("COLUMN_NAME"));
                }
            } catch (Exception ignored) {}

            // Columns
            List<Map<String, Object>> columns = new ArrayList<>();
            try (ResultSet colRs = meta.getColumns(null, schema, table, "%")) {
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

            response.put("columns", columns);
        }
        return response;
    }

    public Map<String, Object> executeQuery(ConnectionInfo connInfo, String sql, int maxRows) throws Exception {
        QueryValidator.validateReadOnlyQuery(sql);

        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();

        try (Connection conn = openConnection(connInfo)) {
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
                                row.add(rs.getObject(i));
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
}
