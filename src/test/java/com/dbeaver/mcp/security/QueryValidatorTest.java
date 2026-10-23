package com.dbeaver.mcp.security;

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

        // EXPLAIN ANALYZE with DELETE
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("EXPLAIN ANALYZE DELETE FROM users"));

        // SELECT INTO
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("SELECT * INTO new_table FROM old_table"));
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
}
