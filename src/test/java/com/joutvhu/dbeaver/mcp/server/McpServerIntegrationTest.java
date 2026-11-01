package com.joutvhu.dbeaver.mcp.server;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class McpServerIntegrationTest {

    @Test
    public void testServerStdioFlow() throws Exception {
        String jarPath = "target/dbeaver-jdbc-mcp.jar";
        if (!new File(jarPath).exists()) {
            jarPath = "dbeaver-jdbc-mcp.jar";
        }
        if (!new File(jarPath).exists()) {
            System.err.println("Skipping testServerStdioFlow as jar is not built yet.");
            return;
        }

        String javaHome = System.getProperty("java.home");
        String javaBin = (javaHome != null) ? java.nio.file.Paths.get(javaHome, "bin", "java").toString() : "java";

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", jarPath);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();

        OutputStream os = p.getOutputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));

        try {
            // 1. Initialize
            os.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1.0.0\"}}}\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            String line1 = reader.readLine();
            System.err.println("Line 1 (initialize): " + line1);
            assertNotNull(line1);
            assertTrue(line1.contains("\"id\":1"));

            // 2. Initialized notification
            os.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            // 3. tools/list
            os.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            String line2 = reader.readLine();
            System.err.println("Line 2 (tools/list): " + line2);
            assertNotNull(line2);
            assertTrue(line2.contains("\"id\":2"));
            assertTrue(line2.contains("list_connections"));

            // 4. tools/call list_connections
            os.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_connections\",\"arguments\":{\"details\":false}}}\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            String line3 = reader.readLine();
            System.err.println("Line 3 (tools/call list_connections): " + line3);
            assertNotNull(line3);
            assertTrue(line3.contains("\"id\":3"));
            assertTrue(line3.contains("\"content\""));

            // 5. tools/call debug_info
            os.write("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"debug_info\",\"arguments\":{}}}\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            String line4 = reader.readLine();
            System.err.println("Line 4 (tools/call debug_info): " + line4);
            assertNotNull(line4);
            assertTrue(line4.contains("\"id\":4"));
            assertTrue(line4.contains("workspace_path"));

            // 6. tools/call test_connection with non-existent connection
            os.write("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"test_connection\",\"arguments\":{\"connection_id\":\"non-existent\"}}}\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            String line5 = reader.readLine();
            System.err.println("Line 5 (tools/call test_connection error): " + line5);
            assertNotNull(line5);
            assertTrue(line5.contains("\"id\":5"));
            assertTrue(line5.contains("Connection not found"));

            // 7. Verify clean shutdown on stdin EOF (client disconnection)
            os.close();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(finished, "Server process should terminate cleanly when stdin is closed");
            assertEquals(0, p.exitValue());
        } finally {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
