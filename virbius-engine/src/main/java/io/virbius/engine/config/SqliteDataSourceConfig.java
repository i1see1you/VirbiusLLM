package io.virbius.engine.config;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

@Configuration
public class SqliteDataSourceConfig {

    @Bean
    public DataSource dataSource(@Value("${spring.datasource.url}") String url) throws Exception {
        ensureSqliteParentDir(url);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(url);
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    static void ensureSqliteParentDir(String url) throws Exception {
        if (!url.startsWith("jdbc:sqlite:")) {
            return;
        }
        String path = url.substring("jdbc:sqlite:".length());
        if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }
        if (path.isBlank() || ":memory:".equals(path)) {
            return;
        }
        Path file = Path.of(path).toAbsolutePath();
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
    }
}
