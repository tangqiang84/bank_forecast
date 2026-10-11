package com.bankforecast.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 测试库为共享 MySQL 实例。每个库（按 JDBC URL 区分）在一次 JVM 运行内只在首个
 * Spring 上下文启动时 clean 一次，之后仅 migrate（幂等跳过），既保证跨运行的
 * 空库隔离语义，又避免 @DirtiesContext 重建上下文时清空当前测试类已写入的数据。
 */
@Configuration
public class TestFlywayConfig {

  private static final Set<String> CLEANED_URLS = ConcurrentHashMap.newKeySet();

  @Bean
  public FlywayMigrationStrategy cleanMigrateStrategy() {
    return flyway -> {
      String url = jdbcUrl(flyway.getConfiguration().getDataSource());
      if (CLEANED_URLS.add(url)) {
        flyway.clean();
      }
      flyway.migrate();
    };
  }

  private static String jdbcUrl(javax.sql.DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      return connection.getMetaData().getURL();
    } catch (SQLException ex) {
      throw new IllegalStateException("无法读取测试数据源 URL", ex);
    }
  }
}
