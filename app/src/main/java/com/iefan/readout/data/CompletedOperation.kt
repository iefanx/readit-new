package com.iefan.readout.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Transactional receipt makes retries after process death idempotent. */
@Entity(tableName = "completed_operations")
data class CompletedOperation(@PrimaryKey val id: String, val resultId: Long)
