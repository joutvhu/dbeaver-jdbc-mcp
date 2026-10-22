package com.dbeaver.mcp.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class QueryValidatorTest {

    @Test
    public void testReadOnlyAllowed() {
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("SELECT * FROM users"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("select id, name from table_a where id = 1"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("EXPLAIN SELECT 1"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("SHOW TABLES"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("DESCRIBE my_table"));
        assertDoesNotThrow(() -> QueryValidator.validateReadOnlyQuery("WITH cte AS (SELECT 1) SELECT * FROM cte"));
    }

    @Test
    public void testReadOnlyBlocked() {
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("DROP TABLE users"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("TRUNCATE TABLE users"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("INSERT INTO users VALUES (1)"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("UPDATE users SET name = 'a'"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateReadOnlyQuery("DELETE FROM users"));
    }

    @Test
    public void testDangerousWriteBlocked() {
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("UPDATE users SET name = 'a'")); // No where
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("DELETE FROM users")); // No where
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("DROP DATABASE prod"));
        assertThrows(IllegalArgumentException.class, () -> QueryValidator.validateWriteQuery("ALTER TABLE users DROP COLUMN name"));
    }
}
