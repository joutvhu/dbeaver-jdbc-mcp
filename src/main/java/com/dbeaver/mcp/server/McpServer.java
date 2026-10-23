package com.dbeaver.mcp.server;

import com.dbeaver.mcp.config.DBeaverConfigParser;
import com.dbeaver.mcp.db.DatabaseService;
import com.dbeaver.mcp.driver.DriverRegistry;
import com.dbeaver.mcp.model.ConnectionInfo;
import com.dbeaver.mcp.security.QueryValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.io.File;
import java.io.StringWriter;
import java.util.*;

public class McpServer {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DBeaverConfigParser configParser = new DBeaverConfigParser();
    private final DatabaseService dbService = new DatabaseService();

    public void start() {
        System.err.println("[McpServer] Starting DBeaver MCP Server using official Java SDK 2.x...");

        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        Implementation serverInfo = Implementation.builder("dbeaver-database", "1.0.0")
                .title("DBeaver Database MCP Server")
                .description("Model Context Protocol server for DBeaver with dynamic JDBC driver loading, supporting all DBeaver database connections.")
                .websiteUrl("https://github.com/joutvhu/dbeaver-mcp")
                .build();

        String instructions = "Access and interact with any database configured in DBeaver (PostgreSQL, MySQL, SQL Server, Oracle, SQLite, Snowflake, ClickHouse, etc.) using DBeaver's native JDBC drivers. Use 'list_connections' first to discover configured connections, 'list_tables' and 'describe_table' to inspect schemas, and 'query' to execute read-only queries safely.";

        McpSyncServer syncServer = io.modelcontextprotocol.server.McpServer.sync(transportProvider)
                .serverInfo(serverInfo)
                .instructions(instructions)
                .capabilities(ServerCapabilities.builder()
                        .tools(false)
                        .build())
                .tools(
                        createListConnectionsTool(),
                        createTestConnectionTool(),
                        createListTablesTool(),
                        createDescribeTableTool(),
                        createQueryTool(),
                        createExportQueryTool(),
                        createExecuteTool(),
                        createDbStatsTool(),
                        createDebugInfoTool()
                )
                .build();

        System.err.println("[McpServer] Server listening on STDIO...");

        // Keep main thread alive until stdin reaches EOF (client closes connection)
        try {
            while (System.in.read() != -1) {
                // Keep reading until stream terminates
            }
        } catch (Exception ignored) {
        } finally {
            try {
                syncServer.close();
            } catch (Exception ignored) {
            }
        }
    }

    // 1. list_connections
    private SyncToolSpecification createListConnectionsTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "details", Map.of(
                                "type", "boolean",
                                "description", "If True, show full connection details (with credentials redacted). If False, show simplified list."
                        )
                )
        );

        Tool tool = Tool.builder("list_connections", schema)
                .description("List all DBeaver database connections.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        boolean details = Boolean.TRUE.equals(request.arguments().get("details"));
                        List<ConnectionInfo> list = configParser.parseConnections();
                        if (list.isEmpty()) {
                            Map<String, Object> debug = Map.of(
                                    "error", "No DBeaver connections found",
                                    "workspace", configParser.getWorkspacePath()
                            );
                            return CallToolResult.builder()
                                    .content(List.of(new TextContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(debug))))
                                    .build();
                        }

                        if (details) {
                            List<ConnectionInfo> redacted = new ArrayList<>();
                            for (ConnectionInfo c : list) redacted.add(c.redacted());
                            return CallToolResult.builder()
                                    .content(List.of(new TextContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(redacted))))
                                    .build();
                        } else {
                            List<Map<String, Object>> summary = new ArrayList<>();
                            for (ConnectionInfo c : list) {
                                Map<String, Object> map = new LinkedHashMap<>();
                                map.put("id", c.getId());
                                map.put("name", c.getName());
                                map.put("driver", c.getDriver());
                                map.put("host", c.getHost());
                                map.put("database", c.getDatabase());
                                summary.add(map);
                            }
                            return CallToolResult.builder()
                                    .content(List.of(new TextContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary))))
                                    .build();
                        }
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Error listing connections: " + e.getMessage())))
                                .build();
                    }
                })
                .build();
    }

    // 2. test_connection
    private SyncToolSpecification createTestConnectionTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "connection_id", Map.of(
                                "type", "string",
                                "description", "The connection ID or name from list_connections."
                        )
                ),
                "required", List.of("connection_id")
        );

        Tool tool = Tool.builder("test_connection", schema)
                .description("Test a database connection and return connectivity status.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        String connId = String.valueOf(request.arguments().get("connection_id"));
                        ConnectionInfo conn = configParser.getConnection(connId);
                        if (conn == null) {
                            return CallToolResult.builder()
                                    .isError(true)
                                    .content(List.of(new TextContent("Connection not found: " + connId)))
                                    .build();
                        }
                        Map<String, Object> result = dbService.testConnection(conn);
                        return CallToolResult.builder()
                                .content(List.of(new TextContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))))
                                .build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Error testing connection: " + e.getMessage())))
                                .build();
                    }
                })
                .build();
    }

    // 3. list_tables
    private SyncToolSpecification createListTablesTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "connection_id", Map.of(
                                "type", "string",
                                "description", "The connection ID or name from list_connections."
                        ),
                        "schema", Map.of(
                                "type", "string",
                                "description", "Optional schema name to filter by."
                        ),
                        "include_views", Map.of(
                                "type", "boolean",
                                "description", "If True, include views in the results (default false)."
                        )
                ),
                "required", List.of("connection_id")
        );

        Tool tool = Tool.builder("list_tables", schema)
                .description("List tables (and optionally views) in a database.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        String connId = String.valueOf(request.arguments().get("connection_id"));
                        String dbSchema = request.arguments().get("schema") != null ? String.valueOf(request.arguments().get("schema")) : null;
                        boolean includeViews = Boolean.TRUE.equals(request.arguments().get("include_views"));

                        ConnectionInfo conn = configParser.getConnection(connId);
                        if (conn == null) {
                            return CallToolResult.builder()
                                    .isError(true)
                                    .content(List.of(new TextContent("Connection not found: " + connId)))
                                    .build();
                        }
                        List<Map<String, Object>> tables = dbService.listTables(conn, dbSchema, includeViews);
                        return CallToolResult.builder()
                                .content(List.of(new TextContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tables))))
                                .build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Error listing tables: " + e.getMessage())))
                                .build();
                    }
                })
                .build();
    }

    // 4. describe_table
    private SyncToolSpecification createDescribeTableTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "connection_id", Map.of(
                                "type", "string",
                                "description", "The connection ID or name from list_connections."
                        ),
                        "table_name", Map.of(
                                "type", "string",
                                "description", "The name of the table to describe."
                        )
                ),
                "required", List.of("connection_id", "table_name")
        );

        Tool tool = Tool.builder("describe_table", schema)
                .description("Describe a table's schema (columns, types, constraints, primary keys).")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        String connId = String.valueOf(request.arguments().get("connection_id"));
                        String tableName = String.valueOf(request.arguments().get("table_name"));

                        ConnectionInfo conn = configParser.getConnection(connId);
                        if (conn == null) {
                            return CallToolResult.builder()
                                    .isError(true)
                                    .content(List.of(new TextContent("Connection not found: " + connId)))
                                    .build();
                        }
                        Map<String, Object> tableDesc = dbService.describeTable(conn, tableName);
                        return CallToolResult.builder()
                                .content(List.of(new TextContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tableDesc))))
                                .build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Error describing table: " + e.getMessage())))
                                .build();
                    }
                })
                .build();
    }

    // 5. query
    private SyncToolSpecification createQueryTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "connection_id", Map.of(
                                "type", "string",
                                "description", "The connection ID or name from list_connections."
                        ),
                        "sql", Map.of(
                                "type", "string",
                                "description", "The SQL query to execute. Only read-only queries are allowed."
                        ),
                        "max_rows", Map.of(
                                "type", "integer",
                                "description", "Maximum number of rows to return (default 1000)."
                        )
                ),
                "required", List.of("connection_id", "sql")
        );

        Tool tool = Tool.builder("query", schema)
                .description("Execute a read-only SQL query (SELECT, EXPLAIN, SHOW, DESCRIBE only).")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        String connId = String.valueOf(request.arguments().get("connection_id"));
                        String sql = String.valueOf(request.arguments().get("sql"));
                        int maxRows = request.arguments().get("max_rows") != null ? ((Number) request.arguments().get("max_rows")).intValue() : 1000;

                        ConnectionInfo conn = configParser.getConnection(connId);
                        if (conn == null) {
                            return CallToolResult.builder()
                                    .isError(true)
                                    .content(List.of(new TextContent("Connection not found: " + connId)))
                                    .build();
                        }
                        Map<String, Object> queryResult = dbService.executeQuery(conn, sql, maxRows);
                        return CallToolResult.builder()
                                .content(List.of(new TextContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(queryResult))))
                                .build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Error executing query: " + e.getMessage())))
                                .build();
                    }
                })
                .build();
    }

    // 6. export_query
    private SyncToolSpecification createExportQueryTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "connection_id", Map.of(
                                "type", "string",
                                "description", "The connection ID or name from list_connections."
                        ),
                        "sql", Map.of(
                                "type", "string",
                                "description", "The SQL query to execute. Only read-only queries are allowed."
                        ),
                        "format", Map.of(
                                "type", "string",
                                "description", "Output format: 'json' (array of objects) or 'csv'."
                        ),
                        "max_rows", Map.of(
                                "type", "integer",
                                "description", "Maximum number of rows to return (default 10000)."
                        )
                ),
                "required", List.of("connection_id", "sql")
        );

        Tool tool = Tool.builder("export_query", schema)
                .description("Execute a query and return results in the specified format (json or csv).")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        String connId = String.valueOf(request.arguments().get("connection_id"));
                        String sql = String.valueOf(request.arguments().get("sql"));
                        String format = request.arguments().get("format") != null ? String.valueOf(request.arguments().get("format")) : "json";
                        int maxRows = request.arguments().get("max_rows") != null ? ((Number) request.arguments().get("max_rows")).intValue() : 10000;

                        ConnectionInfo conn = configParser.getConnection(connId);
                        if (conn == null) {
                            return CallToolResult.builder()
                                    .isError(true)
                                    .content(List.of(new TextContent("Connection not found: " + connId)))
                                    .build();
                        }

                        Map<String, Object> queryResult = dbService.executeQuery(conn, sql, maxRows);
                        @SuppressWarnings("unchecked")
                        List<String> columns = (List<String>) queryResult.get("columns");
                        @SuppressWarnings("unchecked")
                        List<List<Object>> rows = (List<List<Object>>) queryResult.get("rows");

                        if ("csv".equalsIgnoreCase(format)) {
                            StringWriter sw = new StringWriter();
                            for (int i = 0; i < columns.size(); i++) {
                                sw.append(escapeCsv(columns.get(i)));
                                if (i < columns.size() - 1) sw.append(",");
                            }
                            sw.append("\r\n");

                            for (List<Object> row : rows) {
                                for (int i = 0; i < row.size(); i++) {
                                    Object val = row.get(i);
                                    sw.append(escapeCsv(val != null ? val.toString() : ""));
                                    if (i < row.size() - 1) sw.append(",");
                                }
                                sw.append("\r\n");
                            }
                            return CallToolResult.builder()
                                    .content(List.of(new TextContent(sw.toString())))
                                    .build();
                        } else {
                            List<Map<String, Object>> list = new ArrayList<>();
                            for (List<Object> row : rows) {
                                Map<String, Object> obj = new LinkedHashMap<>();
                                for (int i = 0; i < columns.size(); i++) {
                                    obj.put(columns.get(i), i < row.size() ? row.get(i) : null);
                                }
                                list.add(obj);
                            }
                            return CallToolResult.builder()
                                    .content(List.of(new TextContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(list))))
                                    .build();
                        }
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Error exporting query: " + e.getMessage())))
                                .build();
                    }
                })
                .build();
    }

    // 7. execute
    private SyncToolSpecification createExecuteTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "connection_id", Map.of(
                                "type", "string",
                                "description", "The connection ID or name from list_connections."
                        ),
                        "sql", Map.of(
                                "type", "string",
                                "description", "The SQL statement to execute. Must be INSERT, UPDATE (with WHERE), DELETE (with WHERE), or MERGE."
                        )
                ),
                "required", List.of("connection_id", "sql")
        );

        Tool tool = Tool.builder("execute", schema)
                .description("Execute a data modification query (INSERT, UPDATE, DELETE, MERGE). Blocked when READ_ONLY=true.")
                .annotations(ToolAnnotations.builder().destructiveHint(true).build())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        String connId = String.valueOf(request.arguments().get("connection_id"));
                        String sql = String.valueOf(request.arguments().get("sql"));

                        ConnectionInfo conn = configParser.getConnection(connId);
                        if (conn == null) {
                            return CallToolResult.builder()
                                    .isError(true)
                                    .content(List.of(new TextContent("Connection not found: " + connId)))
                                    .build();
                        }

                        Map<String, Object> result = dbService.executeWrite(conn, sql);
                        return CallToolResult.builder()
                                .content(List.of(new TextContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))))
                                .build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Error executing statement: " + e.getMessage())))
                                .build();
                    }
                })
                .build();
    }

    // 8. db_stats
    private SyncToolSpecification createDbStatsTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "connection_id", Map.of(
                                "type", "string",
                                "description", "The connection ID or name from list_connections."
                        )
                ),
                "required", List.of("connection_id")
        );

        Tool tool = Tool.builder("db_stats", schema)
                .description("Get database statistics (table count, server version, connection time).")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        String connId = String.valueOf(request.arguments().get("connection_id"));
                        ConnectionInfo conn = configParser.getConnection(connId);
                        if (conn == null) {
                            return CallToolResult.builder()
                                    .isError(true)
                                    .content(List.of(new TextContent("Connection not found: " + connId)))
                                    .build();
                        }
                        Map<String, Object> stats = dbService.getDatabaseStats(conn);
                        return CallToolResult.builder()
                                .content(List.of(new TextContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stats))))
                                .build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Error getting database stats: " + e.getMessage())))
                                .build();
                    }
                })
                .build();
    }

    // 9. debug_info
    private SyncToolSpecification createDebugInfoTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Collections.emptyMap()
        );

        Tool tool = Tool.builder("debug_info", schema)
                .description("Show DBeaver workspace debug information, paths, and loaded JDBC drivers.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> debug = new LinkedHashMap<>();
                        debug.put("workspace_path", configParser.getWorkspacePath());
                        File ds = new File(configParser.getWorkspacePath(), "General/.dbeaver/data-sources.json");
                        debug.put("data_sources_exists", ds.exists());
                        debug.put("read_only_mode", QueryValidator.isReadOnlyMode());
                        debug.put("loaded_driver_classes", DriverRegistry.getInstance().getLoadedDriverClassNames());
                        debug.put("loaded_driver_jars_count", DriverRegistry.getInstance().getLoadedJarPaths().size());
                        debug.put("loaded_driver_jars", DriverRegistry.getInstance().getLoadedJarPaths());

                        return CallToolResult.builder()
                                .content(List.of(new TextContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(debug))))
                                .build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Error retrieving debug info: " + e.getMessage())))
                                .build();
                    }
                })
                .build();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
