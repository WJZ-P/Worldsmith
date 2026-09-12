package com.wjz.worldsmith.core.prompt

import com.wjz.worldsmith.core.content.CustomCreatureValidator
import com.wjz.worldsmith.core.content.QuestValidation
import com.wjz.worldsmith.core.model.PromptTemplateRef
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.structure.StructureArchitectureValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Check the actual packaged MD loaded by MCP, without executing tools or creating an authoring host. */
class ContractConsistencyTest {
    private val repository = ClasspathPromptTemplateRepository()
    private fun contract(id: String) = repository.load(PromptTemplateRef("contract/$id")).systemPrompt

    @Test fun `publication contracts use the current nine-module format and preserve legacy boundaries`() {
        assertEquals(9, WorldContentBundleIO.REQUIRED_MODULES.size)
        val stalePublication = Regex("Published worlds use bundle format [345]|New writes use format [345]|(?m)^# .*bundle format [345]")
        for (id in listOf("theme", "architecture", "items")) {
            val text = contract(id)
            assertTrue("format ${WorldContentBundleIO.FORMAT_VERSION}" in text, "$id must name the current bundle format")
            assertTrue("nine typed modules" in text, "$id must not retain the old seven/eight-module publication promise")
            assertTrue(Regex("Formats 3/4(?:/5)? remain\\s+read-only").containsMatchIn(text), "$id must preserve the explicit legacy boundary")
            assertFalse(stalePublication.containsMatchIn(text), "$id advertises an obsolete current publication format")
        }
        for (id in listOf("theme", "architecture")) {
            val text = contract(id)
            WorldContentBundleIO.REQUIRED_MODULES.forEach { module -> assertTrue("`$module`" in text, "$id omits $module") }
        }
        assertTrue("the frozen format-${WorldContentBundleIO.FORMAT_VERSION} pack" in contract("theme"))
        for (id in listOf("theme", "items", "quests"))
            assertTrue(Regex("Formats 3/4/5 remain\\s+read-only").containsMatchIn(contract(id)), "$id must include format-5 restoration")
    }

    @Test fun `theme and gameplay contracts agree on installed quests achievements and bounded bosses`() {
        val theme = contract("theme")
        assertTrue("The `quests` module is installed" in theme)
        assertTrue("`achievements` is not installed" in theme)
        assertFalse(Regex("`quests` and `achievements` are future\\s+modules").containsMatchIn(theme))
        val quests = contract("quests")
        assertTrue("quests (0..${QuestValidation.MAX_QUESTS})" in quests)
        assertTrue("exactly one root" in quests)
        assertTrue("at most one successor" in quests)
        assertTrue("kill_creature" in quests && "deliver_item" in quests)
        val creatures = contract("creatures")
        assertTrue("${CustomCreatureValidator.MAX_CREATURES} definitions" in creatures)
        assertTrue("ground-melee" in creatures && "2..3 phase" in creatures)
        assertTrue("current bundle format ${WorldContentBundleIO.FORMAT_VERSION}" in creatures)
    }

    @Test fun `starter scale advice is not advertised as an engine maximum`() {
        val general = ClasspathStyleCatalog(repository).fallback().body
        assertTrue("six to twelve is a starting-size suggestion, not a maximum" in general)
        assertTrue("contract/grand_world" in general)
        val architecture = contract("architecture")
        assertTrue("2..4 is a starting suggestion, not a maximum" in architecture)
        assertTrue("groups has ${StructureArchitectureValidator.MIN_GROUPS}..12 entries" in architecture)
        assertTrue("Minimum size/count checks are floors, never design targets" in architecture)
    }
}
