package com.wjz.worldsmith.core.story

import com.wjz.worldsmith.core.ability.AbilityValues
import com.wjz.worldsmith.core.content.ContentKey
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StoryProjectionTest {
    private fun library()=StoryLibrary(
        facts=listOf(StoryFact("chosen",StoryFactScope.WORLD,StoryFactType.BOOL,AbilityValues.bool(false)),
            StoryFact("applied",StoryFactScope.PLACE,StoryFactType.BOOL,AbilityValues.bool(false)),
            StoryFact("opinion",StoryFactScope.PLAYER,StoryFactType.BOOL,AbilityValues.bool(false))),
        places=listOf(StoryPlace("village","Village","An actual settlement","village_house")),
        projections=listOf(StoryProjection("lamp","village",StoryCondition.Compare(StoryFactRef("chosen"),StoryComparison.EQ,AbilityValues.bool(true)),
            listOf(StoryBlockChange(StoryOffset(0,3,2),StoryBlockState("minecraft:polished_deepslate"),StoryBlockState("minecraft:sea_lantern"))),
            listOf(StoryFactChange(StoryFactRef("applied"),AbilityValues.bool(true))))))

    @Test fun `projection schema is current and serializes exact state contracts and catalog references`() {
        val value=library()
        assertEquals(2,value.schemaVersion)
        assertTrue(StoryValidation.validate(value).isEmpty(),StoryValidation.validate(value).toString())
        assertFalse(StoryValidation.validate(value.copy(schemaVersion=1)).isEmpty())
        assertEquals(value,WorldsmithJson.decode<StoryLibrary>(WorldsmithJson.encode(value)))
        val contribution=StoryContentModule.describe(WorldsmithJson.format.encodeToJsonElement(StoryLibrary.serializer(),value).jsonObject)
        val entry=contribution.entries.single { it.key==ContentKey("story_projection","lamp") }
        assertEquals(setOf(ContentKey("place","village"),ContentKey("story_fact","chosen"),ContentKey("story_fact","applied")),entry.references.map { it.target }.toSet())
        assertEquals(setOf("minecraft:polished_deepslate","minecraft:sea_lantern"),entry.nativeReferences.map { it.id }.toSet())
    }
    @Test fun `projection ownership is independent of players and cannot guess another actual instance`() {
        val value=library();val projection=value.projections.single()
        val invalid=listOf(
            projection.copy(condition=StoryCondition.Compare(StoryFactRef("opinion"),StoryComparison.EQ,AbilityValues.bool(true))),
            projection.copy(onApplied=listOf(StoryFactChange(StoryFactRef("opinion"),AbilityValues.bool(true)))),
            projection.copy(onApplied=listOf(StoryFactChange(StoryFactRef("applied","other"),AbilityValues.bool(true)))),
            projection.copy(place="missing"))
        invalid.forEach { assertFalse(StoryValidation.validate(value.copy(projections=listOf(it))).isEmpty(),it.toString()) }
        val explicitOwn=projection.copy(onApplied=listOf(StoryFactChange(StoryFactRef("applied","village"),AbilityValues.bool(true))))
        assertTrue(StoryValidation.validate(value.copy(projections=listOf(explicitOwn))).isEmpty())
    }
    @Test fun `projection changes have finite unique offsets and bounded exact properties`() {
        val value=library();val p=value.projections.single();val block=p.blocks.single()
        val invalid=listOf(p.copy(blocks=emptyList()),p.copy(blocks=List(33) { block }),p.copy(blocks=listOf(block,block)),
            p.copy(blocks=listOf(block.copy(offset=StoryOffset(32,1,0)))),
            p.copy(blocks=listOf(block.copy(desired=block.expected))),
            p.copy(blocks=listOf(block.copy(desired=StoryBlockState("minecraft:sea_lantern",mapOf("bad key" to "yes"))))))
        invalid.forEach { assertFalse(StoryValidation.validate(value.copy(projections=listOf(it))).isEmpty()) }
        assertFalse(StoryValidation.validate(value.copy(projections=List(65) { p.copy(id="lamp_$it") })).isEmpty())
    }
    @Test fun `freezing detaches projection block lists properties conditions and effects`() {
        val properties=linkedMapOf("facing" to "north")
        val changes=mutableListOf(StoryBlockChange(StoryOffset(),StoryBlockState("minecraft:stone"),StoryBlockState("minecraft:oak_stairs",properties)))
        val effects=mutableListOf(StoryFactChange(StoryFactRef("applied"),AbilityValues.bool(true)))
        val original=library().let { it.copy(projections=listOf(it.projections.single().copy(blocks=changes,onApplied=effects))) }
        val frozen=StoryValidation.freeze(original);properties.clear();changes.clear();effects.clear()
        assertEquals("north",frozen.projections.single().blocks.single().desired.properties["facing"])
        assertEquals(1,frozen.projections.single().onApplied.size)
        assertThrows(UnsupportedOperationException::class.java) { (frozen.projections.single().blocks as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (frozen.projections.single().blocks.single().desired.properties as MutableMap).clear() }
    }
}
