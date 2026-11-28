/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rpooi3.metroloop.lyrics

import android.content.Context
import com.rpooi3.metroloop.betterlyrics.BetterLyrics
import com.rpooi3.metroloop.constants.EnableBetterLyricsKey
import com.rpooi3.metroloop.utils.dataStore
import com.rpooi3.metroloop.utils.get

object BetterLyricsProvider : LyricsProvider {
    override val name = "BetterLyrics"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableBetterLyricsKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = BetterLyrics.getLyrics(title, artist, duration, album)
}
