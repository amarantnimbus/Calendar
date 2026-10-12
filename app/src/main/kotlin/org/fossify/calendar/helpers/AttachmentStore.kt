package org.fossify.calendar.helpers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Attachments of an event: only references (URIs given by the system file picker) kept on this phone.
 * They are not synchronized, so the partner's phone does not see them.
 */
object AttachmentStore {
    data class Attachment(val uri: String, val name: String)

    fun list(config: Config, key: String): List<Attachment> {
        return try {
            val array = JSONObject(config.attachmentsJson).optJSONArray(key) ?: return emptyList()
            (0 until array.length()).map {
                val item = array.getJSONObject(it)
                Attachment(item.getString("uri"), item.optString("name", item.getString("uri")))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(config: Config, key: String, items: List<Attachment>) {
        val root = try {
            JSONObject(config.attachmentsJson)
        } catch (e: Exception) {
            JSONObject()
        }

        if (items.isEmpty()) {
            root.remove(key)
        } else {
            val array = JSONArray()
            items.forEach { array.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
            root.put(key, array)
        }

        config.attachmentsJson = root.toString()
    }

    fun add(config: Config, key: String, attachment: Attachment) {
        val items = list(config, key).filter { it.uri != attachment.uri } + attachment
        save(config, key, items)
    }

    fun remove(config: Config, key: String, uri: String) {
        save(config, key, list(config, key).filter { it.uri != uri })
    }
}
