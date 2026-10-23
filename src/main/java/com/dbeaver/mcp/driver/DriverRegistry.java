package com.dbeaver.mcp.driver;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class DriverRegistry {
    private static DriverRegistry instance;

    private final List<Driver> loadedDrivers = new ArrayList<>();
    private final List<String> loadedJarPaths = new ArrayList<>();
    private URLClassLoader classLoader;

    public static synchronized DriverRegistry getInstance() {
        if (instance == null) {
            instance = new DriverRegistry();
            instance.init();
        }
        return instance;
    }

    private void init() {
        List<File> jarFiles = findDriverJars();
        List<URL> urls = new ArrayList<>();
        for (File jar : jarFiles) {
            try {
                urls.add(jar.toURI().toURL());
                loadedJarPaths.add(jar.getAbsolutePath());
            } catch (Exception e) {
                System.err.println("[DriverRegistry] Error converting jar to URL: " + jar + ": " + e.getMessage());
            }
        }

        this.classLoader = new URLClassLoader(urls.toArray(new URL[0]), DriverRegistry.class.getClassLoader());

        for (File jar : jarFiles) {
            loadDriversFromJar(jar);
        }
        System.err.println("[DriverRegistry] Initialized with " + loadedDrivers.size() + " drivers from " + jarFiles.size() + " JAR files.");
    }

    private List<File> findDriverJars() {
        List<File> result = new ArrayList<>();

        // 1. Check custom path from env
        String customPath = System.getenv("DBEAVER_DRIVERS_PATH");
        if (customPath != null && !customPath.trim().isEmpty()) {
            scanDirectory(new File(customPath.trim()), result);
        }

        // 2. Check default DBeaverData drivers folder
        String userHome = System.getProperty("user.home");
        String appData = System.getenv("APPDATA");
        File defaultDriversDir;
        if (appData != null && !appData.isEmpty()) {
            defaultDriversDir = Paths.get(appData, "DBeaverData", "drivers").toFile();
        } else {
            defaultDriversDir = Paths.get(userHome, "AppData", "Roaming", "DBeaverData", "drivers").toFile();
        }

        if (defaultDriversDir.exists() && defaultDriversDir.isDirectory()) {
            scanDirectory(defaultDriversDir, result);
        }

        // 3. Check macOS/Linux DBeaver paths if applicable
        File macDrivers = Paths.get(userHome, "Library", "DBeaverData", "drivers").toFile();
        if (macDrivers.exists() && macDrivers.isDirectory()) {
            scanDirectory(macDrivers, result);
        }
        File linuxDrivers = Paths.get(userHome, ".local", "share", "DBeaverData", "drivers").toFile();
        if (linuxDrivers.exists() && linuxDrivers.isDirectory()) {
            scanDirectory(linuxDrivers, result);
        }

        return result;
    }

    private void scanDirectory(File dir, List<File> result) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectory(f, result);
            } else if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                result.add(f);
            }
        }
    }

    private void loadDriversFromJar(File jar) {
        try (JarFile jarFile = new JarFile(jar)) {
            JarEntry serviceEntry = jarFile.getJarEntry("META-INF/services/java.sql.Driver");
            if (serviceEntry != null) {
                try (InputStream is = jarFile.getInputStream(serviceEntry);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        try {
                            Class<?> clazz = Class.forName(line, true, this.classLoader);
                            if (Driver.class.isAssignableFrom(clazz)) {
                                Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
                                loadedDrivers.add(driver);
                                System.err.println("[DriverRegistry] Registered driver: " + line + " from " + jar.getName());
                            }
                        } catch (Throwable t) {
                            System.err.println("[DriverRegistry] Could not instantiate driver " + line + ": " + t.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[DriverRegistry] Error reading jar " + jar.getName() + ": " + e.getMessage());
        }
    }

    public Connection connect(String url, Properties properties) throws SQLException {
        if (url == null || url.trim().isEmpty()) {
            throw new SQLException("JDBC URL cannot be empty");
        }

        SQLException lastException = null;

        // Try loaded drivers first
        for (Driver driver : loadedDrivers) {
            try {
                if (driver.acceptsURL(url)) {
                    Connection conn = driver.connect(url, properties);
                    if (conn != null) {
                        return conn;
                    }
                }
            } catch (SQLException e) {
                lastException = e;
            } catch (Throwable ignored) {
                // Ignore unexpected driver loading / linkage errors
            }
        }

        // If a matching driver was found but threw a real connection/auth error, report it directly
        if (lastException != null) {
            throw lastException;
        }

        // Fallback to default DriverManager in case driver is on the system classpath
        try {
            return java.sql.DriverManager.getConnection(url, properties);
        } catch (SQLException e) {
            throw new SQLException("No suitable driver found for URL: " + url + " (Registered dynamic drivers: " + loadedDrivers.size() + ")", e);
        }
    }

    public List<String> getLoadedJarPaths() {
        return Collections.unmodifiableList(loadedJarPaths);
    }

    public List<String> getLoadedDriverClassNames() {
        List<String> names = new ArrayList<>();
        for (Driver d : loadedDrivers) {
            names.add(d.getClass().getName());
        }
        return names;
    }
}
