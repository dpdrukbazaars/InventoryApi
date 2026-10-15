
package com.api.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.MySqlJdbcIndexedSessionRepositoryCustomizer;

@Configuration
public class JdbcSessionConfig {

    @Bean
    public MySqlJdbcIndexedSessionRepositoryCustomizer
    mysqlSessionCustomizer() {

        return new MySqlJdbcIndexedSessionRepositoryCustomizer();
    }
}
