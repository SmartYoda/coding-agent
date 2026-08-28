package com.yoda.codingagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class PersistenceStackTest {

    @Test
    void flywayAndMyBatisUseTheSameSqliteDatabase(@TempDir Path tempDirectory) throws Exception {
        Path databasePath = tempDirectory.resolve("agent-test.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databasePath);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .failOnMissingLocations(false)
                .load()
                .migrate();

        Environment environment =
                new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        SqlSessionFactory sessionFactory =
                new SqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = sessionFactory.openSession();
                PreparedStatement statement = session.getConnection().prepareStatement(
                        "SELECT COUNT(*) FROM sqlite_master "
                                + "WHERE type = 'table' AND name = 'flyway_schema_history'");
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }
}
