package de.marween.filmabend

import com.google.firebase.firestore.DocumentSnapshot
import java.util.Locale

/** Ein Film oder eine Serie in eurer gemeinsamen Bibliothek (Firestore). */
data class Title(
    val key: String,
    val mediaId: Int,
    val type: String,               // "movie" oder "tv"
    val title: String,
    val year: String,
    val poster: String?,
    val overview: String,
    val genres: List<String>,
    val seasons: Int,
    val watched: Map<String, Boolean>,
    val ratings: Map<String, Int>,
    val watchlist: Map<String, Boolean>,
    val seasonsWatched: Map<String, List<Int>>,
    val watchedAt: Map<String, Long>,
    val addedAt: Long,
    val together: Boolean = false,
    val togetherBy: String = "",
    val posterChecked: Boolean = false,
    /** „Nicht mein Fall“ – pro Person abgelehnt unter „Neu“ */
    val rejected: Map<String, Boolean> = emptyMap(),
) {
    fun isRejected(uid: String?) = uid != null && rejected[uid] == true
    fun isWatched(uid: String?) = uid != null && watched[uid] == true
    fun onWatchlist(uid: String?) = uid != null && watchlist[uid] == true
    fun rating(uid: String?): Int? = uid?.let { ratings[it] }
    fun avgRating(): Double? = ratings.values.takeIf { it.isNotEmpty() }?.average()
    fun seasonsOf(uid: String?): List<Int> = uid?.let { seasonsWatched[it] } ?: emptyList()
    val info get() = TitleInfo(mediaId, type, title, year, poster, overview, genres, seasons)

    companion object {
        fun from(doc: DocumentSnapshot): Title? {
            val id = (doc.get("mediaId") as? Number)?.toInt() ?: return null
            val type = doc.getString("type") ?: return null
            return Title(
                key = doc.id,
                mediaId = id,
                type = type,
                title = doc.getString("title") ?: "",
                year = doc.getString("year") ?: "",
                poster = doc.getString("poster")?.takeIf { it.isNotBlank() },
                overview = doc.getString("overview") ?: "",
                genres = (doc.get("genres") as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                seasons = (doc.get("seasons") as? Number)?.toInt() ?: 0,
                watched = boolMap(doc.get("watched")),
                ratings = intMap(doc.get("ratings")),
                watchlist = boolMap(doc.get("watchlist")),
                seasonsWatched = (doc.get("seasonsWatched") as? Map<*, *>)?.entries
                    ?.associate { (k, v) ->
                        k.toString() to ((v as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList())
                    } ?: emptyMap(),
                watchedAt = longMap(doc.get("watchedAt")),
                addedAt = (doc.get("addedAt") as? Number)?.toLong() ?: 0L,
                together = doc.getBoolean("together") == true,
                togetherBy = doc.getString("togetherBy") ?: "",
                posterChecked = doc.getBoolean("posterChecked") == true,
                rejected = boolMap(doc.get("rejected")),
            )
        }

        private fun boolMap(v: Any?): Map<String, Boolean> =
            (v as? Map<*, *>)?.entries?.associate { (k, x) -> k.toString() to (x == true) } ?: emptyMap()

        private fun intMap(v: Any?): Map<String, Int> =
            (v as? Map<*, *>)?.entries?.mapNotNull { (k, x) -> (x as? Number)?.let { k.toString() to it.toInt() } }
                ?.toMap() ?: emptyMap()

        private fun longMap(v: Any?): Map<String, Long> =
            (v as? Map<*, *>)?.entries?.mapNotNull { (k, x) -> (x as? Number)?.let { k.toString() to it.toLong() } }
                ?.toMap() ?: emptyMap()
    }
}

/** Grunddaten eines Titels – reicht, um ihn in der Bibliothek anzulegen. */
data class TitleInfo(
    val mediaId: Int,
    val type: String,
    val title: String,
    val year: String,
    val poster: String?,
    val overview: String,
    val genres: List<String> = emptyList(),
    val seasons: Int = 0,
) {
    val key get() = keyOf(type, mediaId)
}

fun keyOf(type: String, id: Int) = "${type}_$id"

data class Suggestion(
    val id: String,
    val from: String,
    val to: String,
    val key: String,
    val mediaId: Int,
    val type: String,
    val title: String,
    val year: String,
    val poster: String?,
    val note: String,
    val createdAt: Long,
    val status: String,   // "offen", "angenommen", "abgelehnt"
    val seen: Boolean,
) {
    companion object {
        fun from(doc: DocumentSnapshot): Suggestion? {
            val id = (doc.get("mediaId") as? Number)?.toInt() ?: return null
            return Suggestion(
                id = doc.id,
                from = doc.getString("from") ?: "",
                to = doc.getString("to") ?: "",
                key = doc.getString("key") ?: "",
                mediaId = id,
                type = doc.getString("type") ?: "movie",
                title = doc.getString("title") ?: "",
                year = doc.getString("year") ?: "",
                poster = doc.getString("poster")?.takeIf { it.isNotBlank() },
                note = doc.getString("note") ?: "",
                createdAt = (doc.get("createdAt") as? Number)?.toLong() ?: 0L,
                status = doc.getString("status") ?: "offen",
                seen = doc.getBoolean("seen") == true,
            )
        }
    }
}

data class Profile(val name: String, val hid: String?)

data class Household(
    val id: String,
    val code: String,
    val members: List<String>,
    val names: Map<String, String>,
) {
    fun partnerOf(uid: String?): String? = members.firstOrNull { it != uid }
    fun nameOf(uid: String?): String = uid?.let { names[it] } ?: "?"
}

fun typeLabel(type: String) = if (type == "tv") "Serie" else "Film"

fun ratingWord(r: Int): String = when (r) {
    1, 2 -> "Grottig"
    3, 4 -> "Schwach"
    5, 6 -> "Ganz okay"
    7, 8 -> "Gut"
    9 -> "Großartig"
    else -> "Meisterwerk"
}

fun fmt1(v: Double): String = String.format(Locale.GERMANY, "%.1f", v)
