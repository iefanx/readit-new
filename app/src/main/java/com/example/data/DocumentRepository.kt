package com.example.data

import kotlinx.coroutines.flow.Flow

class DocumentRepository(private val documentDao: DocumentDao) {
    val allDocuments: Flow<List<Document>> = documentDao.getAllDocuments()

    fun getDocumentByIdFlow(id: Long): Flow<Document?> = documentDao.getDocumentByIdFlow(id)

    suspend fun getDocumentById(id: Long): Document? = documentDao.getDocumentById(id)

    suspend fun insert(document: Document): Long = documentDao.insertDocument(document)

    suspend fun update(document: Document) = documentDao.updateDocument(document)

    suspend fun updatePlaybackPosition(id: Long, position: Int) = documentDao.updatePlaybackPosition(id, position)

    suspend fun updatePlaybackSpeed(id: Long, speed: Float) = documentDao.updatePlaybackSpeed(id, speed)

    suspend fun updateModelTier(id: Long, tier: String) = documentDao.updateModelTier(id, tier)

    suspend fun delete(document: Document) = documentDao.deleteDocument(document)
}
