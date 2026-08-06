package org.lattice.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Migration-testing harness. **v1 is the frozen launch baseline:** there is no destructive fallback, and from
 * v1 forward every schema bump ships a tested [KnitMigrations] entry validated here — `MIGRATION_1_2` (v2:
 * `peers.phoneNumber`, see [org.lattice.data.peer.PeerEntity]) is the first one. `createDatabase(version)`
 * rebuilds the DB from the
 * checked-in `app/schemas/org.lattice.data.LatticeDatabase/<version>.json`, proving `exportSchema`, the Room
 * Gradle plugin's `schemaDirectory` export, the unit-test asset wiring (Robolectric serves `sourceSets["test"]`
 * assets), and the `MigrationTestHelper` harness all line up. The version below is hardcoded rather than read
 * off the `@Database` annotation (its retention is CLASS, so it can't be read reflectively) — bump it by hand
 * alongside every `@Database(version = …)` change, or this test silently stops testing the current schema.
 *
 * It uses the driver-based [MigrationTestHelper] constructor with [AndroidSQLiteDriver] — the connection API
 * (`createDatabase`/`runMigrationsAndValidate` returning a `SQLiteConnection`) requires a `SQLiteDriver`, and
 * the framework driver runs on Robolectric's shadowed SQLite (the same engine the DAO tests use;
 * `BundledSQLiteDriver` can't load its Android native lib on the host JVM). When the next schema change lands,
 * add a [KnitMigrations] entry, bump the version below, and add a `migrate N to N+1` test following
 * `migrate 1 to 2` — `runMigrationsAndValidate` then validates both the migrated schema and the carried data.
 */
@RunWith(AndroidJUnit4::class)
class LatticeDatabaseMigrationTest {
    private val dbFile = File.createTempFile("knit-migration", ".db").apply { delete() } // path must be free

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = dbFile,
            driver = AndroidSQLiteDriver(),
            databaseClass = LatticeDatabase::class,
        )

    @Test
    fun `the current schema (v2) creates and opens from the exported JSON`() {
        val version = 2 // LatticeDatabase @Database(version = 2) — bump alongside the DB (its retention is CLASS,
        // so the version can't be read reflectively). A missing schemas/<db>/<version>.json fails here.
        helper.createDatabase(version).close()
    }

    @Test
    fun `migrate 1 to 2 preserves peer rows and adds a nullable phoneNumber column`() {
        helper.createDatabase(1).use { c ->
            c
                .prepare("INSERT INTO peers (nodeId, name, status, verified, updatedAt) VALUES ('n1','Ann','',0,0)")
                .use { it.step() }
        }
        helper.runMigrationsAndValidate(2, listOf(KnitMigrations.MIGRATION_1_2)).use { c ->
            // The pre-existing row survived the migration, and its new column defaulted to NULL
            // ("mesh-only" — see PeerEntity's doc comment), not to an empty string or 0.
            c.prepare("SELECT name, phoneNumber FROM peers WHERE nodeId = 'n1'").use { s ->
                assertTrue(s.step())
                assertEquals("Ann", s.getText(0))
                assertTrue(s.isNull(1))
            }
            // The column actually accepts and round-trips a real value for a newly-attached peer.
            c.prepare("UPDATE peers SET phoneNumber = '+15551234567' WHERE nodeId = 'n1'").use { it.step() }
            c.prepare("SELECT phoneNumber FROM peers WHERE nodeId = 'n1'").use { s ->
                assertTrue(s.step())
                assertEquals("+15551234567", s.getText(0))
            }
        }
    }
}
