package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.analysis.PlayabilityReport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Findings first: the counts only matter as the evidence behind them. */
internal fun playabilityJson(report: PlayabilityReport): JsonObject = buildJsonObject {
    putJsonArray("findings") {
        report.findings.forEach { finding ->
            add(buildJsonObject {
                put("code", finding.code); put("message", finding.message)
                put("subjects", JsonArray(finding.subjects.map(::JsonPrimitive)))
            })
        }
    }
    put("mechanics", report.mechanics); put("distinctMechanics", report.distinctMechanics)
    putJsonObject("questObjectives") { report.questObjectives.forEach { (kind, count) -> put(kind, count) } }
    putJsonArray("creatureVerbs") {
        report.creatures.forEach { creature ->
            add(buildJsonObject { put("id", creature.id); put("verbs", JsonArray(creature.verbs.map(::JsonPrimitive))) })
        }
    }
    put("landmarkStructures", report.landmarkStructures); put("repeatingStructures", report.repeatingStructures)
    put("livingLinks", report.livingLinks)
    put("unusedItems", JsonArray(report.unusedItems.map(::JsonPrimitive)))
    put("soundedBiomes", report.soundedBiomes)
    put("advisory", true)
}

internal fun playabilityText(report: PlayabilityReport): String = buildString {
    if (report.findings.isEmpty()) append("No playability findings.")
    report.findings.forEach { appendLine(it.code + ": " + it.message) }
}.trim()
