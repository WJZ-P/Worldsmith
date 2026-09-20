package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.mcp.TextureRecipes
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MaterialShowcaseExampleTest {
    @Test fun `family recipes reproduce all actual material assets and distinct silhouettes`() {
        val family=MaterialFamilyFactory.create()
        assertEquals(family.assets.keys,family.recipes.values.map {TextureRecipes.render(it).descriptor.id}.toSet())
        assertEquals(3,family.items.items.map {it.textureAsset}.distinct().size)
        assertTrue(CustomBlockValidation.validate(family.blocks).isEmpty())
        val dark=family.blocks.blocks.single {it.id==MaterialFamilyFactory.LAMP_UNLIT};val lit=family.blocks.blocks.single {it.id==MaterialFamilyFactory.LAMP_LIT}
        assertNotEquals(dark.appearance.north,lit.appearance.north);assertEquals(dark.appearance.up,lit.appearance.up)
        assertEquals(BlockOrientation.HORIZONTAL,dark.appearance.orientation);assertEquals(0,dark.light);assertEquals(13,lit.light)
        assertTrue(family.assets.keys.containsAll(family.blocks.blocks.flatMap {it.appearance.assetIds()}))
    }
    @Test fun `showcase publishes actual structure supply items and repair references in current format`() {
        val pack=MaterialShowcaseExample.create();val errors=WorldsmithPackValidator.validate(pack).filter {it.severity==DiagnosticSeverity.ERROR}
        assertTrue(errors.isEmpty(),errors.joinToString("\n"));assertEquals(WorldContentBundleIO.FORMAT_VERSION,pack.manifest.formatVersion)
        assertTrue(pack.structures.structures.any {it.id==MaterialShowcaseExample.STRUCTURE})
        val repair=pack.mechanics.mechanics.single {it.id==MaterialShowcaseExample.REPAIR}.rules.single()
        assertEquals("worldsmith:item/${MaterialFamilyFactory.WICK}",repair.heldItem!!.item)
        assertEquals("worldsmith:content/${MaterialFamilyFactory.LAMP_LIT}",(repair.actions.single() as MechanicAction.SetBlock).block.block)
        val encoded=WorldContentBundleIO.encode(pack)
        assertEquals(2,encoded.manifest.modules.getValue("blocks").schemaVersion)
        assertTrue(encoded.texts.getValue("blocks.json").contains("\"appearance\""))
    }
}
