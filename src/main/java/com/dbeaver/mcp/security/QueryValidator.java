package com.dbeaver.mcp.security;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class QueryValidator {
    // Dangerous patterns: match keyword boundary to avoid false positives inside comments or values
    private static final List<Pattern> DANGEROUS_PATTERNS = Arrays.asList(
            Pattern.compile("\\bdrop\\s+database\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdrop\\s+schema\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdrop\\s+table\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdrop\\s+index\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdrop\\s+view\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btruncate\\s+(?:table\\s+)?\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgrant\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brevoke\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcreate\\s+user\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdrop\\s+user\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\balter\\s+user\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bshutdown\\b", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> READ_ONLY_PREFIXES = Arrays.asList(
            Pattern.compile("^select\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^with\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^explain\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^show\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^describe\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^desc\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^pragma\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^set\\s+search_path\\b", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> WRITE_ALLOWED_PREFIXES = Arrays.asList(
            Pattern.compile("^insert\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^update\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^delete\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^merge\\b", Pattern.CASE_INSENSITIVE)
    );

    public static boolean isReadOnlyMode() {
        String ro = System.getenv("READ_ONLY");
        if (ro == null || ro.trim().isEmpty()) {
            return true; // Default is read-only
        }
        return !ro.trim().equalsIgnoreCase("false");
    }

    public static void validateReadOnlyQuery(String sql) throws IllegalArgumentException {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }

        String cleaned = stripLeadingComments(sql.trim());
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty after stripping comments");
        }

        // Prevent multiple queries stacked with semicolons (SQL injection / smuggling)
        checkMultipleStatements(cleaned);

        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(cleaned).find()) {
                throw new IllegalArgumentException("Potentially dangerous query detected. Query blocked for safety.");
            }
        }

        boolean matched = false;
        for (Pattern p : READ_ONLY_PREFIXES) {
            if (p.matcher(cleaned).find()) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new IllegalArgumentException("Only read-only queries (SELECT, WITH, EXPLAIN, SHOW, DESCRIBE, PRAGMA) are allowed. Use 'execute' for data modifications.");
        }
    }

    public static void validateWriteQuery(String sql) throws IllegalArgumentException {
        if (isReadOnlyMode()) {
            throw new IllegalArgumentException("Write operations are disabled. The server is running in read-only mode (READ_ONLY=true). Set READ_ONLY=false to enable write operations.");
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }

        String cleaned = stripLeadingComments(sql.trim());
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty after stripping comments");
        }

        // Prevent multiple statements in execute
        checkMultipleStatements(cleaned);

        boolean allowedPrefix = false;
        for (Pattern p : WRITE_ALLOWED_PREFIXES) {
            if (p.matcher(cleaned).find()) {
                allowedPrefix = true;
                break;
            }
        }
        if (!allowedPrefix) {
            throw new IllegalArgumentException("Only data modification queries (INSERT, UPDATE, DELETE, MERGE) are allowed. Use 'query' for read operations.");
        }

        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(cleaned).find()) {
                throw new IllegalArgumentException("DDL and administrative commands are not allowed. Only INSERT, UPDATE, DELETE (with WHERE), and MERGE are permitted.");
            }
        }

        // Enforce WHERE clause for DELETE and UPDATE
        String lower = cleaned.toLowerCase();
        if (lower.startsWith("delete") && !Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE).matcher(cleaned).find()) {
            throw new IllegalArgumentException("DELETE without a WHERE clause is not allowed. Please add a WHERE clause to target specific rows.");
        }

        if (lower.startsWith("update") && !Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE).matcher(cleaned).find()) {
            throw new IllegalArgumentException("UPDATE without a WHERE clause is not allowed. Please add a WHERE clause to target specific rows.");
        }
    }

    private static String stripLeadingComments(String sql) {
        String s = sql.trim();
        while (true) {
            if (s.startsWith("--")) {
                int nextLine = s.indexOf('\n');
                if (nextLine == -1) return "";
                s = s.substring(nextLine + 1).trim();
            } else if (s.startsWith("/*")) {
                int endComment = s.indexOf("*/");
                if (endComment == -1) return "";
                s = s.substring(endComment + 2).trim();
            } else {
                break;
            }
        }
        return s;
    }

    private static void checkMultipleStatements(String sql) {
        // Strip trailing semicolon
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }

        // Check if there is an unquoted semicolon indicating stacked queries
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (c == ';' && !inSingleQuotes && !inDoubleQuotes) {
                throw new IllegalArgumentException("Multiple SQL statements in a single execution are blocked for security.");
            }
        }
    }
}
