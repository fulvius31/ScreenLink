package com.sangiorgisrl.screenlink.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConnectionEntity::class, SshKeyEntity::class, ConnectionKeyCrossRef::class],
    version = 3,
    // Schema export is ON so the generated JSON is committed and the on-device
    // migration suite (RoomMigrationTest) can validate every upgrade on real SQLite.
    exportSchema = true,
)
abstract class ConnectionDatabase : RoomDatabase() {

    abstract fun connectionDao(): ConnectionDao
    abstract fun sshKeyDao(): SshKeyDao

    companion object {
        @Volatile private var instance: ConnectionDatabase? = null

        /** v1 -> v2: add the ssh_keys and connection_keys tables. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ssh_keys` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`private_key_enc` TEXT NOT NULL, " +
                        "`public_key` TEXT, " +
                        "`passphrase_enc` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `connection_keys` (" +
                        "`connectionId` INTEGER NOT NULL, " +
                        "`keyId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`connectionId`, `keyId`))"
                )
            }
        }

        /**
         * v2 -> v3: remember each key's type at import ("openssh" | "ec" | ... -
         * a stable tag, NOT a resource id) so the UI badge never needs to decrypt
         * the private key. Pre-existing keys default to "unknown".
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `ssh_keys` ADD COLUMN `key_algo` TEXT NOT NULL DEFAULT 'unknown'")
            }
        }

        fun getInstance(context: Context): ConnectionDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ConnectionDatabase::class.java,
                    "screenlink.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
