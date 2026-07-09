package com.example.commander.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.quartz.autoconfigure.QuartzDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dedicated connection pool for Quartz's JDBC job store.
 *
 * <p>Kept separate from the application's primary DataSource (spring.datasource.*) so
 * Quartz's clustered lock-acquisition/check-in polling can never starve, or be starved
 * by, connections used for application-level work. Spring Boot's Quartz autoconfiguration
 * picks up whichever DataSource bean is qualified with {@link QuartzDataSource} in place
 * of the primary one.
 */
@Configuration
public class QuartzDataSourceConfig {

    @Bean
    @ConfigurationProperties("commander.quartz.datasource")
    public DataSourceProperties quartzDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @QuartzDataSource
    @ConfigurationProperties("commander.quartz.datasource.hikari")
    public DataSource quartzDataSource(DataSourceProperties quartzDataSourceProperties) {
        return quartzDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
