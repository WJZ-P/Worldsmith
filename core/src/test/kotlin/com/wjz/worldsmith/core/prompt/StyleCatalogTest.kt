package com.wjz.worldsmith.core.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

class StyleCatalogTest {
    private val catalog = ClasspathStyleCatalog()

    @Test
    fun `the fallback method is always available and carries a summary`() {
        val fallback = catalog.fallback()

        assertEquals(StyleCatalog.FALLBACK_ID, fallback.summary.id)
        assertTrue(fallback.summary.name.isNotBlank())
        assertTrue(fallback.summary.description.isNotBlank())
        assertTrue(fallback.body.isNotBlank())
        // The front matter is the index entry, not part of what an agent reads.
        assertTrue(fallback.body.startsWith("#"), "the body should begin after the front matter")
    }

    @Test
    fun `a listed style resolves and an unlisted one does not`() {
        catalog.list().forEach { summary ->
            assertNotNull(catalog.load(summary.id), "${summary.id} is listed but cannot be read")
            assertTrue(summary.description.isNotBlank())
        }

        assertNull(catalog.load("no_such_style"))
        // Reachable by name so a run can ask for it deliberately, but never
        // offered as a style: it is what to do when no style matched.
        assertNotNull(catalog.load(StyleCatalog.FALLBACK_ID))
        assertTrue(catalog.list().none { it.id == StyleCatalog.FALLBACK_ID })
    }

    @Test
    fun `every style file on disk is reachable through the index`() {
        val index = javaClass.classLoader.getResource("prompts/style/index.json")
        // Only meaningful while resources are an exploded directory, which is
        // where styles are added; inside a jar the index is all there is.
        if (index?.protocol != "file") {
            return
        }
        val directory = Path.of(index.toURI()).parent
        val onDisk = Files.list(directory).use { paths ->
            paths.filter { it.extension == "md" }
                .map { it.nameWithoutExtension.removeSuffix(".system") }
                .toList()
                .toSet()
        }

        val reachable = catalog.list().map { it.id }.toSet() + StyleCatalog.FALLBACK_ID

        assertEquals(
            emptySet<String>(),
            onDisk - reachable,
            "a style file that is not in the index is one no agent can ever choose",
        )
    }

    @Test
    fun `a style without front matter is rejected rather than listed blank`() {
        val bodyOnly = object : PromptTemplateRepository {
            override fun load(ref: com.wjz.worldsmith.core.model.PromptTemplateRef) =
                PromptTemplate(ref, "# Windswept\n\nA style with no summary.")
        }

        // Blank summaries would make the index useless in exactly the way that
        // is hardest to notice: every style still lists, and none says anything.
        assertThrows(IllegalArgumentException::class.java) {
            ClasspathStyleCatalog(bodyOnly).fallback()
        }
    }
}
