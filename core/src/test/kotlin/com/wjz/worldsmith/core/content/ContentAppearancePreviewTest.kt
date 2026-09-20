package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.examples.MaterialFamilyFactory
import com.wjz.worldsmith.core.mcp.ContentTextureMcp
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class ContentAppearancePreviewTest {
    @Test fun `native UV quarter turns preserve exact RGBA and rotate clockwise`() {
        val source=BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB)
        source.setRGB(0,0,0xff112233.toInt());source.setRGB(1,0,0xff445566.toInt());source.setRGB(0,1,0x80778899.toInt());source.setRGB(1,1,0xffaabbcc.toInt())
        val turn=ContentAppearancePreview.oriented(source,1)
        assertEquals(source.getRGB(0,1),turn.getRGB(0,0));assertEquals(source.getRGB(0,0),turn.getRGB(1,0))
        assertEquals(source.getRGB(1,1),turn.getRGB(0,1));assertEquals(source.getRGB(1,0),turn.getRGB(1,1))
        val full=ContentAppearancePreview.oriented(turn,3)
        for(y in 0..1)for(x in 0..1)assertEquals(source.getRGB(x,y),full.getRGB(x,y))
        assertThrows(IllegalArgumentException::class.java){ContentAppearancePreview.oriented(source,4)}
    }
    @Test fun `block sheet reports every actual face particle and honest inspection boundaries`() {
        val family=MaterialFamilyFactory.create();val block=family.blocks.blocks.single{it.id==MaterialFamilyFactory.WAYSTONE}
        val result=ContentAppearancePreview.block(block,family.assets);val image=ImageIO.read(ByteArrayInputStream(result.png()))
        assertEquals(1440,image.width);assertEquals(1150,image.height)
        assertEquals(false,result.metadata()["minecraftScreenshot"]);assertEquals(false,result.metadata()["aestheticQualityValidated"])
        val assets=result.metadata()["assets"] as Map<*,*>
        assertEquals(block.appearance.assetIds().toSet(),assets.keys)
        assertEquals(6,(result.metadata()["faces"] as Map<*,*>).size)
        assertEquals(block.appearance.particle,result.metadata()["particle"])
        val copy=result.png();copy[0]=0;assertNotEquals(0,result.png()[0])
    }
    @Test fun `preview rejects missing particle and changed bytes instead of trusting declared hashes`() {
        val family=MaterialFamilyFactory.create();val block=family.blocks.blocks.first()
        assertThrows(IllegalArgumentException::class.java){ContentAppearancePreview.block(block,family.assets-block.appearance.particle)}
        val broken=family.assets.mapValues {it.value.copyOf()};val bytes=broken.getValue(block.appearance.particle);bytes[20]=(bytes[20].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java){ContentAppearancePreview.block(block,broken)}
    }
    @Test fun `item sheet compares separate silhouettes at bounded native scales`() {
        val family=MaterialFamilyFactory.create();val result=ContentAppearancePreview.items(family.items.items,family.assets)
        val image=ImageIO.read(ByteArrayInputStream(result.png()));assertEquals(1200,image.width);assertEquals(840,image.height)
        assertEquals(3,(result.metadata()["items"] as Map<*,*>).size)
        assertThrows(IllegalArgumentException::class.java){ContentAppearancePreview.items(emptyList(),emptyMap())}
        assertThrows(IllegalArgumentException::class.java){ContentAppearancePreview.items(List(9){family.items.items.first()},family.assets)}
    }
    @Test fun `non-native icon dimensions are rejected before a polished sheet can mask them`() {
        val texture=ContentTextureMcp.pixels(listOf("#FFFFFF"),List(3){List(3){0}})
        assertThrows(IllegalArgumentException::class.java){ContentAppearancePreview.items(listOf(CustomItemDefinition("bad","Bad",texture.descriptor.id)),mapOf(texture.descriptor.id to texture.bytes))}
    }
}
