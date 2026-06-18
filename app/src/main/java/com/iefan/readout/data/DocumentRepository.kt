package com.iefan.readout.data

import kotlinx.coroutines.flow.Flow

class DocumentRepository(private val documentDao: DocumentDao) {
    val allDocuments: Flow<List<Document>> = documentDao.getAllDocuments()

    fun getDocumentByIdFlow(id: Long): Flow<Document?> = documentDao.getDocumentByIdFlow(id)

    suspend fun getDocumentById(id: Long): Document? = documentDao.getDocumentById(id)

    suspend fun insert(document: Document): Long = documentDao.insertDocument(document)

    suspend fun update(document: Document) = documentDao.updateDocument(document)

    suspend fun updatePlaybackPosition(id: Long, position: Int) = documentDao.updatePlaybackPositionWithTime(id, position, System.currentTimeMillis())

    suspend fun updatePlaybackSpeed(id: Long, speed: Float) = documentDao.updatePlaybackSpeed(id, speed)

    suspend fun updateModelTier(id: Long, tier: String) = documentDao.updateModelTier(id, tier)

    suspend fun delete(document: Document) = documentDao.deleteDocumentWithRelations(document)

    val allCollections: Flow<List<CollectionEntity>> = documentDao.getAllCollections()

    val allCrossRefs: Flow<List<DocumentCollectionCrossRef>> = documentDao.getAllCrossRefs()

    suspend fun updateFavoriteStatus(id: Long, isFav: Boolean) = documentDao.updateFavoriteStatus(id, isFav)

    suspend fun insertCollection(collection: CollectionEntity): Long = documentDao.insertCollection(collection)

    suspend fun deleteCollection(collection: CollectionEntity) = documentDao.deleteCollectionWithRelations(collection)

    suspend fun renameCollection(id: Long, newName: String) = documentDao.updateCollectionName(id, newName)

    suspend fun addDocumentToCollection(documentId: Long, collectionId: Long) = documentDao.insertCrossRef(DocumentCollectionCrossRef(documentId, collectionId))

    suspend fun removeDocumentFromCollection(documentId: Long, collectionId: Long) = documentDao.deleteCrossRef(documentId, collectionId)

    suspend fun deleteCrossRefsForCollection(collectionId: Long) = documentDao.deleteCrossRefsForCollection(collectionId)

    suspend fun deleteCrossRefsForDocument(documentId: Long) = documentDao.deleteCrossRefsForDocument(documentId)

    fun getChaptersForDocumentFlow(documentId: Long): Flow<List<Chapter>> = documentDao.getChaptersForDocumentFlow(documentId)

    suspend fun getChaptersForDocument(documentId: Long): List<Chapter> = documentDao.getChaptersForDocument(documentId)

    suspend fun insertChapters(chapters: List<Chapter>) = documentDao.insertChapters(chapters)

    suspend fun deleteChaptersForDocument(documentId: Long) = documentDao.deleteChaptersForDocument(documentId)
}
