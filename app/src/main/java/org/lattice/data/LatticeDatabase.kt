package org.lattice.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.lattice.data.blob.BlobDao
import org.lattice.data.blob.BlobEntity
import org.lattice.data.blob.BlobVerdictDao
import org.lattice.data.blob.BlobVerdictEntity
import org.lattice.data.forward.ForwardDao
import org.lattice.data.forward.ForwardEntity
import org.lattice.data.group.GroupDao
import org.lattice.data.group.GroupEntity
import org.lattice.data.message.MessageDao
import org.lattice.data.message.MessageEntity
import org.lattice.data.peer.PeerDao
import org.lattice.data.peer.PeerEntity
import org.lattice.data.reaction.ReactionDao
import org.lattice.data.reaction.ReactionEntity

@Database(
    entities = [
        MessageEntity::class, PeerEntity::class, ReactionEntity::class, BlobEntity::class,
        GroupEntity::class, BlobVerdictEntity::class, ForwardEntity::class,
    ],
    // v1: frozen launch baseline. The pre-1.0 alpha schema churn (the old destructive v2…v22 bumps that
    //     rode the wire/crypto breaks) is collapsed; docs/WIRE_COMPAT.md keeps the historical break record.
    //     From v1 on, every @Database bump ships a tested KnitMigrations entry — a missing one throws at open
    //     time (caught by LatticeDatabaseMigrationTest), never a silent wipe of a user's messages/custody/pins.
    // v2: peers.phoneNumber (nullable) — SMS/MMS carrier-transport addressing. See KnitMigrations.MIGRATION_1_2
    //     and PeerEntity's doc comment.
    version = 2,
    // Export the schema JSON to app/schemas/ (location set by the androidx.room Gradle plugin's
    // room { schemaDirectory(...) } in app/build.gradle.kts). Keeps the schema diffable in review and feeds
    // the migration test's MigrationTestHelper. Room also errors at compile time if an entity changes without
    // a version bump.
    exportSchema = true,
)
abstract class LatticeDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun peerDao(): PeerDao

    abstract fun reactionDao(): ReactionDao

    abstract fun blobDao(): BlobDao

    abstract fun groupDao(): GroupDao

    abstract fun blobVerdictDao(): BlobVerdictDao

    abstract fun forwardDao(): ForwardDao

    companion object {
        /**
         * Builds the encrypted database. [passphrase] is the SQLCipher key (see
         * [org.lattice.data.crypto.DatabaseKey]); SQLCipher zeroes it once the DB is opened.
         * The native `libsqlcipher.so` must be loaded explicitly before the factory is created.
         */
        @Suppress("SpreadOperator") // vararg Room migrations API; a one-time DB-init copy
        fun build(
            context: Context,
            passphrase: ByteArray,
        ): LatticeDatabase {
            System.loadLibrary("sqlcipher")
            return Room
                .databaseBuilder(context, LatticeDatabase::class.java, "knit.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                // Production migration posture: v1 is the frozen launch baseline, with NO destructive fallback.
                // Every schema change from here ships a tested KnitMigrations entry; a version bump with no
                // matching migration makes Room throw at open time (caught by LatticeDatabaseMigrationTest) — a loud
                // failure in CI, never a silent wipe of a user's messages/custody/pins in production.
                .addMigrations(*KnitMigrations.ALL)
                .build()
        }
    }
}
