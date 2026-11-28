package com.atrishub.atrisstream.core.sourceengine.flixcloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decodes SvelteKit `__data.json` devalue payloads into standard [JsonObject] trees.
 */
object SvelteKitDevalueDecoder {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parses a SvelteKit `__data.json` response body (single JSON or NDJSON chunks)
     * and returns the merged root [JsonObject] across all data nodes.
     */
    fun decodeMergedNodes(rawBody: String): JsonObject {
        val merged = linkedMapOf<String, JsonElement>()
        val whole = runCatching { json.parseToJsonElement(rawBody.trim()) }.getOrNull() as? JsonObject
        val objects = if (whole != null) {
            listOf(whole)
        } else {
            rawBody.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line -> runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject }
                .toList()
        }

        for (root in objects) {
            val nodes = root["nodes"] as? JsonArray
            if (nodes != null) {
                for (nodeEl in nodes) {
                    val nodeObj = nodeEl as? JsonObject ?: continue
                    val dataArr = nodeObj["data"] as? JsonArray ?: continue
                    val resolved = resolveDataArray(dataArr) as? JsonObject ?: continue
                    for ((k, v) in resolved) {
                        merged[k] = v
                    }
                }
            } else {
                val dataArr = root["data"] as? JsonArray
                if (dataArr != null) {
                    val resolved = resolveDataArray(dataArr) as? JsonObject
                    if (resolved != null) {
                        for ((k, v) in resolved) {
                            merged[k] = v
                        }
                    }
                } else {
                    for ((k, v) in root) {
                        merged[k] = v
                    }
                }
            }
        }
        return JsonObject(merged)
    }

    /**
     * Resolves a SvelteKit devalue flat [data] array starting at index 0.
     */
    fun resolveDataArray(data: JsonArray): JsonElement {
        if (data.isEmpty()) return JsonNull
        return resolveAt(data, 0, mutableSetOf())
    }

    private fun resolveAt(
        data: JsonArray,
        index: Int,
        visiting: MutableSet<Int>
    ): JsonElement {
        if (index < 0 || index >= data.size) return JsonNull
        if (!visiting.add(index)) return JsonNull

        val result = when (val raw = data[index]) {
            is JsonNull -> JsonNull
            is JsonPrimitive -> raw
            is JsonObject -> {
                val resolvedMap = linkedMapOf<String, JsonElement>()
                for ((k, v) in raw) {
                    val refIdx = (v as? JsonPrimitive)?.intOrNull
                    resolvedMap[k] = if (refIdx != null) {
                        resolveAt(data, refIdx, visiting)
                    } else {
                        v
                    }
                }
                JsonObject(resolvedMap)
            }
            is JsonArray -> {
                // Check for devalue tagged tuples like ["Date", "..."] or ["Set", ...]
                val firstStr = (raw.firstOrNull() as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                if (firstStr == "Date" && raw.size == 2) {
                    raw[1]
                } else {
                    val resolvedList = raw.map { item ->
                        val refIdx = (item as? JsonPrimitive)?.intOrNull
                        if (refIdx != null) {
                            resolveAt(data, refIdx, visiting)
                        } else {
                            item
                        }
                    }
                    JsonArray(resolvedList)
                }
            }
        }

        visiting.remove(index)
        return result
    }
}
