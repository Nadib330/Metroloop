/**
 * MetroLoop Project (C) 2026
 * Based on the Metrolist project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.R
import com.metrolist.music.api.GoogleTranslateService
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.LyricsEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides Google Translate powered translations for lyrics and keeps them in sync with the
 * lyrics list currently on screen.
 */
object LyricsTranslationHelper {
    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    val status: StateFlow<TranslationStatus> = _status.asStateFlow()

    private val _hasActiveTranslations = MutableStateFlow(false)
    val hasActiveTranslations: StateFlow<Boolean> = _hasActiveTranslations.asStateFlow()

    private val _manualTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val manualTrigger: SharedFlow<Unit> = _manualTrigger.asSharedFlow()

    private val _clearTranslationsTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val clearTranslationsTrigger: SharedFlow<Unit> = _clearTranslationsTrigger.asSharedFlow()

    private var translationJob: Job? = null
    private var isCompositionActive = true

    // Cache translations in memory to avoid redundant network calls during a session
    private val translationCache = ConcurrentHashMap<String, List<String>>()

    fun setCompositionActive(active: Boolean) {
        isCompositionActive = active
    }

    fun triggerManualTranslation() {
        _manualTrigger.tryEmit(Unit)
    }

    fun triggerClearTranslations() {
        _clearTranslationsTrigger.tryEmit(Unit)
        _hasActiveTranslations.value = false
    }

    fun clearTranslations(lyrics: LyricsEntity): LyricsEntity {
        return lyrics.copy(
            translatedLyrics = "",
            translationLanguage = "",
            translationMode = "",
        )
    }

    fun cancelTranslation() {
        translationJob?.cancel()
        if (_status.value is TranslationStatus.Translating) {
            _status.value = TranslationStatus.Idle
        }
    }

    private fun getCacheKey(
        text: String,
        mode: String,
        targetLanguage: String,
    ): String = "${text.hashCode()}_${mode}_${targetLanguage}"

    fun loadTranslationsFromDatabase(
        lyrics: List<LyricsEntry>,
        lyricsEntity: LyricsEntity?,
        targetLanguage: String,
        mode: String,
    ) {
        if (lyricsEntity == null || lyricsEntity.translatedLyrics.isNullOrBlank()) {
            _hasActiveTranslations.value = false
            lyrics.forEach { it.translatedTextFlow.value = null }
            return
        }

        // Only load if language and mode match
        if (lyricsEntity.translationLanguage != targetLanguage || lyricsEntity.translationMode != mode) {
            _hasActiveTranslations.value = false
            lyrics.forEach { it.translatedTextFlow.value = null }
            return
        }

        val translatedLines = lyricsEntity.translatedLyrics.split("\n")
        val nonEmptyEntries = lyrics.filter { it.text.isNotBlank() }

        if (translatedLines.size >= nonEmptyEntries.size) {
            var transIndex = 0
            lyrics.forEach { entry ->
                if (entry.text.isNotBlank() && transIndex < translatedLines.size) {
                    entry.translatedTextFlow.value = translatedLines[transIndex]
                    transIndex++
                }
            }

            // Also cache them
            val fullText = nonEmptyEntries.joinToString("\n") { it.text }
            translationCache[getCacheKey(fullText, mode, targetLanguage)] = translatedLines
            _hasActiveTranslations.value = true
        }
    }

    fun translateLyrics(
        lyrics: List<LyricsEntry>,
        targetLanguage: String,
        mode: String,
        scope: CoroutineScope,
        context: Context,
        songId: String = "",
        database: MusicDatabase? = null,
    ) {
        translationJob?.cancel()
        _status.value = TranslationStatus.Translating

        // Clear existing translations to indicate re-translation
        lyrics.forEach { it.translatedTextFlow.value = null }

        translationJob =
            scope.launch(Dispatchers.IO) {
                try {
                    if (targetLanguage.isBlank()) {
                        _status.value = TranslationStatus.Error(context.getString(R.string.ai_error_language_required))
                        return@launch
                    }

                    if (lyrics.isEmpty()) {
                        _status.value = TranslationStatus.Error(context.getString(R.string.ai_error_no_lyrics))
                        return@launch
                    }

                    // Filter out empty lines and keep track of their indices
                    val nonEmptyEntries =
                        lyrics.mapIndexedNotNull { index, entry ->
                            if (entry.text.isNotBlank()) index to entry else null
                        }

                    if (nonEmptyEntries.isEmpty()) {
                        _status.value = TranslationStatus.Error(context.getString(R.string.ai_error_lyrics_empty))
                        return@launch
                    }

                    val fullText = nonEmptyEntries.joinToString("\n") { it.second.text }

                    // Check cache first
                    val cacheKey = getCacheKey(fullText, mode, targetLanguage)
                    val cachedTranslations = translationCache[cacheKey]
                    if (cachedTranslations != null && cachedTranslations.size >= nonEmptyEntries.size) {
                        nonEmptyEntries.forEachIndexed { idx, (originalIndex, _) ->
                            if (idx < cachedTranslations.size) {
                                lyrics[originalIndex].translatedTextFlow.value = cachedTranslations[idx]
                            }
                        }
                        _hasActiveTranslations.value = true
                        _status.value = TranslationStatus.Success

                        // Persist cached translations so loadTranslationsFromDatabase can't
                        // overwrite them with a stale empty entity (e.g. after an untranslate race).
                        persistTranslations(
                            songId = songId,
                            database = database,
                            translatedLines = cachedTranslations,
                            targetLanguage = targetLanguage,
                            mode = mode,
                        )

                        hideSuccessAfterDelay()
                        return@launch
                    }

                    GoogleTranslateService
                        .translate(
                            lines = nonEmptyEntries.map { it.second.text },
                            targetLanguage = targetLanguage,
                            mode = mode,
                        ).onSuccess { translatedLines ->
                            if (!isCompositionActive) return@onSuccess

                            translationCache[cacheKey] = translatedLines

                            persistTranslations(
                                songId = songId,
                                database = database,
                                translatedLines = translatedLines,
                                targetLanguage = targetLanguage,
                                mode = mode,
                            )

                            // Map translations back to the non-empty entries they belong to
                            nonEmptyEntries.forEachIndexed { idx, (originalIndex, _) ->
                                val translation = translatedLines.getOrNull(idx)
                                if (!translation.isNullOrBlank()) {
                                    lyrics[originalIndex].translatedTextFlow.value = translation
                                }
                            }

                            _hasActiveTranslations.value = true
                            _status.value = TranslationStatus.Success
                            hideSuccessAfterDelay()
                        }.onFailure { error ->
                            if (!isCompositionActive) return@onFailure

                            Timber.e(error, "Google Translate failed")
                            _status.value =
                                TranslationStatus.Error(
                                    error.message ?: context.getString(R.string.ai_error_translation_failed),
                                )
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isCompositionActive) {
                        Timber.e(e, "Lyrics translation failed")
                        val errorMessage = e.message ?: context.getString(R.string.ai_error_unknown)
                        _status.value = TranslationStatus.Error(errorMessage)
                    }
                }
            }
    }

    private suspend fun persistTranslations(
        songId: String,
        database: MusicDatabase?,
        translatedLines: List<String>,
        targetLanguage: String,
        mode: String,
    ) {
        if (songId.isBlank() || database == null) return
        try {
            val currentLyrics = database.lyrics(songId).first() ?: return
            if (!currentLyrics.translatedLyrics.isNullOrBlank()) return
            database.query {
                upsert(
                    currentLyrics.copy(
                        translatedLyrics = translatedLines.joinToString("\n"),
                        translationLanguage = targetLanguage,
                        translationMode = mode,
                    ),
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save translated lyrics to database")
        }
    }

    private suspend fun hideSuccessAfterDelay() {
        delay(3000)
        if (_status.value is TranslationStatus.Success && isCompositionActive) {
            _status.value = TranslationStatus.Idle
        }
    }

    sealed class TranslationStatus {
        data object Idle : TranslationStatus()

        data object Translating : TranslationStatus()

        data object Success : TranslationStatus()

        data class Error(
            val message: String,
        ) : TranslationStatus()
    }
}
