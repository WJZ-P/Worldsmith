package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.Serializable

/** Persistent creative intent, not an executable quest or achievement definition. */
@Serializable
data class WorldTheme(
    val schemaVersion: Int = 1,
    val id: String = "main",
    val title: String = "",
    val premise: String = "",
    val playerRole: String = "",
    val worldRules: List<String> = emptyList(),
    val mainConflict: String = "",
    val beats: List<WorldNarrativeBeat> = emptyList(),
)

@Serializable
data class WorldNarrativeBeat(
    val id: String,
    val title: String,
    val description: String,
    val content: List<ContentKey> = emptyList(),
)

object WorldThemeValidation {
    fun validate(theme: WorldTheme): List<Diagnostic> = buildList {
        fun check(ok: Boolean, path: String, code: String, message: String) {
            if (!ok) add(Diagnostic(path, code, DiagnosticSeverity.ERROR, message))
        }
        check(theme.schemaVersion == 1, "schemaVersion", "THEME_SCHEMA_UNSUPPORTED", "Theme schema must be 1")
        check(localId(theme.id), "id", "THEME_ID_INVALID", "Use a lowercase local identifier, at most 64 characters")
        for ((name, text) in listOf("title" to theme.title, "premise" to theme.premise,
            "playerRole" to theme.playerRole, "mainConflict" to theme.mainConflict)) {
            check(text.isNotBlank() && text.length <= if (name == "title") 160 else 4096,
                name, "THEME_TEXT_INVALID", "Theme text must be nonempty and within its length budget")
        }
        check(theme.worldRules.size in 1..32 && theme.worldRules.all { it.isNotBlank() && it.length <= 1024 },
            "worldRules", "THEME_RULES_INVALID", "Declare 1..32 concise world rules (at most 1024 characters each)")
        check(theme.beats.size in 1..64, "beats", "THEME_BEATS_INVALID", "Declare 1..64 narrative beats linked to real content")
        check(theme.beats.map { it.id }.distinct().size == theme.beats.size, "beats", "THEME_BEAT_DUPLICATE", "Narrative beat IDs must be unique")
        theme.beats.take(64).forEachIndexed { i, beat ->
            check(localId(beat.id), "beats[$i].id", "THEME_BEAT_ID_INVALID", "Use a lowercase local identifier")
            check(beat.title.isNotBlank() && beat.title.length <= 160 && beat.description.isNotBlank() && beat.description.length <= 4096,
                "beats[$i]", "THEME_BEAT_TEXT_INVALID", "Narrative beat needs a title and bounded description")
            check(beat.content.size in 1..64 && beat.content.distinct().size == beat.content.size && beat.content.none { it.kind in setOf("theme", "narrative_beat") },
                "beats[$i].content", "THEME_BEAT_LINKS_INVALID", "Link 1..64 distinct concrete content definitions; narrative self-links do not anchor a world")
        }
    }
    private fun localId(id: String) = id.matches(Regex("[a-z0-9][a-z0-9_.-]{0,63}"))
}
