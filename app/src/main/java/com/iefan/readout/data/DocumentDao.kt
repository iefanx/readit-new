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

    @Query("UPDATE documents SET playbackPosition = :position, lastReadTime = :lastReadTime WHERE id = :id")
    suspend fun updatePlaybackPositionWithTime(id: Long, position: Int, lastReadTime: Long)

    @Query("UPDATE documents SET playbackSpeed = :speed WHERE id = :id")
    suspend fun updatePlaybackSpeed(id: Long, speed: Float)

    @Query("UPDATE documents SET selectedModelTier = :tier WHERE id = :id")
    suspend fun updateModelTier(id: Long, tier: String)

    @Query("UPDATE documents SET isFavorite = :isFav WHERE id = :id")
    suspend fun updateFavoriteStatus(id: Long, isFav: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :newName WHERE id = :id")
    suspend fun updateCollectionName(id: Long, newName: String)

    @Delete
    suspend fun deleteCollection(collection: CollectionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: DocumentCollectionCrossRef)

    @Query("DELETE FROM document_collection_cross_ref WHERE documentId = :documentId AND collectionId = :collectionId")
    suspend fun deleteCrossRef(documentId: Long, collectionId: Long)

    @Query("DELETE FROM document_collection_cross_ref WHERE collectionId = :collectionId")
    suspend fun deleteCrossRefsForCollection(collectionId: Long)

    @Query("DELETE FROM document_collection_cross_ref WHERE documentId = :documentId")
    suspend fun deleteCrossRefsForDocument(documentId: Long)

    @Query("SELECT * FROM collections ORDER BY name ASC")
    fun getAllCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM document_collection_cross_ref")
    fun getAllCrossRefs(): Flow<List<DocumentCollectionCrossRef>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<Chapter>)

    @Query("SELECT * FROM chapters WHERE documentId = :documentId ORDER BY startCharOffset ASC")
    fun getChaptersForDocumentFlow(documentId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE documentId = :documentId ORDER BY startCharOffset ASC")
    suspend fun getChaptersForDocument(documentId: Long): List<Chapter>

    @Query("DELETE FROM chapters WHERE documentId = :documentId")
    suspend fun deleteChaptersForDocument(documentId: Long)

    @Delete
    suspend fun deleteDocument(document: Document)

    @Transaction
    suspend fun deleteDocumentWithRelations(document: Document) {
        deleteCrossRefsForDocument(document.id)
        deleteChaptersForDocument(document.id)
        deleteDocument(document)
    }

    @Transaction
    suspend fun deleteCollectionWithRelations(collection: CollectionEntity) {
        deleteCrossRefsForCollection(collection.id)
        deleteCollection(collection)
    }
}
