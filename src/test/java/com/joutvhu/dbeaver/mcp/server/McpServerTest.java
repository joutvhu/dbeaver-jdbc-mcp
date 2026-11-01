package com.joutvhu.dbeaver.mcp.server;

import com.joutvhu.dbeaver.mcp.Main;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class McpServerTest {

    private McpServer server;

    @BeforeEach
    public void setup() {
        server = new McpServer();
    }

    @Test
    public void testMainConstructor() {
        Main main = new Main();
        assertNotNull(main);
    }

    @Test
    public void testEscapeCsv() {
        assertEquals("", server.escapeCsv(null));
        assertEquals("simple", server.escapeCsv("simple"));
        assertEquals("\"hello, world\"", server.escapeCsv("hello, world"));
        assertEquals("\"quotes \"\"test\"\"\"", server.escapeCsv("quotes \"test\""));
        assertEquals("\"line1\nline2\"", server.escapeCsv("line1\nline2"));
        assertEquals("\"line1\r\nline2\"", server.escapeCsv("line1\r\nline2"));
    }

    @Test
    public void testParseBoolean() {
        assertFalse(server.parseBoolean(null));
        assertTrue(server.parseBoolean(true));
        assertFalse(server.parseBoolean(false));
        assertTrue(server.parseBoolean("true"));
        assertTrue(server.parseBoolean("TRUE"));
        assertTrue(server.parseBoolean(" true "));
        assertFalse(server.parseBoolean("false"));
        assertFalse(server.parseBoolean("xyz"));
        assertFalse(server.parseBoolean(123));
    }

    @Test
    public void testParseInteger() {
        assertEquals(10, server.parseInteger(null, 10));
        assertEquals(42, server.parseInteger(42, 10));
        assertEquals(42L, server.parseInteger(42L, 10));
        assertEquals(99, server.parseInteger("99", 10));
        assertEquals(99, server.parseInteger(" 99 ", 10));
        assertEquals(10, server.parseInteger("not-a-number", 10));
    }

    @Test
    public void testResolveUniqueKey() {
        Map<String, Object> map = new LinkedHashMap<>();
        assertEquals("name", server.resolveUniqueKey(map, "name"));

        map.put("name", "Alice");
        assertEquals("name_2", server.resolveUniqueKey(map, "name"));

        map.put("name_2", "Bob");
        assertEquals("name_3", server.resolveUniqueKey(map, "name"));
    }

    @Test
    public void testListConnectionsToolHandler() {
        SyncToolSpecification spec = server.createListConnectionsTool();
        assertNotNull(spec.tool());
        assertEquals("list_connections", spec.tool().name());

        // Without details
        CallToolRequest reqSummary = CallToolRequest.builder()
                .name("list_connections")
                .arguments(Map.of("details", false))
                .build();
        CallToolResult resSummary = spec.callHandler().apply(null, reqSummary);
        assertNotNull(resSummary);
        assertFalse(resSummary.content().isEmpty());

        // With details
        CallToolRequest reqDetails = CallToolRequest.builder()
                .name("list_connections")
                .arguments(Map.of("details", true))
                .build();
        CallToolResult resDetails = spec.callHandler().apply(null, reqDetails);
        assertNotNull(resDetails);
        assertFalse(resDetails.content().isEmpty());
    }

    @Test
    public void testDebugInfoToolHandler() {
        SyncToolSpecification spec = server.createDebugInfoTool();
        assertNotNull(spec.tool());
        assertEquals("debug_info", spec.tool().name());

        CallToolRequest req = CallToolRequest.builder()
                .name("debug_info")
                .arguments(Map.of())
                .build();
        CallToolResult res = spec.callHandler().apply(null, req);
        assertNotNull(res);
        assertFalse(res.content().isEmpty());
        String text = ((TextContent) res.content().get(0)).text();
        assertTrue(text.contains("workspace_path"));
    }

    @Test
    public void testUnknownConnectionHandledGracefully() {
        Map<String, Object> args = new HashMap<>();
        args.put("connection_id", "non-existent-id");
        args.put("sql", "SELECT 1");
        args.put("table_name", "test_tbl");

        // test_connection
        SyncToolSpecification testConnSpec = server.createTestConnectionTool();
        CallToolResult testConnRes = testConnSpec.callHandler().apply(null, CallToolRequest.builder().name("test_connection").arguments(args).build());
        assertTrue(testConnRes.isError());

        // list_tables
        SyncToolSpecification listTablesSpec = server.createListTablesTool();
        CallToolResult listTablesRes = listTablesSpec.callHandler().apply(null, CallToolRequest.builder().name("list_tables").arguments(args).build());
        assertTrue(listTablesRes.isError());

        // describe_table
        SyncToolSpecification descTableSpec = server.createDescribeTableTool();
        CallToolResult descTableRes = descTableSpec.callHandler().apply(null, CallToolRequest.builder().name("describe_table").arguments(args).build());
        assertTrue(descTableRes.isError());

        // query
        SyncToolSpecification querySpec = server.createQueryTool();
        CallToolResult queryRes = querySpec.callHandler().apply(null, CallToolRequest.builder().name("query").arguments(args).build());
        assertTrue(queryRes.isError());

        // export_query
        SyncToolSpecification exportSpec = server.createExportQueryTool();
        CallToolResult exportRes = exportSpec.callHandler().apply(null, CallToolRequest.builder().name("export_query").arguments(args).build());
        assertTrue(exportRes.isError());

        // execute
        SyncToolSpecification execSpec = server.createExecuteTool();
        CallToolResult execRes = execSpec.callHandler().apply(null, CallToolRequest.builder().name("execute").arguments(args).build());
        assertTrue(execRes.isError());

        // db_stats
        SyncToolSpecification statsSpec = server.createDbStatsTool();
        CallToolResult statsRes = statsSpec.callHandler().apply(null, CallToolRequest.builder().name("db_stats").arguments(args).build());
        assertTrue(statsRes.isError());
    }
}
