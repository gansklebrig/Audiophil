package de.eq.app

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Ergebnis eines eingelesenen parametrischen EQ (AutoEq / Equalizer APO). */
data class ParsedEq(val preamp: Float, val filters: List<PFilter>)

/**
 * Zugriff auf die AutoEq-Datenbank (github.com/jaakkopasanen/AutoEq):
 * Korrekturkurven für tausende Kopfhörer, als parametrischer EQ.
 */
object AutoEq {
    private const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    private const val INDEX_URL = BASE + "INDEX.md"
    private const val CACHE_DAYS = 14L

    /** Ein Kopfhörer im Index: Name, Messquelle und Ordner (relativ zu results/). */
    data class Entry(val name: String, val source: String, val path: String)

    private val indexLine = Regex("""^- \[(.+)]\(\./(.+)\) by (.+)$""")

    fun parseIndex(md: String): List<Entry> {
        val list = md.lineSequence().mapNotNull { line ->
            val m = indexLine.find(line.trim()) ?: return@mapNotNull null
            Entry(m.groupValues[1], m.groupValues[3], decode(m.groupValues[2]))
        }.toList()
        // Bevorzugte Messquellen zuerst (oratory1990 und crinacle gelten als besonders genau)
        return list.sortedWith(compareBy({ it.name.lowercase() }, { rank(it.source) }))
    }

    private fun rank(source: String) = when {
        source.startsWith("oratory1990") -> 0
        source.startsWith("crinacle") -> 1
        else -> 2
    }

    /** Adresse der „ParametricEQ.txt“ eines Kopfhörers. */
    fun parametricUrl(e: Entry): String {
        val folder = e.path.substringAfterLast('/')
        return BASE + e.path.split('/').joinToString("/") { encode(it) } + "/" + encode("$folder ParametricEQ.txt")
    }

    /** Index laden – aus dem Zwischenspeicher, wenn er jünger als 14 Tage ist. */
    fun loadIndex(context: Context): List<Entry> {
        val cache = File(context.filesDir, "autoeq_index.md")
        val fresh = cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_DAYS * 24 * 3600 * 1000
        if (!fresh) {
            try {
                cache.writeText(download(INDEX_URL))
            } catch (e: IOException) {
                if (!cache.exists()) throw e   // ohne Netz: alten Stand verwenden, falls vorhanden
            }
        }
        return parseIndex(cache.readText())
    }

    fun download(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 15000
            con.readTimeout = 30000
            con.setRequestProperty("User-Agent", "EqualizerApp")
            if (con.responseCode != 200) throw IOException("Server antwortet mit ${con.responseCode}")
            return con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            con.disconnect()
        }
    }

    private val preampRe = Regex("""Preamp:\s*([-+]?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val filterRe = Regex(
        """Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+([\d.]+)\s*Hz(?:\s+Gain\s+([-+]?[\d.]+)\s*dB)?(?:\s+Q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Liest das Equalizer-APO-Format, das AutoEq verwendet:
     *   Preamp: -5.3 dB
     *   Filter 1: ON LSC Fc 105 Hz Gain 13.6 dB Q 0.70
     */
    fun parseParametric(text: String): ParsedEq? {
        val preamp = preampRe.find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val filters = filterRe.findAll(text).mapNotNull { m ->
            val type = when (m.groupValues[2].uppercase()) {
                "PK", "PEQ", "PEAK" -> FilterType.PEAK
                "LS", "LSC", "LSQ" -> FilterType.LOW_SHELF
                "HS", "HSC", "HSQ" -> FilterType.HIGH_SHELF
                "LP", "LPQ" -> FilterType.LOW_PASS
                "HP", "HPQ" -> FilterType.HIGH_PASS
                else -> return@mapNotNull null
            }
            PFilter(
                type = type,
                freq = m.groupValues[3].toFloatOrNull() ?: return@mapNotNull null,
                gain = m.groupValues[4].toFloatOrNull() ?: 0f,
                q = m.groupValues[5].toFloatOrNull() ?: 0.707f,
                on = m.groupValues[1].equals("ON", ignoreCase = true),
            )
        }.toList()
        return if (filters.isEmpty()) null else ParsedEq(preamp, filters)
    }

    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
