package com.iefan.readout.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY lastReadTime DESC, addedDate DESC")
    fun getAllDocuments(): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    fun getDocumentByIdFlow(id: Long): Flow<Document?>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: Long): Document?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: Document): Long

    @Update
    suspend fun updateDocument(document: Document)

    @Query("UPDATE documents SET playbackPosition = :position WHERE id = :id")
    suspend fun updatePlaybackPosition(id: Long, position: Int)

    @Query("UPDATE documents SET playbackSpeed = :speed WHERE id = :id")
    suspend fun updatePlaybackSpeed(id: Long, speed: Float)

    @Query("UPDATE documents SET selectedModelTier = :tier WHERE id = :id")
    suspend fun updateModelTier(id: Long, tier: String)

    @Delete
    suspend fun deleteDocument(document: Document)
}
