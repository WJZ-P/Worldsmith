package com.wjz.worldsmith.core.prompt

import com.wjz.worldsmith.core.model.PromptTemplateRef
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.Serializable

/** What an agent learns about a style before deciding whether to read it. */
data class StyleSummary(
    val id: String,
    val name: String,
    val description: String,
)

/** A style's summary together with the body an agent reads once it has chosen. */
data class StyleGuide(
    val summary: StyleSummary,
    val body: String,
)

/**
 * The world styles an agent may consult, listed cheaply and read on demand.
 *
 * A style says what values make a world feel like something - what a Japanese
 * coast or a dead industrial plain is worth as a land ratio, a continent scale
 * and a palette. That is the one thing a model cannot supply for itself: it
 * knows what a torii is, and it has no way to know that this codebase renders an
 * inland sea at `continentScale` 0.6.
 *
 * Styles are the only prompt documents worth disclosing progressively. There
 * can be any number of them and a run uses at most one, so the index carries a
 * sentence each and the body is fetched only after a choice. The contracts are
 * the opposite case and stay eager: a run needs all of them, every time.
 *
 * The index is a file rather than a directory scan because the resources ship
 * inside a jar, where listing a directory is a different operation than reading
 * one. It is also the allowlist: an id that is not in it is never turned into a
 * resource path.
 */
interface StyleCatalog {
    /** Every listed style. Legitimately empty, which is what makes [fallback] the normal path. */
    fun list(): List<StyleSummary>

    /** Null rather than an exception, because an unknown id is an agent's mistake to be told about. */
    fun load(id: String): StyleGuide?

    /** The method to follow when no style fits, which is every run until styles are written. */
    fun fallback(): StyleGuide

    companion object {
        /** Not listed by [list]: it is the method used when nothing matches, not a style that could match. */
        const val FALLBACK_ID: String = "general"
    }
}

class ClasspathStyleCatalog(
    private val templates: PromptTemplateRepository = ClasspathPromptTemplateRepository(),
    private val classLoader: ClassLoader = ClasspathStyleCatalog::class.java.classLoader,
) : StyleCatalog {
    @Serializable
    private data class Index(val styles: List<String> = emptyList())

    private val ids: List<String> by lazy {
        val raw = classLoader.getResourceAsStream(INDEX_PATH)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Style index '$INDEX_PATH' was not found")
        val index = WorldsmithJson.format.decodeFromString(Index.serializer(), raw)
        index.styles.forEach { id ->
            require(ID.matches(id)) { "Style id '$id' must match ${ID.pattern}" }
            require(id != StyleCatalog.FALLBACK_ID) { "'$id' is the fallback method and must not be listed as a style" }
        }
        index.styles
    }

    override fun list(): List<StyleSummary> = ids.map { read(it).summary }

    override fun load(id: String): StyleGuide? =
        if (id == StyleCatalog.FALLBACK_ID || id in ids) read(id) else null

    override fun fallback(): StyleGuide = read(StyleCatalog.FALLBACK_ID)

    private fun read(id: String): StyleGuide {
        val text = templates.load(PromptTemplateRef("style/$id")).systemPrompt
        return parse(id, text)
    }

    private companion object {
        const val INDEX_PATH = "prompts/style/index.json"
        val ID = Regex("^[a-z0-9_]+$")
        const val FENCE = "---"

        /**
         * Splits the leading `--- name/description ---` block off the body.
         *
         * Hand-rolled rather than pulled from a YAML parser because the header
         * is two known keys, and because the failure mode that matters is a
         * missing header rather than an exotic one: a style whose description
         * never reaches the index is a style no agent will ever choose.
         */
        fun parse(id: String, text: String): StyleGuide {
            val normalized = text.replace("\r\n", "\n")
            require(normalized.startsWith("$FENCE\n")) { "Style '$id' must open with a '$FENCE' front-matter fence" }
            val end = normalized.indexOf("\n$FENCE", startIndex = FENCE.length)
            require(end >= 0) { "Style '$id' has an unterminated front-matter block" }

            val header = normalized.substring(FENCE.length + 1, end)
                .lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) null else line.take(separator).trim() to line.drop(separator + 1).trim()
                }
                .toMap()

            val name = header["name"]?.takeIf { it.isNotBlank() } ?: error("Style '$id' front matter has no name")
            val description = header["description"]?.takeIf { it.isNotBlank() }
                ?: error("Style '$id' front matter has no description")
            val body = normalized.substring(end + 1).removePrefix(FENCE).trim()
            require(body.isNotBlank()) { "Style '$id' has front matter but no body" }

            return StyleGuide(StyleSummary(id, name, description), body)
        }
    }
}
