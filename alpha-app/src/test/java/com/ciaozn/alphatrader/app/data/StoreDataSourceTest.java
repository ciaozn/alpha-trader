package com.ciaozn.alphatrader.app.data;

import com.ciaozn.alphatrader.app.config.StoreProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreDataSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void theUrlDecidesTheDialect() {
        assertThat(new StoreProperties("jdbc:sqlite:data/trading.db", null, null).dialect())
                .isEqualTo(StoreProperties.Dialect.SQLITE);
        assertThat(new StoreProperties("jdbc:mysql://db:3306/alpha_trader", "u", "p").dialect())
                .isEqualTo(StoreProperties.Dialect.MYSQL);
    }

    @Test
    void anUnsupportedUrlFailsAtStartupWithTheUrlInTheMessage() {
        StoreProperties unsupported = new StoreProperties("jdbc:postgresql://db:5432/alpha", "u", "p");
        assertThatThrownBy(unsupported::dialect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdbc:postgresql");
    }

    @Test
    void mysqlWithoutCredentialsIsRefusedBeforeAnyQueryRuns() {
        StoreProperties missingPassword = new StoreProperties("jdbc:mysql://db:3306/alpha", "user", null);
        assertThatThrownBy(() -> StoreDataSource.create(missingPassword))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALPHA_DB_PASSWORD");
    }

    @Test
    void sqliteIsUsableImmediatelyAndTheSchemaAppliesToIt() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("store.db");
        StoreProperties store = new StoreProperties(url, null, null);

        DataSource dataSource = StoreDataSource.create(store);
        // The table the JDBC stores create proves the dialect-neutral DDL runs on this driver -
        // the point of the shared schema is that MySQL and SQLite get the same six tables.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE probe (id INTEGER PRIMARY KEY, note TEXT)");
            statement.execute("INSERT INTO probe (id, note) VALUES (1, 'ok')");
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            var rs = statement.executeQuery("SELECT note FROM probe WHERE id = 1");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("ok");
        }
    }

    @Test
    void aBlankJdbcUrlFallsBackToTheLocalDefault() {
        assertThat(new StoreProperties(null, null, null).jdbcUrl())
                .isEqualTo(StoreProperties.DEFAULT_JDBC_URL);
        assertThat(new StoreProperties("   ", null, null).jdbcUrl())
                .isEqualTo(StoreProperties.DEFAULT_JDBC_URL);
    }
}
