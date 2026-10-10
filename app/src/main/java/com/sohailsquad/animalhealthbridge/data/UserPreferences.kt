package com.sohailsquad.animalhealthbridge.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.userSettings by preferencesDataStore("user_settings")

class UserPreferences(context: Context) {
    private val store = context.applicationContext.userSettings
    private val languageKey = stringPreferencesKey("language")
    val language = store.data.catch { failure ->
        if (failure is CancellationException) throw failure
        Log.w("AhbAsr", "Preferences unavailable; using Hindi", failure)
        emit(androidx.datastore.preferences.core.emptyPreferences())
    }.map { it[languageKey] ?: "hi" }

    suspend fun selectedLanguage(): String = language.first()
    suspend fun setLanguage(code: String) {
        require(code.matches(Regex("[a-z]{2,3}")))
        store.edit { it[languageKey] = code }
        Log.i("AhbAsr", "Input language saved: $code")
    }
}
