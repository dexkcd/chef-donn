package ph.chefdonn.hubserver

import app.cash.sqldelight.db.SqlDriver
import ph.chefdonn.hubserver.db.HubDatabase

/**
 * Builds the database from an injected driver so the same code runs on Android
 * (AndroidSqliteDriver) and JVM tests (JdbcSqliteDriver). Schema uses IF NOT EXISTS,
 * so creating it on an existing file is a no-op.
 */
fun hubDatabase(driver: SqlDriver): HubDatabase {
    HubDatabase.Schema.create(driver)
    // Survive power loss mid-write: WAL keeps the log consistent, FULL fsyncs commits.
    driver.execute(null, "PRAGMA journal_mode=WAL", 0)
    driver.execute(null, "PRAGMA synchronous=FULL", 0)
    return HubDatabase(driver)
}
