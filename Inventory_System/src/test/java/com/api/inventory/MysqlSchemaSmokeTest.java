
package com.api.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-integration")
@EnabledIfEnvironmentVariable(
        named = "INVENTORY_TEST_DB_PASSWORD",
        matches = ".+"
)
public class MysqlSchemaSmokeTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayCreatesMySqlSessionTables() {

        String database = jdbc.queryForObject(
                "SELECT DATABASE()", String.class
        );

        assertEquals("inventory_session_test", database);

        Integer migrations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class
        );

        assertNotNull(migrations);
        assertTrue(migrations >= 17);

        Integer sessions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spring_session",
                Integer.class
        );

        Integer attributes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spring_session_attributes",
                Integer.class
        );

        assertNotNull(sessions);
        assertNotNull(attributes);
    }
}
