package com.joutvhu.dbeaver.mcp.driver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DriverRegistryTest {

    @Test
    public void testDynamicDriverDiscovery() {
        DriverRegistry registry = DriverRegistry.getInstance();
        List<String> loadedClasses = registry.getLoadedDriverClassNames();
        System.err.println("Loaded driver classes: " + loadedClasses);

        assertFalse(loadedClasses.isEmpty(), "Should have found and loaded at least one JDBC driver from DBeaver drivers folder");
        assertTrue(loadedClasses.contains("com.microsoft.sqlserver.jdbc.SQLServerDriver"),
                "Should have loaded com.microsoft.sqlserver.jdbc.SQLServerDriver from DBeaver drivers");
    }
}
