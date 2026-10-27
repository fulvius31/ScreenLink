package com.sangiorgisrl.screenlink.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SshKeyDao {

    @Query("SELECT * FROM ssh_keys ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getById(id: Long): SshKeyEntity?

    @Insert
    suspend fun insert(key: SshKeyEntity): Long

    @Delete
    suspend fun delete(key: SshKeyEntity)

    // --- connection <-> key junction -----------------------------------------

    @Query(
        """SELECT k.* FROM ssh_keys k
           INNER JOIN connection_keys ck ON k.id = ck.keyId
           WHERE ck.connectionId = :connectionId
           ORDER BY k.name COLLATE NOCASE ASC"""
    )
    suspend fun getKeysForConnection(connectionId: Long): List<SshKeyEntity>

    @Query("SELECT keyId FROM connection_keys WHERE connectionId = :connectionId")
    suspend fun getKeyIdsForConnection(connectionId: Long): List<Long>

    @Query("DELETE FROM connection_keys WHERE connectionId = :connectionId")
    suspend fun clearConnectionKeys(connectionId: Long)

    @Query("DELETE FROM connection_keys WHERE keyId = :keyId")
    suspend fun deleteConnectionKeysByKeyId(keyId: Long)

    @Insert
    suspend fun insertCrossRef(ref: ConnectionKeyCrossRef)
}
