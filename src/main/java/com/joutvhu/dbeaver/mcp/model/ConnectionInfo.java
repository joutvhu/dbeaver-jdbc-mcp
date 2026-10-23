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
        copy.url = this.url;
        copy.host = this.host;
        copy.port = this.port;
        copy.database = this.database;
        copy.user = this.user;
        copy.password = null; // NEVER expose password
        copy.readonly = this.readonly;
        copy.folder = this.folder;
        copy.description = this.description;

        Map<String, Object> redactedProps = new HashMap<>();
        for (Map.Entry<String, Object> entry : this.properties.entrySet()) {
            String keyLower = entry.getKey().toLowerCase();
            if (keyLower.contains("password") || keyLower.contains("secret") ||
                keyLower.contains("token") || keyLower.contains("key")) {
                redactedProps.put(entry.getKey(), "***REDACTED***");
            } else {
                redactedProps.put(entry.getKey(), entry.getValue());
            }
        }
        copy.properties = redactedProps;
        return copy;
    }
}
