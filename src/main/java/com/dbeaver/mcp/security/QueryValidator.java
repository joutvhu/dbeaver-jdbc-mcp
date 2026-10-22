package com.dbeaver.mcp.security;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class QueryValidator {
    private static final List<Pattern> DANGEROUS_PATTERNS = Arrays.asList(
            Pattern.compile("drop\\s+database", Pattern.CASE_INSENSITIVE),
            Pattern.compile("drop\\s+schema", Pattern.CASE_INSENSITIVE),
            Pattern.compile("drop\\s+table", Pattern.CASE_INSENSITIVE),
            Pattern.compile("drop\\s+index", Pattern.CASE_INSENSITIVE),
            Pattern.compile("drop\\s+view", Pattern.CASE_INSENSITIVE),
            Pattern.compile("truncate\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("grant\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("revoke\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("create\\s+user", Pattern.CASE_INSENSITIVE),
            Pattern.compile("drop\\s+user", Pattern.CASE_INSENSITIVE),
            Pattern.compile("alter\\s+user", Pattern.CASE_INSENSITIVE),
            Pattern.compile("shutdown", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> READ_ONLY_PREFIXES = Arrays.asList(
            Pattern.compile("^select\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^select$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^with\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^explain\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^show\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^describe\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^desc\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^pragma\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^set\\s+search_path", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> WRITE_ALLOWED_PREFIXES = Arrays.asList(
            Pattern.compile("^insert\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^update\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^delete\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^merge\\s", Pattern.CASE_INSENSITIVE)
    );

    private static final Pattern DELETE_NO_WHERE = Pattern.compile("^delete\\s+from\\s+\\S+\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_NO_WHERE = Pattern.compile("^update\\s+\\S+\\s+set\\s+(?:(?!where).)*\\s*;?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

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
        String trimmed = sql.trim();
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(trimmed).find()) {
                throw new IllegalArgumentException("Potentially dangerous query detected. Query blocked for safety.");
            }
        }

        boolean matched = false;
        for (Pattern p : READ_ONLY_PREFIXES) {
            if (p.matcher(trimmed).find()) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new IllegalArgumentException("Only read-only queries (SELECT, EXPLAIN, SHOW, DESCRIBE, PRAGMA) are allowed. Use 'execute' for data modifications.");
        }
    }

    public static void validateWriteQuery(String sql) throws IllegalArgumentException {
        if (isReadOnlyMode()) {
            throw new IllegalArgumentException("Write operations are disabled. The server is running in read-only mode (READ_ONLY=true). Set READ_ONLY=false to enable write operations.");
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }
        String trimmed = sql.trim();

        boolean allowedPrefix = false;
        for (Pattern p : WRITE_ALLOWED_PREFIXES) {
            if (p.matcher(trimmed).find()) {
                allowedPrefix = true;
                break;
            }
        }
        if (!allowedPrefix) {
            throw new IllegalArgumentException("Only data modification queries (INSERT, UPDATE, DELETE, MERGE) are allowed. Use 'query' for read operations.");
        }

        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(trimmed).find()) {
                throw new IllegalArgumentException("DDL and administrative commands are not allowed. Only INSERT, UPDATE, DELETE (with WHERE), and MERGE are permitted.");
            }
        }

        if (DELETE_NO_WHERE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("DELETE without a WHERE clause is not allowed. Please add a WHERE clause to target specific rows.");
        }

        if (UPDATE_NO_WHERE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("UPDATE without a WHERE clause is not allowed. Please add a WHERE clause to target specific rows.");
        }
    }
}
