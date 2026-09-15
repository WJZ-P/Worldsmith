package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QuestMechanicObjectiveTest {
    private fun library(id: String = "altar", count: Int = 1) = QuestLibrary(quests = listOf(
        Quest("ritual", "Ritual", "Observe the altar after a committed interaction", objectives = listOf(QuestObjective.ActivateMechanic(id, count))),
    ))

    @Test fun activationUsesTheActualPackKindDiscriminatorAndRoundTrips() {
        val value = library()
        val document = WorldsmithJson.encode(value)
        val objective = WorldsmithJson.format.parseToJsonElement(document).jsonObject.getValue("quests").jsonArray.single().jsonObject.getValue("objectives").jsonArray.single().jsonObject
        assertEquals("activate_mechanic", objective.getValue("kind").jsonPrimitive.content)
        assertEquals("altar", objective.getValue("mechanic").jsonPrimitive.content)
        assertEquals(value, WorldsmithJson.decode<QuestLibrary>(document))
        assertTrue(QuestValidation.validate(value).isEmpty())
    }

    @Test fun activationTargetsAreNormalizedIdsAndCountsUseTheSameBoundAsOtherObjectives() {
        for (id in listOf("", "Bad", "a..b", "../altar", "worldsmith:altar", "x".repeat(65)))
            assertTrue(QuestValidation.validate(library(id)).any { it.code == "quests.mechanic_reference" }, id)
        for (count in listOf(0, -1, 1025))
            assertTrue(QuestValidation.validate(library(count = count)).any { it.code == "quests.objective_quantity" })
        assertTrue(QuestValidation.validate(library(count = 1024)).isEmpty())
    }
}
