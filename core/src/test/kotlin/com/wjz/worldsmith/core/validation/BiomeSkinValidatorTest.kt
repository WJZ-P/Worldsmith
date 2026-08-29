package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.model.BiomeColors
import com.wjz.worldsmith.core.model.BiomeArchetypeRole
import com.wjz.worldsmith.core.model.BiomeBehavior
import com.wjz.worldsmith.core.model.BiomeLayoutPlan
import com.wjz.worldsmith.core.model.BiomeSkeletonDefinition
import com.wjz.worldsmith.core.model.BiomeSkin
import com.wjz.worldsmith.core.model.BiomeSkinSet
import com.wjz.worldsmith.core.model.ClimateBox
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.SurfaceLayers
import com.wjz.worldsmith.core.model.VegetationRecipe
import com.wjz.worldsmith.core.model.VegetationSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BiomeSkinValidatorTest {
    private val skeletonIds = listOf("abyss", "shallows", "shore", "peaks", "highland", "flats_cold", "flats_temperate", "flats_hot")

    @Test
    fun `a complete skin set reports no diagnostics`() {
        assertTrue(BiomeSkinValidator.validate(skinSet(), layout()).isEmpty())
    }

    @Test
    fun `a missing skeleton is reported`() {
        val incomplete = skinSet().let { it.copy(skins = it.skins.dropLast(1)) }

        val codes = BiomeSkinValidator.validate(incomplete, layout()).map { it.code }

        assertEquals(listOf("MISSING_SKELETON"), codes)
    }

    @Test
    fun `an unknown skeleton is reported and does not satisfy coverage`() {
        val renamed = skinSet().let {
            it.copy(skins = it.skins.dropLast(1) + skin("flats_hot").copy(skeletonId = "molten_sea"))
        }

        val codes = BiomeSkinValidator.validate(renamed, layout()).map { it.code }

        assertEquals(listOf("UNKNOWN_SKELETON", "MISSING_SKELETON"), codes)
    }

    @Test
    fun `a duplicated skeleton is reported`() {
        val duplicated = skinSet().let {
            it.copy(skins = it.skins.dropLast(1) + skin("abyss"))
        }

        val codes = BiomeSkinValidator.validate(duplicated, layout()).map { it.code }

        assertEquals(listOf("DUPLICATE_SKELETON", "MISSING_SKELETON"), codes)
    }

    @Test
    fun `malformed colors are reported per field`() {
        val broken = skinSet().let {
            val first = it.skins.first()
            it.copy(skins = listOf(first.copy(colors = first.colors.copy(grass = "7A6C55", sky = "#GGGGGG"))) + it.skins.drop(1))
        }

        val diagnostics = BiomeSkinValidator.validate(broken, layout())

        assertEquals(listOf("INVALID_COLOR", "INVALID_COLOR"), diagnostics.map { it.code })
        assertEquals(listOf("skins[0].colors.grass", "skins[0].colors.sky"), diagnostics.map { it.path })
    }

    @Test
    fun `an unusable material selector is reported`() {
        val broken = skinSet().let {
            val first = it.skins.first()
            val empty = MaterialSelector(semanticRole = "surface_top")
            it.copy(skins = listOf(first.copy(surface = first.surface.copy(top = empty))) + it.skins.drop(1))
        }

        val diagnostics = BiomeSkinValidator.validate(broken, layout())

        assertEquals(listOf("EMPTY_MATERIAL"), diagnostics.map { it.code })
        assertEquals(listOf("skins[0].surface.top"), diagnostics.map { it.path })
    }

    @Test
    fun `vegetation density outside the unit range is reported`() {
        val broken = skinSet().let {
            val last = it.skins.last()
            val slot = VegetationSlot(VegetationRecipe.DEAD_TREE, material("dead_wood", "minecraft:dead_bush"), 1.5)
            it.copy(skins = it.skins.dropLast(1) + last.copy(vegetation = listOf(slot)))
        }

        val diagnostics = BiomeSkinValidator.validate(broken, layout())

        assertEquals(listOf("DENSITY_OUT_OF_RANGE"), diagnostics.map { it.code })
    }

    @Test
    fun `a blank world id is reported`() {
        val diagnostics = BiomeSkinValidator.validate(skinSet().copy(worldId = " "), layout())

        assertEquals(listOf("EMPTY_WORLD_ID"), diagnostics.map { it.code })
    }

    private fun skinSet() = BiomeSkinSet(
        worldId = "ashlands",
        skins = skeletonIds.map(::skin),
    )

    private fun layout() = BiomeLayoutPlan(
        worldId = "ashlands",
        skeletons = skeletonIds.map { id ->
            BiomeSkeletonDefinition(
                id = id,
                archetype = BiomeArchetypeRole.LOWLAND,
                climate = ClimateBox(),
                behavior = BiomeBehavior(temperature = 0.5f, downfall = 0.4f, hasPrecipitation = true),
            )
        },
    )

    private fun skin(skeletonId: String) = BiomeSkin(
        skeletonId = skeletonId,
        displayName = skeletonId.replace('_', ' '),
        colors = BiomeColors(
            grass = "#7A6C55",
            foliage = "#6B5F49",
            water = "#4A5340",
            sky = "#8C7A63",
            fog = "#9C8A73",
        ),
        surface = SurfaceLayers(
            top = material("surface_top", "minecraft:coarse_dirt"),
            under = material("surface_under", "minecraft:dirt"),
            deep = material("surface_deep", "minecraft:tuff"),
        ),
    )

    private fun material(role: String, id: String) = MaterialSelector(
        semanticRole = role,
        preferredIds = listOf(id),
    )
}
