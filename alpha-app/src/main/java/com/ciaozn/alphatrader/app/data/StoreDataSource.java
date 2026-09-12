package com.ciaozn.alphatrader.app.data;

import com.ciaozn.alphatrader.app.config.StoreProperties;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

/**
 * Builds the business DataSource for whichever dialect the URL names (T408).
 *
 * <p><b>No connection pool abstraction, and no Spring auto-configuration.</b> Both drivers come with
 * a DataSource that is exactly what this application needs - one process, a handful of queries per
 * minute - and Boot's auto-configuration would be a second, invisible place where the URL is read.
 * The store is opened once at startup and its schema created by the JDBC stores themselves, so a
 * pool would only add a layer between a failure and the message describing it.
 *
 * <p><b>SQLite keeps its file semantics; MySQL gets a real connection.</b> That difference is not
 * papered over: MySQL gets credentials validated up front (see
 * {@link StoreProperties#requireCredentialsIfNeeded()}), because a missing password there is a
 * runtime failure on the first write, and SQLite is left alone.
 */
public final class StoreDataSource {

    private StoreDataSource() {
    }

    public static DataSource create(StoreProperties store) {
        store.requireCredentialsIfNeeded();
        return switch (store.dialect()) {
            case SQLITE -> sqlite(store.jdbcUrl());
            case MYSQL -> mysql(store);
        };
    }

    private static DataSource sqlite(String jdbcUrl) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(jdbcUrl);
        return dataSource;
    }

    private static DataSource mysql(StoreProperties store) {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(store.jdbcUrl());
        dataSource.setUser(store.effectiveUsername());
        dataSource.setPassword(store.effectivePassword());
        return dataSource;
    }
}
