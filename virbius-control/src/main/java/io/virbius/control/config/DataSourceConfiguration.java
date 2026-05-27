package io.virbius.control.config;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 使用 Spring Boot 标准 {@link DataSourceProperties} 构建数据源，不绑定 SQLite 驱动类。
 * 在连接前仅为 SQLite 文件 URL 创建父目录。
 */
@Configuration
public class DataSourceConfiguration {

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        JdbcDatabaseBootstrap.ensureSqliteParentDir(properties.determineUrl());
        return properties.initializeDataSourceBuilder().build();
    }
}
