package com.wjz.worldsmith.core.story

import com.wjz.worldsmith.core.ability.AbilityValues
import com.wjz.worldsmith.core.ability.AbilityValue
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StoryValidationTest {
    private val rescued=StoryFact("rescued",StoryFactScope.WORLD,StoryFactType.BOOL,AbilityValues.bool(false))
    private val trust=StoryFact("trust",StoryFactScope.PLAYER,StoryFactType.NUMBER,AbilityValues.number(0.0),-10.0,10.0)
    private fun eq(id: String,value: AbilityValue)=StoryCondition.Compare(StoryFactRef(id),StoryComparison.EQ,value)
    private fun library()=StoryLibrary(facts=listOf(rescued,trust),dialogues=listOf(StoryDialogue("welcome","hello",listOf(
        StoryDialogueNode("hello","Welcome, traveller.",listOf(StoryDialogueOption("leave","Farewell.")))))))

    @Test fun `shared comparisons do not turn missing or mismatched facts into truth through negation`() {
        assertTrue(StoryConditions.test(StoryCondition.All(emptyList())) { AbilityValues.none() })
        assertFalse(StoryConditions.test(StoryCondition.Any(emptyList())) { AbilityValues.none() })
        val comparison=eq("rescued",AbilityValues.bool(true))
        assertTrue(StoryConditions.test(comparison) { AbilityValues.bool(true) })
        assertFalse(StoryConditions.test(StoryCondition.Not(comparison)) { AbilityValues.none() })
        assertFalse(StoryConditions.test(StoryCondition.Compare(StoryFactRef("rescued"),StoryComparison.NE,AbilityValues.bool(true))) { AbilityValues.text("true") })
        assertTrue(StoryConditions.test(StoryCondition.Compare(StoryFactRef("trust"),StoryComparison.GE,AbilityValues.number(3.0))) { AbilityValues.number(4.0) })
    }
    @Test fun `conditions bound traversal and preserve all fact references`() {
        val comparison=eq("rescued",AbilityValues.bool(true))
        assertEquals(listOf(StoryFactRef("rescued")),StoryConditions.references(StoryCondition.Not(comparison)))
        assertFalse(StoryConditions.validate(StoryCondition.All(List(64) { comparison })).isEmpty())
        var deep: StoryCondition=comparison;repeat(9) { deep=StoryCondition.Not(deep) }
        assertFalse(StoryConditions.validate(deep).isEmpty())
        assertFalse(StoryConditions.test(deep) { AbilityValues.bool(true) })
    }
    @Test fun `serialized condition and primitive bounds reject before typed recursion`() {
        val deeplyNested="{\"kind\":\"not\",\"condition\":".repeat(500)+"{\"kind\":\"always\"}"+"}".repeat(500)
        assertThrows(Exception::class.java) { WorldsmithJson.decode<StoryCondition>(deeplyNested) }
        assertThrows(Exception::class.java) { WorldsmithJson.decode<StoryCondition>("""{"kind":"always","invented":true}""") }
        assertThrows(Exception::class.java) { WorldsmithJson.decode<StoryFact>("""{"id":"bad","scope":"WORLD","type":"TEXT","initial":{"kind":"list","values":[]}}""") }
        val original=StoryCondition.All(listOf(eq("rescued",AbilityValues.bool(true)),StoryCondition.Not(StoryCondition.Any(emptyList()))))
        assertEquals(original,WorldsmithJson.decode<StoryCondition>(WorldsmithJson.encode<StoryCondition>(original)))
    }
    @Test fun `typed transactions reject ambiguous duplicates invalid subjects and invalid additions`() {
        val l=library()
        assertTrue(StoryValidation.validate(l).isEmpty(),StoryValidation.validate(l).toString())
        val c=StoryFactChange(StoryFactRef("trust"),AbilityValues.number(2.0),StoryChangeMode.ADD)
        assertTrue(StoryValidation.validateChanges(listOf(c),l).isEmpty())
        assertFalse(StoryValidation.validateChanges(listOf(c,c),l).isEmpty())
        assertFalse(StoryValidation.validateChanges(listOf(c.copy(value=AbilityValues.number(11.0),mode=StoryChangeMode.SET)),l).isEmpty())
        assertFalse(StoryValidation.validateChanges(listOf(StoryFactChange(StoryFactRef("rescued"),AbilityValues.bool(true),StoryChangeMode.ADD)),l).isEmpty())
        assertFalse(StoryValidation.validateCondition(eq("rescued",AbilityValues.text("yes")),l).isEmpty())
        assertFalse(StoryValidation.validateCondition(StoryCondition.Compare(StoryFactRef("trust","someone"),StoryComparison.EQ,AbilityValues.number(0.0)),l).isEmpty())
    }
    @Test fun `dialogues reject dangling unreachable and inescapable graph nodes`() {
        val l=library();val d=l.dialogues.single();val n=d.nodes.single();val o=n.options.single()
        for (target in listOf("missing","hello")) {
            val broken=d.copy(nodes=listOf(n.copy(options=listOf(o.copy(next=target)))))
            assertFalse(StoryValidation.validate(l.copy(dialogues=listOf(broken))).isEmpty())
        }
        val unreachable=d.copy(nodes=d.nodes+StoryDialogueNode("hidden","Lost",emptyList()))
        assertFalse(StoryValidation.validate(l.copy(dialogues=listOf(unreachable))).isEmpty())
    }
    @Test fun `world routines wrap midnight reject overlap and never read observer dependent facts`() {
        val routine=StoryRoutine("night",23000,1000,StoryOffset(2,0,0),"Resting")
        val resident=StoryCharacter("keeper","Keeper","keeper_creature","village",routines=listOf(routine))
        val l=library().copy(places=listOf(StoryPlace("village","Village","A sheltered village","village_structure")),characters=listOf(resident))
        assertTrue(StoryValidation.validate(l).isEmpty(),StoryValidation.validate(l).toString())
        val broken=listOf(
            resident.copy(routines=listOf(routine,routine.copy(id="other",startTick=500,endTick=2000))),
            resident.copy(routines=listOf(routine.copy(condition=eq("trust",AbilityValues.number(0.0))))),
            resident.copy(routines=listOf(routine.copy(offset=StoryOffset(24,24,0))))
        )
        broken.forEach { assertFalse(StoryValidation.validate(l.copy(characters=listOf(it))).isEmpty()) }
    }
    @Test fun `freeze protects nested conditions options and transactions and serialization is exact`() {
        val conditions=mutableListOf<StoryCondition>(eq("rescued",AbilityValues.bool(true)))
        val options=mutableListOf(StoryDialogueOption("leave","Leave",condition=StoryCondition.All(conditions)))
        val l=library().copy(dialogues=listOf(StoryDialogue("welcome","hello",listOf(StoryDialogueNode("hello","Welcome",options)))))
        val frozen=StoryValidation.freeze(l);conditions.clear();options.clear()
        assertEquals(1,frozen.dialogues.single().nodes.single().options.size)
        val condition=frozen.dialogues.single().nodes.single().options.single().condition as StoryCondition.All
        assertEquals(1,condition.conditions.size)
        assertThrows(UnsupportedOperationException::class.java) { (condition.conditions as MutableList).clear() }
        assertEquals(frozen,WorldsmithJson.decode<StoryLibrary>(WorldsmithJson.encode(frozen)))
    }
}
