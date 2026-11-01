package com.joutvhu.dbeaver.mcp.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class QueryValidatorTest {

    @Test
    public void testReadOnlyAllowed() {
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("SELECT * FROM users"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("-- comment\nSELECT id, name FROM table_a WHERE id = 1"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("/* multi-line comment */ SELECT 1"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("EXPLAIN SELECT 1"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("SHOW TABLES"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("DESCRIBE my_table"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("WITH cte AS (SELECT 1) SELECT * FROM cte"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("(SELECT 1) UNION (SELECT 2)"));
        // String literal containing semicolon
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("SELECT * FROM users WHERE name = 'John; Doe'"));
        // Escaped quote in string literal containing semicolon
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("SELECT * FROM users WHERE name = 'O''Reilly; test'"));
        // Comment containing semicolon
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("SELECT 1 /* ; inside comment ; */ FROM dual; -- trailing;"));
    }

    @Test
    public void testReadOnlyBlocked() {
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("DROP TABLE users"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("TRUNCATE TABLE users"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("INSERT INTO users VALUES (1)"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("UPDATE users SET name = 'a' WHERE id = 1"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("DELETE FROM users WHERE id = 1"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("SELECT * FROM users; DROP TABLE users;"));

        // CTE with data modification
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("WITH deleted AS (DELETE FROM users RETURNING *) SELECT * FROM deleted"));

        // Nested CTE in subquery with data modification
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("SELECT (WITH deleted AS (DELETE FROM users RETURNING 1) SELECT count(*) FROM deleted)"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("SELECT * FROM (WITH deleted AS (DELETE FROM users RETURNING *) SELECT * FROM deleted) a"));

        // Multi-statement without semicolon (T-SQL style)
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("SELECT 1 DELETE FROM users"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("SELECT 1 INSERT INTO users VALUES (1)"));

        // EXPLAIN ANALYZE with DELETE
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("EXPLAIN ANALYZE DELETE FROM users"));

        // SELECT INTO
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("SELECT * INTO new_table FROM old_table"));
    }

    @Test
    public void testStripCommentsAndLiterals() {
        assertEquals("", QueryValidator.stripCommentsAndLiterals(null));
        assertEquals("", QueryValidator.stripCommentsAndLiterals(""));
        assertEquals("   ", QueryValidator.stripCommentsAndLiterals("   "));

        // Single-line comment
        assertEquals("SELECT 1  \n", QueryValidator.stripCommentsAndLiterals("SELECT 1 -- this is a comment\n"));
        assertEquals("SELECT 1  ", QueryValidator.stripCommentsAndLiterals("SELECT 1 -- unclosed single line"));

        // Multi-line comment
        assertEquals("SELECT 1   FROM dual", QueryValidator.stripCommentsAndLiterals("SELECT 1 /* comment */ FROM dual"));
        assertEquals("SELECT 1  ", QueryValidator.stripCommentsAndLiterals("SELECT 1 /* unclosed multi-line comment"));

        // Single-quoted string literals with escapes
        assertEquals("SELECT ''", QueryValidator.stripCommentsAndLiterals("SELECT 'hello world'"));
        assertEquals("SELECT ''", QueryValidator.stripCommentsAndLiterals("SELECT 'O''Reilly'"));
        assertEquals("SELECT ''", QueryValidator.stripCommentsAndLiterals("SELECT 'escaped \\' quote'"));
        assertEquals("SELECT ''", QueryValidator.stripCommentsAndLiterals("SELECT 'unclosed quote"));

        // Double-quoted identifiers
        assertEquals("SELECT \"\" FROM \"\"", QueryValidator.stripCommentsAndLiterals("SELECT \"col_name\" FROM \"my_table\""));
        assertEquals("SELECT \"\"", QueryValidator.stripCommentsAndLiterals("SELECT \"escaped \\\" quote\""));
        assertEquals("SELECT \"\"", QueryValidator.stripCommentsAndLiterals("SELECT \"unclosed quote"));

        // Backtick identifiers
        assertEquals("SELECT `` FROM ``", QueryValidator.stripCommentsAndLiterals("SELECT `col` FROM `tbl`"));
        assertEquals("SELECT ``", QueryValidator.stripCommentsAndLiterals("SELECT `unclosed backtick"));

        // Square bracket identifiers
        assertEquals("SELECT [] FROM []", QueryValidator.stripCommentsAndLiterals("SELECT [col name] FROM [my table]"));
        assertEquals("SELECT [unclosed bracket", QueryValidator.stripCommentsAndLiterals("SELECT [unclosed bracket"));

        // PostgreSQL Dollar quotes
        assertEquals("SELECT ''", QueryValidator.stripCommentsAndLiterals("SELECT $$string content$$"));
        assertEquals("SELECT ''", QueryValidator.stripCommentsAndLiterals("SELECT $tag$body with $$ nested$tag$"));
        assertEquals("SELECT ''", QueryValidator.stripCommentsAndLiterals("SELECT $$unclosed dollar"));
        // Parameter variables like $1, $2 should not be treated as dollar quote tags
        assertEquals("SELECT $1, $2", QueryValidator.stripCommentsAndLiterals("SELECT $1, $2"));
    }

    @Test
    public void testReadOnlyEmptyAndNull() {
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery(null));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery(""));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("   "));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("-- only comment\n/* another */"));
    }

    @Test
    public void testReadOnlySupportedPrefixes() {
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("DESC my_table"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("PRAGMA table_info(users)"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("SET search_path TO public"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("(((SELECT 1)))"));
    }

    @Test
    public void testDangerousPatternsBlocked() {
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("CREATE OR REPLACE VIEW my_view AS SELECT 1"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("CREATE OR REPLACE PROCEDURE sp_foo() BEGIN END"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("SHUTDOWN"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("SELECT * FROM tbl INTO OUTFILE '/tmp/test.csv'"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("GRANT ALL PRIVILEGES ON db.* TO 'user'@'%'"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("REVOKE ALL PRIVILEGES ON db.* FROM 'user'@'%'"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("SELECT * FROM users; SELECT * FROM orders;"));
    }

    @Test
    public void testIsReadOnlyModeDefault() {
        // Without DBEAVER_READ_ONLY / READ_ONLY set to false, default is true
        assertTrue(QueryValidator.isReadOnlyMode());
    }

    @org.junit.jupiter.api.AfterEach
    public void cleanup() {
        QueryValidator.setReadOnlyModeOverride(null);
    }

    @Test
    public void testDangerousWriteBlocked() {
        // 'where' in string value should NOT satisfy WHERE requirement
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("UPDATE users SET name = 'where'"));
        // 'where' in identifier should NOT satisfy WHERE requirement
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("UPDATE users SET \"where\" = 1"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("UPDATE users SET [where] = 1"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("UPDATE users SET `where` = 1"));
        // 'where' in comment should NOT satisfy WHERE requirement
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("DELETE FROM users /* where */"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("DROP DATABASE prod"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("DROP PROCEDURE sp_test"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("ALTER TABLE users DROP COLUMN name"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("ALTER SEQUENCE seq_id RESTART"));
    }

    @Test
    public void testWriteQueryAllowedWhenWriteModeEnabled() {
        QueryValidator.setReadOnlyModeOverride(false);
        assertFalse(QueryValidator.isReadOnlyMode());

        assertDoesNotThrow(() -> QueryValidator.validateWriteQuery("INSERT INTO users (id, name) VALUES (1, 'Alice')"));
        assertDoesNotThrow(() -> QueryValidator.validateWriteQuery("UPDATE users SET name = 'Alice' WHERE id = 1"));
        assertDoesNotThrow(() -> QueryValidator.validateWriteQuery("DELETE FROM users WHERE id = 1"));
        assertDoesNotThrow(() -> QueryValidator.validateWriteQuery("MERGE INTO target t USING source s ON t.id = s.id WHEN MATCHED THEN UPDATE SET t.val = s.val"));

        // Still blocked: empty or whitespace
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery(null));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery(""));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("-- comment only"));

        // Blocked: DELETE/UPDATE without WHERE
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("DELETE FROM users"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("UPDATE users SET name = 'a'"));

        // Blocked: non-write statement in execute
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("SELECT * FROM users"));

        // Blocked: DDL in execute
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("DROP TABLE users"));

        // Blocked: multiple statements in execute
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("INSERT INTO users VALUES (1); INSERT INTO users VALUES (2);"));
    }
}
