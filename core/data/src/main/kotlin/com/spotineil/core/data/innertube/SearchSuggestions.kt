package com.spotineil.core.data.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The texts of YouTube Music's search suggestions ("searchSuggestionRenderer"), in order and without
 * repeats. Each is what the search will be for (its search endpoint's query, else its text).
 */
internal fun suggestionTexts(root: JsonElement, limit: Int = 8): List<String> {
    val found = LinkedHashSet<String>()
    fun visit(e: JsonElement) {
        if (found.size >= limit) return
        when (e) {
            is JsonObject -> {
                val renderer = e["searchSuggestionRenderer"] as? JsonObject
                if (renderer != null) {
                    val query = ((((renderer["navigationEndpoint"] as? JsonObject)?.get("searchEndpoint") as? JsonObject)
                        ?.get("query")) as? JsonPrimitive)?.contentOrNull
                    val runs = ((renderer["suggestion"] as? JsonObject)?.get("runs") as? JsonArray)
                        ?.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    (query ?: runs)?.trim()?.takeIf { it.isNotEmpty() }?.let { found += it }
                    return
                }
                e.values.forEach(::visit)
            }
            is JsonArray -> e.forEach(::visit)
            else -> Unit
        }
    }
    visit(root)
    return found.toList()
}
