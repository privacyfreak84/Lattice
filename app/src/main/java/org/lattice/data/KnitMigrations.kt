package org.lattice.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * The registry of tested schema migrations applied in [LatticeDatabase.build].
 *
 * **v1 is the frozen launch baseline.** There is no destructive fallback: from v1 onward every `@Database`
 * version bump MUST add a [Migration] here — a missing one makes Room throw at open time (caught by
 * `LatticeDatabaseMigrationTest`) instead of silently wiping user data. So this is the single place production
 * migrations live: keep it in lockstep with `@Database(version = …)` and the checked-in
 * `app/schemas/**/<version>.json`.
 */
object KnitMigrations {
    /**
     * Adds `peers.phoneNumber` (nullable TEXT, no default needed since it's nullable) — the SMS/MMS
     * carrier-transport addressing field; see [org.lattice.data.peer.PeerEntity]. Every existing row gets
     * `NULL` ("mesh-only"), which is exactly the right value for a peer nobody has attached a number to yet.
     */
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                // prepare().use { it.step() }, not execSQL: androidx.sqlite has no bare execSQL extension on
                // SQLiteConnection in the version this project pins (a prior attempt at that failed to
                // compile -- "Unresolved reference"). prepare()/step() is the primitive already proven to
                // work against this exact SQLiteConnection type in LatticeDatabaseMigrationTest.
                connection.prepare("ALTER TABLE peers ADD COLUMN phoneNumber TEXT").use { it.step() }
            }
        }

    /**
     * Adds `peers.profileSentAt` (nullable INTEGER, epoch millis) — when we last sent our own profile
     * directly to an SMS-only peer's number, for the SMS first-contact handshake; see
     * [org.lattice.data.peer.PeerEntity]. Every existing row gets `NULL` — exactly right for a pre-existing
     * peer, since none of them are mid-SMS-handshake by definition (this field didn't exist for them to be).
     */
    val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare("ALTER TABLE peers ADD COLUMN profileSentAt INTEGER").use { it.step() }
            }
        }

    /** All migrations, applied by Room in order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
