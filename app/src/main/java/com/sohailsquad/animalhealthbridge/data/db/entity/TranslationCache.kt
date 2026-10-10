package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translation_entries")
data class TranslationCache(
    @PrimaryKey val sourceHash: String,
    val sourceText: String,
    val sourceLang: String,
    val translatedText: String,
    val createdAt: Long = System.currentTimeMillis(),
)
