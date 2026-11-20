package de.marween.filmabend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ein Treffer aus der Filmdatenbank TMDB. */
data class TmdbItem(
    val id: Int,
    val type: String,
    val title: String,
    val year: String,
    val poster: String?,
    val overview: String,
    val vote: Double,
) {
    val key get() = keyOf(type, id)
    val info get() = TitleInfo(id, type, title, year, poster, overview)
}

data class TmdbDetail(
    val item: TmdbItem,
    val genres: List<String>,
    val seasons: Int,
    val runtime: Int,
    val providers: List<String>,
    val tagline: String,
) {
    val info get() = item.info.copy(genres = genres, seasons = seasons)
}

/** Kleiner TMDB-Client ohne zusätzliche Bibliotheken. Alles auf Deutsch (de-DE). */
object Tmdb {
    private val key = BuildConfig.TMDB_API_KEY
    val configured get() = key.isNotBlank()

    fun poster(path: String?, size: String = "w342"): String? =
        path?.let { "https://image.tmdb.org/t/p/$size$it" }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            if (!configured) throw IOException("Kein TMDB-Schlüssel eingebaut")
            val bearer = key.length > 40 // langer "Lese-Token" statt kurzem API-Key
            val q = LinkedHashMap(params)
            q["language"] = "de-DE"
            if (!bearer) q["api_key"] = key
            val query = q.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val conn = URL("https://api.themoviedb.org/3/$path?$query").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            if (bearer) conn.setRequestProperty("Authorization", "Bearer $key")
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("Filmdatenbank antwortet mit Fehler $code")
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }

    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

    private fun parse(o: JSONObject, forcedType: String? = null): TmdbItem? {
        val type = forcedType ?: o.str("media_type")
        if (type != "movie" && type != "tv") return null
        val title = if (type == "movie") o.str("title") else o.str("name")
        val date = if (type == "movie") o.str("release_date") else o.str("first_air_date")
        if (title.isBlank()) return null
        return TmdbItem(
            id = o.optInt("id"),
            type = type,
            title = title,
            year = date.take(4),
            poster = o.str("poster_path").takeIf { it.isNotBlank() },
            overview = o.str("overview"),
            vote = o.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it },
        )
    }

    private fun JSONArray?.items(forcedType: String? = null): List<TmdbItem> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let { parse(it, forcedType) } }.distinctBy { it.key }
    }

    suspend fun search(query: String): List<TmdbItem> =
        get("search/multi", mapOf("query" to query, "include_adult" to "false"))
            .optJSONArray("results").items()

    suspend fun trending(): List<TmdbItem> =
        get("trending/all/week").optJSONArray("results").items()

    suspend fun recommendations(type: String, id: Int): List<TmdbItem> =
        get("$type/$id/recommendations").optJSONArray("results").items(type)

    suspend fun details(type: String, id: Int): TmdbDetail {
        val o = get("$type/$id", mapOf("append_to_response" to "watch/providers"))
        val item = parse(o, type) ?: throw IOException("Titel nicht gefunden")
        val genres = o.optJSONArray("genres")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.str("name")?.takeIf { n -> n.isNotBlank() } }
        } ?: emptyList()
        val runtime = if (type == "movie") o.optInt("runtime")
        else o.optJSONArray("episode_run_time")?.optInt(0) ?: 0
        val de = o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("DE")
        val providers = de?.optJSONArray("flatrate")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.str("provider_name")?.takeIf { n -> n.isNotBlank() } }
        } ?: emptyList()
        return TmdbDetail(
            item = item,
            genres = genres,
            seasons = if (type == "tv") o.optInt("number_of_seasons") else 0,
            runtime = runtime,
            providers = providers.distinct(),
            tagline = o.str("tagline"),
        )
    }
}
