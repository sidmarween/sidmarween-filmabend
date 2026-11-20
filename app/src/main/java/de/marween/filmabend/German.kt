package de.marween.filmabend

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Übersetzt englische Beschreibungen ins Deutsche – direkt auf dem Handy
 * (Google ML Kit). Beim ersten Mal wird einmalig ein Sprachpaket (~30 MB) geladen.
 */
object German {
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.GERMAN)
                .build()
        )
    }
    private val cache = HashMap<String, String>()
    private val lock = Mutex()
    private var modelReady = false

    private val en = setOf("the", "and", "of", "is", "his", "her", "with", "to", "in", "when", "who", "their", "they", "an", "for", "after", "on", "are", "this", "that", "from")
    private val de = setOf("der", "die", "das", "und", "ist", "sein", "seine", "ihre", "mit", "zu", "ein", "eine", "wird", "sich", "nicht", "den", "dem", "des", "auf", "als", "nach", "für", "von")

    fun looksEnglish(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^a-zäöüß]+")).filter { it.isNotBlank() }
        val e = words.count { it in en }
        val d = words.count { it in de }
        return e >= 2 && e > d
    }

    /** Gibt den Text auf Deutsch zurück (oder unverändert, wenn er schon deutsch ist). */
    suspend fun translate(text: String): String {
        if (text.isBlank() || !looksEnglish(text)) return text
        cache[text]?.let { return it }
        val out = lock.withLock {
            if (!modelReady) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                modelReady = true
            }
            val parts = ArrayList<String>()
            for (line in text.split("\n")) {
                parts += if (line.isBlank()) line else translator.translate(line).await()
            }
            parts.joinToString("\n")
        }
        cache[text] = out
        return out
    }
}
