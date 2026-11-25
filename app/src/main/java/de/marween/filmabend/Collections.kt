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
    val lead: String = "",   // Darsteller der Hauptfigur, z. B. „Sean Connery“
    val director: String = "",
    val note: String = "",   // Besonderheit, z. B. „ohne James Bond“
) {
    fun toMap() = mapOf("wd" to wd, "title" to title, "year" to year, "date" to date, "key" to key,
        "lead" to lead, "director" to director, "note" to note)

    companion object {
        fun from(m: Map<*, *>) = CollItem(
            m["wd"] as? String ?: "", m["title"] as? String ?: "", m["year"] as? String ?: "",
            m["date"] as? String ?: "", m["key"] as? String ?: "",
            m["lead"] as? String ?: "", m["director"] as? String ?: "", m["note"] as? String ?: ""
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
    val role: String = "",       // durchgehende Hauptfigur, z. B. „James Bond“
    val version: Int = 1,
) {
    companion object {
        fun from(d: DocumentSnapshot): Coll? {
            val name = d.getString("name") ?: return null
            val items = (d.get("items") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(CollItem::from) } ?: emptyList()
            return Coll(d.id, name, d.getString("source") ?: "", items.sortedBy { it.date }, d.getLong("createdAt") ?: 0,
                d.getString("role") ?: "", (d.getLong("version") ?: 1).toInt())
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

    /** Ergebnis: Filme (chronologisch) + die durchgehende Hauptfigur der Reihe (falls es eine gibt). */
    data class Result(val items: List<CollItem>, val role: String)

    suspend fun collection(source: String): Result {
        val list = films(source)
        if (list.isEmpty()) return Result(list, "")
        return runCatching { withDetails(list) }.getOrElse { Result(list, "") }
    }

    private fun sparql(q: String) = "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(q, "UTF-8")

    /**
     * Regie und Rollen nachladen: Welche Figur zieht sich durch die Reihe (z. B. James Bond)
     * und wer spielt sie in welchem Film? Filme ohne diese Figur bekommen einen Hinweis.
     */
    private suspend fun withDetails(list: List<CollItem>): Result {
        val directors = HashMap<String, MutableList<String>>()
        val roles = HashMap<String, MutableList<Pair<String, String>>>() // film -> (Figur, Darsteller)
        for (chunk in list.chunked(120)) {
            val values = chunk.joinToString(" ") { "wd:${it.wd}" }
            val q = """
                SELECT ?film ?kind ?p ?who ?char ?charLabel WHERE {
                  VALUES ?film { $values }
                  {
                    BIND("d" AS ?kind)
                    ?film wdt:P57 ?p .
                    ?p rdfs:label ?who FILTER(LANG(?who) IN ("de", "en"))
                  } UNION {
                    BIND("r" AS ?kind)
                    ?film p:P161 ?st . ?st ps:P161 ?p ; pq:P453 ?char .
                    ?p rdfs:label ?who FILTER(LANG(?who) IN ("de", "en"))
                    ?char rdfs:label ?charLabel FILTER(LANG(?charLabel) = "de" || LANG(?charLabel) = "en")
                  }
                }
            """.trimIndent()
            val rows = JSONObject(get(sparql(q))).getJSONObject("results").getJSONArray("bindings")
            // Namen: Deutsch bevorzugt, sonst Englisch
            val names = HashMap<String, String>()
            fun name(uri: String, label: String, lang: String) {
                if (uri.isBlank() || label.isBlank()) return
                if (lang == "de" || !names.containsKey(uri)) names[uri] = label
            }
            val raw = ArrayList<Triple<String, String, Pair<String, String>>>() // kind, film, (person, char)
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                fun v(k: String) = r.optJSONObject(k)?.optString("value") ?: ""
                fun lang(k: String) = r.optJSONObject(k)?.optString("xml:lang") ?: ""
                name(v("p"), v("who"), lang("who"))
                name(v("char"), v("charLabel"), lang("charLabel"))
                raw += Triple(v("kind"), v("film").substringAfterLast('/'), v("p") to v("char"))
            }
            for ((kind, film, pc) in raw.distinct()) {
                val who = names[pc.first] ?: continue
                when (kind) {
                    "d" -> directors.getOrPut(film) { mutableListOf() }.let { if (who !in it) it += who }
                    "r" -> {
                        val ch = names[pc.second] ?: continue
                        val l = roles.getOrPut(film) { mutableListOf() }
                        if ((ch to who) !in l) l += ch to who
                    }
                }
            }
        }
        // Durchgehende Hauptfigur: kommt in den meisten Filmen vor (mind. 3 und mind. 40 %)
        val charCount = roles.values.flatMap { l -> l.map { it.first }.distinct() }.groupingBy { it }.eachCount()
        val main = charCount.entries.filter { it.value >= 3 && it.value >= list.size * 0.4 }.maxByOrNull { it.value }?.key ?: ""
        // Lohnt sich nur, wenn die Figur von mehreren Darstellern gespielt wird
        val actors = if (main.isBlank()) emptySet() else
            roles.values.mapNotNull { l -> l.firstOrNull { it.first == main }?.second }.toSet()
        val role = if (actors.size >= 2) main else ""
        val items = list.map { f ->
            val d = directors[f.wd].orEmpty().distinct().take(2).joinToString(", ")
            val lead = if (role.isBlank()) "" else roles[f.wd]?.filter { it.first == role }?.map { it.second }?.distinct()?.joinToString(" / ") ?: ""
            val note = when {
                role.isNotBlank() && lead.isBlank() && roles.containsKey(f.wd) -> "Ohne $role"
                else -> ""
            }
            f.copy(director = d, lead = lead, note = note)
        }
        return Result(items, role)
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
