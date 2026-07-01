package com.iefan.readout.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class Document(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val sourceUrl: String? = null,
    val addedDate: Long = System.currentTimeMillis(),
    val playbackPosition: Int = 0, // last character or paragraph index
    val selectedModelTier: String = "HIGH_FIDELITY", // "ULTRA_LIGHT", "BALANCED", "HIGH_FIDELITY"
    val playbackSpeed: Float = 1.0f,
    val coverPath: String? = null,
    val lastReadTime: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val contentLength: Int = 0
)
