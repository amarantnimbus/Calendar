package org.fossify.calendar.helpers

import android.content.Context
import org.fossify.calendar.extensions.config
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to the FacturaScripts API (only when the "work features" switch is on and the address and
 * key are filled in Settings). Address and key live only in the phone settings.
 *
 * All the functions here block, so call them from a background thread.
 */
object FacturaScripts {
    data class Project(val id: Long, val name: String)

    data class Entry(
        val uid: String,
        val projectId: Long,
        val date: String,
        val title: String,
        val text: String,
        val type: String = "altres"
    )

    /** The kinds of diary entry, in the order they are offered. The key is what is stored. */
    val TYPES = listOf(
        "client" to org.fossify.calendar.R.string.fs_type_client,
        "ajuntament" to org.fossify.calendar.R.string.fs_type_ajuntament,
        "obra" to org.fossify.calendar.R.string.fs_type_obra,
        "coaib" to org.fossify.calendar.R.string.fs_type_coaib,
        "admin" to org.fossify.calendar.R.string.fs_type_admin,
        "tecnics" to org.fossify.calendar.R.string.fs_type_tecnics,
        "altres" to org.fossify.calendar.R.string.fs_type_altres
    )

    fun typeLabelRes(key: String?): Int? = TYPES.firstOrNull { it.first == key }?.second

    private val sendLock = Any()

    private const val MARKER_START = "[[FS:"
    private const val MARKER_END = "]]"

    fun isConfigured(context: Context): Boolean {
        val config = context.config
        return config.workFeatures && config.fsUrl.isNotBlank() && config.fsApiKey.isNotBlank()
    }

    // ---- project marker kept at the end of the event description ----

    fun withMarker(text: String, project: Project?, type: String? = null): String {
        val clean = stripMarker(text).trimEnd()
        return if (project == null) {
            clean
        } else {
            val name = project.name.replace("]", ")").replace("|", "/")
            val typePart = if (type.isNullOrEmpty()) "" else "|$type"
            "$clean\n$MARKER_START${project.id}|$name$typePart$MARKER_END"
        }
    }

    fun stripMarker(description: String): String {
        val start = description.lastIndexOf(MARKER_START)
        return if (start >= 0 && description.trimEnd().endsWith(MARKER_END)) {
            description.substring(0, start).trimEnd()
        } else {
            description
        }
    }

    private fun markerParts(description: String): List<String>? {
        val start = description.lastIndexOf(MARKER_START)
        if (start < 0 || !description.trimEnd().endsWith(MARKER_END)) {
            return null
        }

        return description.trimEnd()
            .removeSuffix(MARKER_END)
            .substring(start + MARKER_START.length)
            .split('|')
    }

    fun markerProject(description: String): Project? {
        val parts = markerParts(description) ?: return null
        val id = parts[0].toLongOrNull() ?: return null
        val name = if (parts.size >= 2) parts[1] else id.toString()
        return Project(id, name)
    }

    /** The type kept in the marker, or null when the event has none (older events). */
    fun markerType(description: String): String? {
        val parts = markerParts(description) ?: return null
        return if (parts.size >= 3 && TYPES.any { it.first == parts[2] }) parts[2] else null
    }

    // ---- HTTP ----

    private fun baseUrl(context: Context): String {
        var url = context.config.fsUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url
    }

    private fun request(context: Context, method: String, path: String, form: Map<String, String>? = null): Pair<Int, String> {
        val connection = URL(baseUrl(context) + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8000
            connection.readTimeout = 15000
            connection.setRequestProperty("Token", context.config.fsApiKey.trim())
            if (form != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = form.entries.joinToString("&") {
                    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                }
                connection.outputStream.use { it.write(body.toByteArray()) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            connection.disconnect()
        }
    }

    // ---- projects ----

    /** Returns the projects, or null (with the reason in [lastError]) when it was not possible. */
    @Volatile
    var lastError = ""

    fun fetchProjects(context: Context): List<Project>? {
        return try {
            val (code, body) = request(context, "GET", "/api/3/proyectos?limit=1000")
            if (code !in 200..299) {
                lastError = "HTTP $code"
                return null
            }

            val array = JSONArray(body)
            val projects = ArrayList<Project>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optLong("idproyecto", -1L)
                val name = item.optString("nombre", "").ifEmpty { item.optString("titulo", "") }
                if (id != -1L && name.isNotEmpty()) {
                    projects.add(Project(id, name))
                }
            }

            projects.sortBy { it.name.lowercase() }
            val cache = JSONArray()
            projects.forEach { cache.put(JSONObject().put("id", it.id).put("name", it.name)) }
            context.config.fsProjectsCache = cache.toString()
            lastError = ""
            projects
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    fun cachedProjects(context: Context): List<Project> {
        return try {
            val array = JSONArray(context.config.fsProjectsCache.ifEmpty { "[]" })
            (0 until array.length()).map {
                val item = array.getJSONObject(it)
                Project(item.getLong("id"), item.getString("name"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- diary ----

    private fun entryToJson(entry: Entry) = JSONObject()
        .put("uid", entry.uid)
        .put("projectId", entry.projectId)
        .put("date", entry.date)
        .put("title", entry.title)
        .put("text", entry.text)
        .put("type", entry.type)

    private fun entryFromJson(item: JSONObject) = Entry(
        item.getString("uid"),
        item.getLong("projectId"),
        item.getString("date"),
        item.getString("title"),
        item.getString("text"),
        item.optString("type", "altres").ifEmpty { "altres" }
    )

    // one at a time, so saving the same event twice quickly cannot create two entries
    private fun send(context: Context, entry: Entry): Boolean = synchronized(sendLock) {
        sendNow(context, entry)
    }

    private fun sendNow(context: Context, entry: Entry): Boolean {
        val form = mapOf(
            "uid" to entry.uid,
            "idproyecto" to entry.projectId.toString(),
            "fecha" to entry.date,
            "titulo" to entry.title,
            "descripcion" to entry.text,
            "tipo" to entry.type
        )

        val lookup = request(
            context,
            "GET",
            "/api/3/diarioproyectos?filter[uid]=" + URLEncoder.encode(entry.uid, "UTF-8")
        )
        if (lookup.first !in 200..299) {
            lastError = "HTTP ${lookup.first}"
            return false
        }

        val existing = JSONArray(lookup.second)
        val result = if (existing.length() > 0) {
            val id = existing.getJSONObject(0).get("id").toString()
            request(context, "PUT", "/api/3/diarioproyectos/$id", form)
        } else {
            request(context, "POST", "/api/3/diarioproyectos", form)
        }

        if (result.first in 200..299) {
            lastError = ""
            return true
        }
        lastError = "HTTP ${result.first}"
        return false
    }

    /** Sends the entry now. If the server cannot be reached it is kept to be sent later. */
    fun push(context: Context, entry: Entry) {
        if (!isConfigured(context)) {
            return
        }

        val ok = try {
            send(context, entry)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            false
        }

        if (!ok) {
            queue(context, entry)
        }
    }

    @Synchronized
    private fun queue(context: Context, entry: Entry) {
        val array = try {
            JSONArray(context.config.fsPendingQueue.ifEmpty { "[]" })
        } catch (e: Exception) {
            JSONArray()
        }

        // a newer version of the same event replaces the waiting one
        val kept = JSONArray()
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).optString("uid") != entry.uid) {
                kept.put(array.getJSONObject(i))
            }
        }
        kept.put(entryToJson(entry))
        context.config.fsPendingQueue = kept.toString()
    }

    /** Tries to send what is waiting. Call it when the app opens. */
    @Synchronized
    fun flush(context: Context) {
        if (!isConfigured(context)) {
            return
        }

        val array = try {
            JSONArray(context.config.fsPendingQueue.ifEmpty { "[]" })
        } catch (e: Exception) {
            return
        }

        val remaining = JSONArray()
        for (i in 0 until array.length()) {
            val entry = try {
                entryFromJson(array.getJSONObject(i))
            } catch (e: Exception) {
                continue
            }

            val ok = try {
                send(context, entry)
            } catch (e: Exception) {
                false
            }

            if (!ok) {
                remaining.put(array.getJSONObject(i))
            }
        }
        context.config.fsPendingQueue = remaining.toString()
    }
}
