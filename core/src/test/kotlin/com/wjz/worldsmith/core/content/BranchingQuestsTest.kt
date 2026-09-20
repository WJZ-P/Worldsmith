package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.ability.AbilityValues
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.story.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BranchingQuestsTest {
    private fun quest(id: String, prerequisites: List<String> = emptyList()) = Quest(id, id, "A discovered chapter", prerequisites,
        listOf(QuestObjective.DeliverItem("minecraft:stick")))
    private fun branch(id: String) = quest(id, listOf("start")).copy(manualAccept = true, exclusiveGroup = "choice")
    private val fact = StoryFact("met_keeper", StoryFactScope.PLAYER, StoryFactType.BOOL, AbilityValues.bool(false))
    private val met = StoryCondition.Compare(StoryFactRef("met_keeper"), StoryComparison.EQ, AbilityValues.bool(true))

    @Test fun `multiple roots forks and any merges are a current schema2 DAG`() {
        val library = QuestLibrary(quests = listOf(quest("ending", listOf("left", "right")).copy(prerequisiteMode = QuestPrerequisiteMode.ANY),
            branch("left"), quest("start"), branch("right"), quest("side").copy(optional = true)))
        assertTrue(QuestValidation.validate(library).isEmpty())
        assertEquals(listOf("start", "left", "right", "ending", "side"), QuestValidation.ordered(library).map { it.id })
        assertEquals(library, WorldsmithJson.decode<QuestLibrary>(WorldsmithJson.encode(library)))
        assertTrue(QuestValidation.validate(library.copy(schemaVersion = 1)).any { it.code == "quests.schema" })
    }
    @Test fun `exclusive choices demand manual intent and all merge rejects contradictory prerequisites`() {
        val invalid = QuestLibrary(quests = listOf(quest("start"), branch("left"), branch("right"), quest("ending", listOf("left", "right"))))
        assertTrue(QuestValidation.validate(invalid).any { it.code == "quests.exclusive_prerequisites" })
        assertTrue(QuestValidation.validate(QuestLibrary(quests = listOf(quest("a").copy(exclusiveGroup = "route")))).any { it.code == "quests.branch_accept" })
        val indirect = invalid.copy(quests = listOf(quest("start"), branch("left"), branch("right"),
            quest("left_later", listOf("left")), quest("right_later", listOf("right")), quest("ending", listOf("left_later", "right_later"))))
        assertTrue(QuestValidation.validate(indirect).any { it.code == "quests.exclusive_prerequisites" })
        val sameRoute = QuestLibrary(quests = listOf(quest("a").copy(manualAccept = true, exclusiveGroup = "route"),
            quest("b", listOf("a")).copy(manualAccept = true, exclusiveGroup = "route")))
        assertTrue(QuestValidation.validate(sameRoute).any { it.code == "quests.branch_dependency" })
    }
    @Test fun `cycles duplicate prerequisites and all optional objective lists fail before native binding`() {
        assertTrue(QuestValidation.validate(QuestLibrary(quests = listOf(quest("a", listOf("b")), quest("b", listOf("a"))))).any { it.code == "quests.cycle" })
        assertTrue(QuestValidation.validate(QuestLibrary(quests = listOf(quest("a"), quest("b", listOf("a", "a"))))).any { it.code == "quests.prerequisite_count" })
        assertTrue(QuestValidation.validate(QuestLibrary(quests = listOf(quest("a").copy(objectives = listOf(QuestObjective.DeliverItem("minecraft:stick", optional = true)))))).any { it.code == "quests.required_objective" })
    }
    @Test fun `fact objectives use the shared condition serializer and semantic type checks`() {
        val library = QuestLibrary(quests = listOf(quest("greeting").copy(objectives = listOf(QuestObjective.Fact("Meet the keeper", met)),
            discoverWhen = StoryCondition.Always, onClaim = listOf(StoryFactChange(StoryFactRef("met_keeper"), AbilityValues.bool(true))))))
        val raw = WorldsmithJson.encode(library)
        assertTrue(raw.contains("\"fact\"")); assertTrue(raw.contains("\"compare\""))
        assertEquals(library, WorldsmithJson.decode<QuestLibrary>(raw))
        assertTrue(QuestValidation.validateStory(library, StoryLibrary(facts = listOf(fact))).isEmpty())
        assertFalse(QuestValidation.validateStory(library, StoryLibrary()).isEmpty())
        val wrong = library.copy(quests = library.quests.map { it.copy(onClaim = listOf(StoryFactChange(StoryFactRef("met_keeper"), AbilityValues.number(2.0), StoryChangeMode.ADD))) })
        assertFalse(QuestValidation.validateStory(wrong, StoryLibrary(facts = listOf(fact))).isEmpty())
    }
    @Test fun `freezing owns nested condition and transition collections`() {
        val conditions = mutableListOf<StoryCondition>(met)
        val transitions = mutableListOf(StoryFactChange(StoryFactRef("met_keeper"), AbilityValues.bool(true)))
        val library = QuestValidation.freeze(QuestLibrary(quests = listOf(quest("a").copy(discoverWhen = StoryCondition.All(conditions),
            objectives = listOf(QuestObjective.Fact("Meet", StoryCondition.All(conditions))), onClaim = transitions))))
        conditions.clear(); transitions.clear()
        assertEquals(1, (library.quests.single().discoverWhen as StoryCondition.All).conditions.size)
        assertEquals(1, ((library.quests.single().objectives.single() as QuestObjective.Fact).condition as StoryCondition.All).conditions.size)
        assertEquals(1, library.quests.single().onClaim.size)
    }
}
