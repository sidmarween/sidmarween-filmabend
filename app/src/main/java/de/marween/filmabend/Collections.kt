package de.marween.filmabend

import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ein Film innerhalb einer Sammlung (chronologisch). */
data class CollItem(
    val wd: String,          // Wikidata-ID, z. B. Q123
    val title: String,
    val year: String,
    val date: String,        // yyyy-MM-dd zum Sortieren
    val key: String = "",    // Bibliotheks-Schlüssel, sobald einmal abgehakt
) {
    fun toMap() = mapOf("wd" to wd, "title" to title, "year" to year, "date" to date, "key" to key)

    companion object {
        fun from(m: Map<*, *>) = CollItem(
            m["wd"] as? String ?: "", m["title"] as? String ?: "", m["year"] as? String ?: "",
            m["date"] as? String ?: "", m["key"] as? String ?: ""
        )
    }
}

/** Eine Sammlung, z. B. „James Bond“ oder „Disney-Meisterwerke“ – für beide sichtbar. */
data class Coll(
    val id: String,
    val name: String,
    val source: String,      // Wikidata-ID der Reihe / des Studios
    val items: List<CollItem>,
    val createdAt: Long,
) {
    companion object {
        fun from(d: DocumentSnapshot): Coll? {
            val name = d.getString("name") ?: return null
            val items = (d.get("items") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(CollItem::from) } ?: emptyList()
            return Coll(d.id, name, d.getString("source") ?: "", items.sortedBy { it.date }, d.getLong("createdAt") ?: 0)
        }
    }
}

/** Treffer bei der Suche nach einer Reihe / einem Studio. */
data class WdHit(val id: String, val label: String, val description: String)

/**
 * Wikidata (frei, ohne Schlüssel): Filmreihen, Franchises und Studios mit allen Filmen
 * und Erscheinungsdaten – daraus entstehen die chronologischen Sammlungen.
 */
object Wikidata {
    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "Filmabend-App/1.1 (Android; private Film-Liste)")
        c.setRequestProperty("Accept", "application/sparql-results+json, application/json")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("Wikidata antwortet nicht (${c.responseCode})")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    suspend fun search(q: String): List<WdHit> {
        val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&type=item&limit=12" +
            "&language=de&uselang=de&search=" + URLEncoder.encode(q, "UTF-8")
        val arr = JSONObject(get(url)).optJSONArray("search") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            WdHit(it.optString("id"), it.optString("label"), it.optString("description"))
        }
    }

    /** Alle Filme einer Reihe / eines Franchise / eines Studios, nach Erscheinungsdatum. */
    suspend fun films(source: String): List<CollItem> {
        val q = """
            SELECT ?film (MIN(?date) AS ?d) (SAMPLE(?de) AS ?lde) (SAMPLE(?en) AS ?len) WHERE {
              { ?film wdt:P179 wd:$source } UNION { ?film wdt:P8345 wd:$source } UNION
              { ?film wdt:P272 wd:$source } UNION { ?film wdt:P361 wd:$source } UNION { ?film wdt:P750 wd:$source }
              ?film wdt:P31/wdt:P279?/wdt:P279? wd:Q11424 .
              OPTIONAL { ?film wdt:P577 ?date }
              OPTIONAL { ?film rdfs:label ?de FILTER(LANG(?de) = "de") }
              OPTIONAL { ?film rdfs:label ?en FILTER(LANG(?en) = "en") }
            } GROUP BY ?film ORDER BY ?d LIMIT 400
        """.trimIndent()
        val url = "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(q, "UTF-8")
        val rows = JSONObject(get(url)).getJSONObject("results").getJSONArray("bindings")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.mapNotNull { r ->
            fun v(k: String) = r.optJSONObject(k)?.optString("value") ?: ""
            val title = v("lde").ifBlank { v("len") }
            if (title.isBlank() || title.matches(Regex("Q\\d+"))) return@mapNotNull null
            val date = v("d").take(10)
            CollItem(v("film").substringAfterLast('/'), title, date.take(4), date.ifBlank { "9999" })
        }.distinctBy { it.wd }.sortedBy { it.date }
    }
}
