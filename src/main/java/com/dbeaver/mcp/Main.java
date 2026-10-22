package com.dbeaver.mcp;

import com.dbeaver.mcp.server.McpServer;

public class Main {
    public static void main(String[] args) {
        try {
            McpServer server = new McpServer();
            server.start();
        } catch (Throwable t) {
            System.err.println("[Main] Fatal server error: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
