package com.dresos.lattice.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.dresos.lattice.data.blob.BlobDao
import com.dresos.lattice.data.blob.BlobEntity
import com.dresos.lattice.data.blob.BlobVerdictDao
import com.dresos.lattice.data.blob.BlobVerdictEntity
import com.dresos.lattice.data.forward.ForwardDao
import com.dresos.lattice.data.forward.ForwardEntity
import com.dresos.lattice.data.group.GroupDao
import com.dresos.lattice.data.group.GroupEntity
import com.dresos.lattice.data.message.MessageDao
import com.dresos.lattice.data.message.MessageEntity
import com.dresos.lattice.data.peer.PeerDao
import com.dresos.lattice.data.peer.PeerEntity
import com.dresos.lattice.data.reaction.ReactionDao
import com.dresos.lattice.data.reaction.ReactionEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        MessageEntity::class, PeerEntity::class, ReactionEntity::class, BlobEntity::class,
        GroupEntity::class, BlobVerdictEntity::class, ForwardEntity::class,
    ],
    // v1: frozen launch baseline. The pre-1.0 alpha schema churn (the old destructive v2…v22 bumps that
    //     rode the wire/crypto breaks) is collapsed; docs/WIRE_COMPAT.md keeps the historical break record.
    //     From v1 on, every @Database bump ships a tested KnitMigrations entry — a missing one throws at open
    //     time (caught by LatticeDatabaseMigrationTest), never a silent wipe of a user's messages/custody/pins.
    version = 1,
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
         * [com.dresos.lattice.data.crypto.DatabaseKey]); SQLCipher zeroes it once the DB is opened.
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
