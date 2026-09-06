package com.iefan.readout

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.iefan.readout.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseRegressionTest {
    @Test fun largeUnicodeDocumentRoundTrip() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repository = DocumentRepository(db.documentDao())
            val text = "😀" + "a".repeat(799999) + "BOUNDARY" + "b".repeat(800000)
            val id = repository.insert(Document(title = "Unicode", content = text, contentLength = text.length))
            assertEquals(text, repository.getDocumentById(id)?.content)
        } finally { db.close() }
    }
    @Test fun failedTransactionDoesNotLeaveDocumentOrReceipt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            try {
                db.withTransaction {
                    db.documentDao().insertDocument(Document(title = "Partial", content = "Text"))
                    db.documentDao().completeOperation(CompletedOperation("restore", 1))
                    error("Malformed later record")
                }
            } catch (_: IllegalStateException) { }
            assertNull(db.documentDao().getDocumentById(1))
            assertNull(db.documentDao().completedOperation("restore"))
        } finally { db.close() }
    }
    @Test fun migrationPreservesVersionSevenLibrary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-audit.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val id = first.documentDao().insertDocument(Document(title = "Existing", content = "Keep this."))
        first.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0).use {
            it.execSQL("DROP TABLE completed_operations")
            it.version = 7
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_7_8).build()
        try {
            assertEquals("Keep this.", migrated.documentDao().getDocumentById(id)?.content)
            migrated.documentDao().completeOperation(CompletedOperation("new", id))
            assertEquals(id, migrated.documentDao().completedOperation("new"))
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
