package com.dbeaver.mcp.security;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class QueryValidator {
    // Dangerous DDL / Admin patterns
    private static final List<Pattern> DANGEROUS_PATTERNS = Arrays.asList(
            Pattern.compile("\\bdrop\\s+(?:database|schema|table|index|view|user|role|function|procedure|trigger|sequence|type|extension|materialized\\s+view|event|server|synonym)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btruncate\\s+(?:table\\s+)?\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\balter\\s+\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgrant\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brevoke\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcreate\\s+(?:table|index|view|user|role|database|schema|function|procedure|trigger|sequence|type|extension|materialized\\s+view)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bshutdown\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\binto\\s+(?:outfile|dumpfile)\\b", Pattern.CASE_INSENSITIVE)
    );

    // Read-only prefixes (after stripping comments and optional opening parentheses)
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

    // Block write/DDL keywords inside read-only queries (e.g. inside CTE WITH ... or EXPLAIN ANALYZE)
    private static final Pattern WRITE_KEYWORDS_IN_READ_ONLY = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|exec|execute|call)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // SELECT ... INTO ... table creation
    private static final Pattern SELECT_INTO_PATTERN = Pattern.compile(
            "^select\\b[\\s\\S]*?\\binto\\s+(?:temporary|temp|table)?\\s*[^;\\s]+",
            Pattern.CASE_INSENSITIVE
    );

    private static final List<Pattern> WRITE_ALLOWED_PREFIXES = Arrays.asList(
            Pattern.compile("^insert\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^update\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^delete\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^merge\\b", Pattern.CASE_INSENSITIVE)
    );

    private static final Pattern WHERE_CLAUSE_PATTERN = Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE);

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

        // Clean query: strip comments and string/dollar literals for safe syntactic analysis
        String cleanSql = stripCommentsAndLiterals(sql).trim();
        if (cleanSql.isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty after stripping comments");
        }

        // Prevent multiple queries stacked with semicolons
        checkMultipleStatements(cleanSql);

        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(cleanSql).find()) {
                throw new IllegalArgumentException("Potentially dangerous query detected. Query blocked for safety.");
            }
        }

        // Normalize leading parentheses: e.g. (SELECT ...) UNION (SELECT ...)
        String prefixChecked = cleanSql;
        while (prefixChecked.startsWith("(")) {
            prefixChecked = prefixChecked.substring(1).trim();
        }

        boolean matched = false;
        for (Pattern p : READ_ONLY_PREFIXES) {
            if (p.matcher(prefixChecked).find()) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new IllegalArgumentException("Only read-only queries (SELECT, WITH, EXPLAIN, SHOW, DESCRIBE, PRAGMA) are allowed. Use 'execute' for data modifications.");
        }

        // Prevent data modification inside CTE (e.g. WITH d AS (DELETE FROM ...) SELECT ...)
        if (prefixChecked.toLowerCase().startsWith("with")) {
            if (WRITE_KEYWORDS_IN_READ_ONLY.matcher(cleanSql).find()) {
                throw new IllegalArgumentException("Data modification or DDL statements inside CTE (WITH ...) are not allowed in read-only queries.");
            }
        }

        // Prevent execution via EXPLAIN ANALYZE
        if (prefixChecked.toLowerCase().startsWith("explain")) {
            if (Pattern.compile("\\banalyze\\b", Pattern.CASE_INSENSITIVE).matcher(cleanSql).find()
                    && WRITE_KEYWORDS_IN_READ_ONLY.matcher(cleanSql).find()) {
                throw new IllegalArgumentException("EXPLAIN ANALYZE with data modification is blocked in read-only queries.");
            }
        }

        // Prevent SELECT INTO table creation
        if (SELECT_INTO_PATTERN.matcher(prefixChecked).find()) {
            throw new IllegalArgumentException("SELECT INTO table creation is not allowed in read-only mode.");
        }
    }

    public static void validateWriteQuery(String sql) throws IllegalArgumentException {
        if (isReadOnlyMode()) {
            throw new IllegalArgumentException("Write operations are disabled. The server is running in read-only mode (READ_ONLY=true). Set READ_ONLY=false to enable write operations.");
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }

        String cleanSql = stripCommentsAndLiterals(sql).trim();
        if (cleanSql.isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty after stripping comments");
        }

        // Prevent multiple statements in execute
        checkMultipleStatements(cleanSql);

        boolean allowedPrefix = false;
        for (Pattern p : WRITE_ALLOWED_PREFIXES) {
            if (p.matcher(cleanSql).find()) {
                allowedPrefix = true;
                break;
            }
        }
        if (!allowedPrefix) {
            throw new IllegalArgumentException("Only data modification queries (INSERT, UPDATE, DELETE, MERGE) are allowed. Use 'query' for read operations.");
        }

        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(cleanSql).find()) {
                throw new IllegalArgumentException("DDL and administrative commands are not allowed. Only INSERT, UPDATE, DELETE (with WHERE), and MERGE are permitted.");
            }
        }

        // Enforce WHERE clause for DELETE and UPDATE against cleanSql (so 'where' in string literals or comments doesn't bypass)
        String lower = cleanSql.toLowerCase();
        if (lower.startsWith("delete") && !WHERE_CLAUSE_PATTERN.matcher(cleanSql).find()) {
            throw new IllegalArgumentException("DELETE without a WHERE clause is not allowed. Please add a WHERE clause to target specific rows.");
        }

        if (lower.startsWith("update") && !WHERE_CLAUSE_PATTERN.matcher(cleanSql).find()) {
            throw new IllegalArgumentException("UPDATE without a WHERE clause is not allowed. Please add a WHERE clause to target specific rows.");
        }
    }

    public static String stripCommentsAndLiterals(String sql) {
        if (sql == null) return "";
        StringBuilder sb = new StringBuilder(sql.length());
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            char next = (i + 1 < len) ? sql.charAt(i + 1) : '\0';

            // Single-line comment --
            if (c == '-' && next == '-') {
                i += 2;
                while (i < len && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                sb.append(' ');
                continue;
            }

            // Multi-line comment /* ... */
            if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2; // skip */
                sb.append(' ');
                continue;
            }

            // Single-quoted string literal '...'
            if (c == '\'') {
                sb.append("''");
                i++;
                while (i < len) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            // Escaped quote ''
                            i += 2;
                            continue;
                        } else {
                            // End of string
                            i++;
                            break;
                        }
                    } else if (sql.charAt(i) == '\\' && i + 1 < len) {
                        // Escaped char \' or \\
                        i += 2;
                    } else {
                        i++;
                    }
                }
                continue;
            }

            // Double-quoted identifier or string "..."
            if (c == '"') {
                sb.append("\"\"");
                i++;
                while (i < len) {
                    if (sql.charAt(i) == '"') {
                        if (i + 1 < len && sql.charAt(i + 1) == '"') {
                            i += 2;
                            continue;
                        } else {
                            i++;
                            break;
                        }
                    } else if (sql.charAt(i) == '\\' && i + 1 < len) {
                        i += 2;
                    } else {
                        i++;
                    }
                }
                continue;
            }

            // Backtick identifier `...`
            if (c == '`') {
                sb.append("``");
                i++;
                while (i < len) {
                    if (sql.charAt(i) == '`') {
                        if (i + 1 < len && sql.charAt(i + 1) == '`') {
                            i += 2;
                            continue;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                continue;
            }

            // Square bracket identifier [...] (e.g. SQL Server / MS Access)
            if (c == '[') {
                int endBracket = sql.indexOf(']', i + 1);
                if (endBracket != -1) {
                    sb.append("[]");
                    i = endBracket + 1;
                    continue;
                }
            }

            // PostgreSQL Dollar-quoted string $$...$$ or $tag$...$tag$
            if (c == '$' && isDollarQuoteStart(sql, i)) {
                String tag = extractDollarTag(sql, i);
                i += tag.length();
                int endTag = sql.indexOf(tag, i);
                if (endTag != -1) {
                    i = endTag + tag.length();
                } else {
                    i = len;
                }
                sb.append("''");
                continue;
            }

            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean isDollarQuoteStart(String sql, int index) {
        if (index >= sql.length() || sql.charAt(index) != '$') return false;
        int nextDollar = sql.indexOf('$', index + 1);
        if (nextDollar == -1 || nextDollar - index > 32) return false;
        for (int i = index + 1; i < nextDollar; i++) {
            char c = sql.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static String extractDollarTag(String sql, int index) {
        int nextDollar = sql.indexOf('$', index + 1);
        return sql.substring(index, nextDollar + 1);
    }

    private static void checkMultipleStatements(String cleanSql) {
        // Strip trailing semicolon and trailing whitespace
        String s = cleanSql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }

        // In cleanSql, comments and strings have already been stripped!
        // So any remaining semicolon is an unquoted statement delimiter.
        if (s.indexOf(';') != -1) {
            throw new IllegalArgumentException("Multiple SQL statements in a single execution are blocked for security.");
        }
    }
}
