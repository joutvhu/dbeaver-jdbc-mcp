package com.joutvhu.dbeaver.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConnectionInfo {
    private String id;
    private String name;
    private String driver;
    private String provider;
    private String url;
    private String host;
    private Integer port;
    private String database;
    private String user;
    private String password;
    private boolean readonly;
    private String folder;
    private String description;
    private Map<String, Object> properties = new HashMap<>();

    public ConnectionInfo() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isReadonly() {
        return readonly;
    }

    public void setReadonly(boolean readonly) {
        this.readonly = readonly;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties != null ? properties : new HashMap<>();
    }

    public ConnectionInfo redacted() {
        ConnectionInfo copy = new ConnectionInfo();
        copy.id = this.id;
        copy.name = this.name;
        copy.driver = this.driver;
        copy.provider = this.provider;
        copy.url = sanitizeUrl(this.url);
        copy.host = this.host;
        copy.port = this.port;
        copy.database = this.database;
        copy.user = this.user;
        copy.password = null; // NEVER expose password
        copy.readonly = this.readonly;
        copy.folder = this.folder;
        copy.description = this.description;
        copy.properties = redactMap(this.properties);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redactMap(Map<String, Object> map) {
        if (map == null) return new HashMap<>();
        Map<String, Object> redacted = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String keyLower = key != null ? key.toLowerCase() : "";
            if (keyLower.contains("password") || keyLower.contains("secret") ||
                keyLower.contains("token") || keyLower.contains("key") ||
                keyLower.contains("pwd")) {
                redacted.put(key, "***REDACTED***");
            } else if (value instanceof Map) {
                redacted.put(key, redactMap((Map<String, Object>) value));
            } else {
                redacted.put(key, value);
            }
        }
        return redacted;
    }

    public static String sanitizeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }

        String sanitized = url;
        int schemeEnd = sanitized.indexOf("://");
        if (schemeEnd != -1) {
            int authStart = schemeEnd + 3;
            int pathStart = sanitized.indexOf('/', authStart);
            int queryStart = sanitized.indexOf('?', authStart);
            int semiStart = sanitized.indexOf(';', authStart);
            int authEnd = sanitized.length();
            if (pathStart != -1 && pathStart < authEnd) authEnd = pathStart;
            if (queryStart != -1 && queryStart < authEnd) authEnd = queryStart;
            if (semiStart != -1 && semiStart < authEnd) authEnd = semiStart;

            String authority = sanitized.substring(authStart, authEnd);
            int lastAt = authority.lastIndexOf('@');
            if (lastAt != -1) {
                int firstColon = authority.indexOf(':');
                if (firstColon != -1 && firstColon < lastAt) {
                    String user = authority.substring(0, firstColon);
                    String hostAndPort = authority.substring(lastAt);
                    sanitized = sanitized.substring(0, authStart) + user + ":***REDACTED***" + hostAndPort + sanitized.substring(authEnd);
                }
            }
        }

        // Mask parameter-based credentials, e.g. ;password=xxx or ?password=xxx
        sanitized = sanitized.replaceAll("(?i)([?&;](?:password|pwd|secret|token)=)[^&;]+", "$1***REDACTED***");

        // Mask Oracle thin URL credentials, e.g. jdbc:oracle:thin:user/password@host:port:sid
        sanitized = sanitized.replaceAll("(?i)(thin:[^/@]+)/[^@]+(@)", "$1/***REDACTED***$2");

        return sanitized;
    }
}
