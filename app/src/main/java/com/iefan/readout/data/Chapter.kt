package com.iefan.readout.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = Document::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["documentId"])]
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val title: String,
    val startCharOffset: Int,
    val startSentenceIndex: Int = 0
)
