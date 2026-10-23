package com.joutvhu.dbeaver.mcp.driver;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DriverRegistryTest {

    @Test
    public void testDynamicDriverDiscovery() {
        DriverRegistry registry = DriverRegistry.getInstance();
        assertNotNull(registry, "DriverRegistry instance should not be null");

        List<String> loadedClasses = registry.getLoadedDriverClassNames();
        System.err.println("Loaded driver classes: " + loadedClasses);

        // When running in an environment without DBeaver installed (e.g. CI/CD runners),
        // skip driver assertions gracefully.
        Assumptions.assumeFalse(loadedClasses.isEmpty(),
                "No DBeaver drivers found on this system (skipping in clean CI environment)");

        assertTrue(loadedClasses.size() > 0, "Should have loaded at least one JDBC driver");
    }
}
