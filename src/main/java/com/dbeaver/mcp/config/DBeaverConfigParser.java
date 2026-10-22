package com.dbeaver.mcp.config;

import com.dbeaver.mcp.model.ConnectionInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DBeaverConfigParser {
    // Default hardcoded key and IV in DBeaver Community Edition
    private static final byte[] DEFAULT_DBEAVER_AES_KEY = hexStringToByteArray("babb4a9f774ab853c96c2d653dfe544a");
    private static final byte[] DEFAULT_DBEAVER_AES_IV = new byte[16];

    private final String workspacePath;
    private final boolean debug;
    private final byte[] aesKey;
    private final byte[] aesIv;
    private final ObjectMapper mapper = new ObjectMapper();

    public DBeaverConfigParser() {
        this(null, false);
    }

    public DBeaverConfigParser(String workspacePath, boolean debug) {
        String envWs = System.getenv("DBEAVER_WORKSPACE");
        if (workspacePath != null && !workspacePath.trim().isEmpty()) {
            this.workspacePath = workspacePath.trim();
        } else if (envWs != null && !envWs.trim().isEmpty()) {
            this.workspacePath = envWs.trim();
        } else {
            this.workspacePath = defaultWorkspacePath();
        }

        String envDebug = System.getenv("DBEAVER_DEBUG");
        this.debug = debug || (envDebug != null && envDebug.equalsIgnoreCase("true"));

        // Support custom AES Key from environment
        String envKey = System.getenv("DBEAVER_AES_KEY");
        if (envKey != null && !envKey.trim().isEmpty()) {
            this.aesKey = parseKeyOrHex(envKey.trim());
        } else {
            this.aesKey = DEFAULT_DBEAVER_AES_KEY;
        }

        // Support custom AES IV from environment
        String envIv = System.getenv("DBEAVER_AES_IV");
        if (envIv != null && !envIv.trim().isEmpty()) {
            this.aesIv = parseKeyOrHex(envIv.trim());
        } else {
            this.aesIv = DEFAULT_DBEAVER_AES_IV;
        }
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public static String defaultWorkspacePath() {
        String userHome = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isEmpty()) {
                return Paths.get(appData, "DBeaverData", "workspace6").toString();
            }
            return Paths.get(userHome, "AppData", "Roaming", "DBeaverData", "workspace6").toString();
        } else if (os.contains("mac")) {
            return Paths.get(userHome, "Library", "DBeaverData", "workspace6").toString();
        } else {
            return Paths.get(userHome, ".local", "share", "DBeaverData", "workspace6").toString();
        }
    }

    public List<ConnectionInfo> parseConnections() {
        File dataSourcesFile = new File(workspacePath, "General/.dbeaver/data-sources.json");
        if (!dataSourcesFile.exists()) {
            dataSourcesFile = new File(workspacePath, "General\\.dbeaver\\data-sources.json");
        }

        if (!dataSourcesFile.isFile()) {
            logDebug("data-sources.json not found at: " + dataSourcesFile.getAbsolutePath());
            return Collections.emptyList();
        }

        List<ConnectionInfo> connections = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(dataSourcesFile);
            JsonNode connsNode = root.get("connections");
            if (connsNode != null && connsNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = connsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String connId = entry.getKey();
                    JsonNode connData = entry.getValue();

                    ConnectionInfo info = new ConnectionInfo();
                    info.setId(connId);
                    info.setName(connData.path("name").asText(connId));
                    info.setDriver(connData.path("driver").asText(""));
                    info.setProvider(connData.path("provider").asText(""));
                    info.setFolder(connData.path("folder").asText(""));
                    info.setDescription(connData.path("description").asText(""));
                    info.setReadonly(connData.path("readonly").asBoolean(false));

                    JsonNode config = connData.path("configuration");
                    if (!config.isMissingNode()) {
                        info.setUrl(config.path("url").asText(""));
                        info.setUser(config.path("user").asText(""));
                        info.setHost(config.path("host").asText(config.path("server").asText("")));
                        if (config.has("port") && !config.get("port").isNull()) {
                            info.setPort(config.get("port").asInt());
                        }
                        info.setDatabase(config.path("database").asText(""));

                        Map<String, Object> props = mapper.convertValue(config, Map.class);
                        info.setProperties(props);
                    }

                    fillFromUrl(info);
                    connections.add(info);
                }
            }

            loadCredentials(connections);
        } catch (Exception e) {
            logDebug("Error parsing connections: " + e.getMessage());
        }

        return connections;
    }

    public ConnectionInfo getConnection(String idOrName) {
        if (idOrName == null || idOrName.trim().isEmpty()) {
            return null;
        }
        for (ConnectionInfo info : parseConnections()) {
            if (idOrName.equalsIgnoreCase(info.getId()) || idOrName.equalsIgnoreCase(info.getName())) {
                return info;
            }
        }
        return null;
    }

    private void loadCredentials(List<ConnectionInfo> connections) {
        File credFile = new File(workspacePath, "General/.dbeaver/credentials-config.json");
        if (!credFile.exists()) {
            credFile = new File(workspacePath, "General\\.dbeaver\\credentials-config.json");
        }
        if (!credFile.isFile()) {
            logDebug("Credentials file not found at: " + credFile.getAbsolutePath());
            return;
        }

        try {
            byte[] encryptedData = Files.readAllBytes(credFile.toPath());
            String decryptedJson = decryptCredentials(encryptedData, this.aesKey, this.aesIv);
            if (decryptedJson == null || decryptedJson.trim().isEmpty()) {
                return;
            }

            JsonNode root = mapper.readTree(decryptedJson);
            for (ConnectionInfo conn : connections) {
                JsonNode credNode = root.get(conn.getId());
                if (credNode != null) {
                    JsonNode inner = credNode.path("#connection");
                    if (!inner.isMissingNode()) {
                        if (inner.has("user") && (conn.getUser() == null || conn.getUser().isEmpty())) {
                            conn.setUser(inner.get("user").asText());
                        }
                        if (inner.has("password")) {
                            conn.setPassword(inner.get("password").asText());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logDebug("Failed to load credentials: " + e.getMessage());
        }
    }

    public static String decryptCredentials(byte[] encryptedData) {
        return decryptCredentials(encryptedData, DEFAULT_DBEAVER_AES_KEY, DEFAULT_DBEAVER_AES_IV);
    }

    public static String decryptCredentials(byte[] encryptedData, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            byte[] decrypted = cipher.doFinal(encryptedData);
            // DBeaver prepends a 16-byte random header to encrypted credentials
            if (decrypted.length > 16) {
                return new String(decrypted, 16, decrypted.length - 16, StandardCharsets.UTF_8);
            }
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[DBeaverConfigParser] Decryption error (check if DBEAVER_AES_KEY matches): " + e.getMessage());
            return null;
        }
    }

    private void fillFromUrl(ConnectionInfo conn) {
        String url = conn.getUrl();
        if (url == null || url.trim().isEmpty()) {
            return;
        }

        try {
            String cleanUrl = url.trim();
            if (cleanUrl.toLowerCase().startsWith("jdbc:")) {
                cleanUrl = cleanUrl.substring(5);
            }
            if (cleanUrl.toLowerCase().startsWith("jtds:")) {
                cleanUrl = cleanUrl.substring(5);
            }

            Pattern pattern = Pattern.compile("^[a-zA-Z0-9_+]+://([^/;]+)(.*)$");
            Matcher matcher = pattern.matcher(cleanUrl);
            if (matcher.find()) {
                String authority = matcher.group(1);
                String remainder = matcher.group(2) != null ? matcher.group(2) : "";

                String hostPart = authority;
                Integer port = null;
                if (hostPart.contains("\\")) {
                    String[] parts = hostPart.split("\\\\", 2);
                    hostPart = parts[0];
                    if (parts[1].contains(":")) {
                        String[] instPort = parts[1].split(":", 2);
                        port = tryParseInt(instPort[1]);
                    }
                }
                if (hostPart.contains(":")) {
                    String[] hp = hostPart.split(":", 2);
                    hostPart = hp[0];
                    if (port == null) {
                        port = tryParseInt(hp[1]);
                    }
                }

                if ((conn.getHost() == null || conn.getHost().isEmpty()) && !hostPart.isEmpty()) {
                    conn.setHost(hostPart);
                }
                if (conn.getPort() == null && port != null) {
                    conn.setPort(port);
                }

                Pattern dbPattern = Pattern.compile("^/([^/;?]+)");
                Matcher dbMatcher = dbPattern.matcher(remainder);
                if (dbMatcher.find()) {
                    if (conn.getDatabase() == null || conn.getDatabase().isEmpty()) {
                        conn.setDatabase(dbMatcher.group(1));
                    }
                }
                for (String key : new String[]{"databaseName", "database", "DatabaseName"}) {
                    Pattern paramPattern = Pattern.compile("[;?&]" + key + "=([^;?&]+)", Pattern.CASE_INSENSITIVE);
                    Matcher paramMatcher = paramPattern.matcher(remainder);
                    if (paramMatcher.find()) {
                        if (conn.getDatabase() == null || conn.getDatabase().isEmpty()) {
                            conn.setDatabase(paramMatcher.group(1));
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Integer tryParseInt(String val) {
        try {
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] parseKeyOrHex(String keyStr) {
        if (keyStr.matches("^[0-9a-fA-F]+$") && keyStr.length() % 2 == 0) {
            return hexStringToByteArray(keyStr);
        }
        // Fallback: UTF-8 bytes padded/truncated to 16 bytes
        byte[] bytes = keyStr.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(bytes, 16);
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private void logDebug(String msg) {
        if (debug) {
            System.err.println("[DBeaverConfigParser] " + msg);
        }
    }
}
