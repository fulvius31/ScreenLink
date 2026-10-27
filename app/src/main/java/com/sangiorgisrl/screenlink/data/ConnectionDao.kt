package com.sangiorgisrl.screenlink.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {

    @Query("SELECT * FROM connections ORDER BY display_name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun getById(id: Long): ConnectionEntity?

    @Query("SELECT COUNT(*) FROM connections")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connection: ConnectionEntity): Long

    /** Returns the number of rows updated (0 when the row is gone). */
    @Update
    suspend fun update(connection: ConnectionEntity): Int

    @Delete
    suspend fun delete(connection: ConnectionEntity)
}
