package com.sangiorgisrl.screenlink.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device migration suite backed by the committed Room schema JSON (written to
 * app/schemas by KSP thanks to exportSchema=true). It runs the hand-written
 * migrations against real SQLite and validates the result against the current
 * schema, so a destructive typo in a future migration fails HERE instead of in a
 * user's upgrade.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    private val testDb = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ConnectionDatabase::class.java,
        emptyList(),
    )

    /** v2 -> v3: keys gain the stable key_algo tag (default "unknown" for existing rows). */
    @Test
    fun migrate2To3_addsKeyAlgoTag() {
        helper.createDatabase(testDb, 2).close()
        val db = helper.runMigrationsAndValidate(
            testDb,
            3,
            true,
            ConnectionDatabase.MIGRATION_2_3,
        )
        // The new column exists and is readable (pre-existing rows carry the default).
        db.query("SELECT key_algo FROM ssh_keys").use { cursor ->
            assertNotNull(cursor)
        }
    }
}
