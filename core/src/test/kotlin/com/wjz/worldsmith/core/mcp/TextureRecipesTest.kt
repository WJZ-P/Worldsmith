package com.wjz.worldsmith.core.mcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class TextureRecipesTest {
    private fun recipe(vararg operations:TextureOperation)=TextureRecipe(width=16,height=16,palette=listOf("#102030","#A0B0C080","#EEDDCC"),seed=73,operations=operations.toList())
    private fun pixels(recipe:TextureRecipe)=ImageIO.read(ByteArrayInputStream(TextureRecipes.render(recipe).bytes))
    @Test fun `seed and operation order reproduce exact bytes with untouched transparent background`() {
        val recipe=recipe(TextureOperation("noise",colors=listOf(0,1,2),probability=.35))
        val first=TextureRecipes.render(recipe);val next=TextureRecipes.render(recipe)
        assertArrayEquals(first.bytes,next.bytes);assertEquals(first.descriptor,next.descriptor)
        assertNotEquals(first.descriptor.id,TextureRecipes.render(recipe.copy(seed=74)).descriptor.id)
        val image=pixels(recipe);assertTrue((0..15).any {y->(0..15).any {x->image.getRGB(x,y)==0}})
    }
    @Test fun `fill and zero probability noise preserve exact RGBA`() {
        val image=pixels(recipe(TextureOperation("fill",color=1),TextureOperation("noise",colors=listOf(2),probability=0.0)))
        assertEquals(0x80a0b0c0.toInt(),image.getRGB(3,7))
    }
    @Test fun `checker has requested cell size and line includes both endpoints`() {
        val image=pixels(recipe(TextureOperation("checker",colors=listOf(0,2),cellSize=2),TextureOperation("line",x=1,y=1,x2=4,y2=4,color=1)))
        assertEquals(0xff102030.toInt(),image.getRGB(0,0));assertEquals(0xffeeddcc.toInt(),image.getRGB(2,0))
        for (i in 1..4)assertEquals(0x80a0b0c0.toInt(),image.getRGB(i,i))
        assertEquals(0xff102030.toInt(),image.getRGB(5,5))
    }
    @Test fun `scaled stamp dots preserve underlying pixels rather than erase them`() {
        val image=pixels(recipe(TextureOperation("fill",color=0),TextureOperation("stamp",x=4,y=4,rows=listOf("a.",".a"),glyphs=mapOf("a" to 2),scale=2)))
        assertEquals(0xffeeddcc.toInt(),image.getRGB(4,4));assertEquals(0xffeeddcc.toInt(),image.getRGB(5,5))
        assertEquals(0xff102030.toInt(),image.getRGB(6,4));assertEquals(0xff102030.toInt(),image.getRGB(4,6))
        assertEquals(0xffeeddcc.toInt(),image.getRGB(7,7))
    }
    @Test fun `out of canvas palette unknown operation and ragged stamps fail explicitly`() {
        val invalid=listOf(TextureOperation("fill",x=1),TextureOperation("line",x2=16,y2=0),TextureOperation("fill",color=3),
            TextureOperation("checker",colors=listOf(0)),TextureOperation("noise",colors=listOf(0),probability=Double.NaN),
            TextureOperation("stamp",rows=listOf("a","aa"),glyphs=mapOf("a" to 0)),TextureOperation("stamp",rows=listOf("z")),TextureOperation("shell"))
        invalid.forEach {op->assertThrows(IllegalArgumentException::class.java){TextureRecipes.render(recipe(op))}}
    }
    @Test fun `pixel work budget bounds high resolution repeated operations`() {
        val recipe=TextureRecipe(width=512,height=512,palette=listOf("#FFFFFF"),operations=List(65){TextureOperation("fill")})
        val error=assertThrows(IllegalArgumentException::class.java){TextureRecipes.render(recipe)}
        assertTrue(error.message!!.contains("pixel-work budget"))
    }
    @Test fun `whole image nearest fit uses pixel centers and never silently resamples`() {
        val source=ContentTextureMcp.pixels(listOf("#112233","#DDEEFF"),listOf(listOf(0,1),listOf(1,0)))
        assertSame(source,TextureRecipes.fit(source,2,2,"none"))
        assertThrows(IllegalArgumentException::class.java){TextureRecipes.fit(source,4,4,"none")}
        val resized=ImageIO.read(ByteArrayInputStream(TextureRecipes.fit(source,4,4,"nearest").bytes))
        assertEquals(0xff112233.toInt(),resized.getRGB(1,1));assertEquals(0xffddeeff.toInt(),resized.getRGB(2,1))
    }
    @Test fun `bricks stagger their courses around mortar and tile without a seam`() {
        val palette=listOf("#202020","#808080","#C0C0C0","#404040","#FFFFFF")
        val image=pixels(TextureRecipe(width=16,height=16,palette=palette,seed=5,operations=listOf(
            TextureOperation("bricks",cellSize=4,scale=8,color=0,colors=listOf(1),highlight=2,shadow=3))))
        val mortar=0xff202020.toInt()
        for (x in 0..15) assertEquals(mortar,image.getRGB(x,3),"each course ends in a mortar line")
        assertEquals(mortar,image.getRGB(7,0));assertEquals(mortar,image.getRGB(15,0))
        assertEquals(mortar,image.getRGB(3,4),"the next course is offset by half a brick")
        assertEquals(0xffc0c0c0.toInt(),image.getRGB(0,0),"a brick's top-left edge catches the light")
        assertEquals(0xff404040.toInt(),image.getRGB(6,1),"its bottom-right edge is shaded")
        // Wrapping: a course's mortar columns repeat every brick length, across the tile edge too.
        for (y in 0..15) assertEquals(image.getRGB(0,y)==mortar,image.getRGB(8,y)==mortar)
    }
    @Test fun `grain gradient and edge change brightness only where there is paint`() {
        val flat=recipe(TextureOperation("fill",x=0,y=0,width=8,height=8,color=2))
        val grained=pixels(flat.copy(operations=flat.operations+TextureOperation("grain",amount=.2,cellSize=2)))
        assertEquals(0,grained.getRGB(12,12),"transparency stays transparent")
        assertTrue((0..7).flatMap {y->(0..7).map {x->grained.getRGB(x,y)}}.distinct().size>1)
        assertEquals(grained.getRGB(0,0),grained.getRGB(1,1),"grain varies in clusters, not per pixel")

        val graded=pixels(flat.copy(operations=flat.operations+TextureOperation("gradient",amount=.2)))
        assertTrue((graded.getRGB(3,0) and 0xff)>(graded.getRGB(3,7) and 0xff),"lighter at the top")

        val edged=pixels(flat.copy(operations=flat.operations+TextureOperation("edge",amount=.5)))
        assertTrue((edged.getRGB(7,3) and 0xff)<(edged.getRGB(3,3) and 0xff),"the rim beside transparency darkens")
        assertEquals(0xffeeddcc.toInt(),edged.getRGB(3,3),"the interior is untouched")
    }
    @Test fun `material operations refuse values outside their ranges`() {
        listOf(TextureOperation("bricks",cellSize=1,scale=8,colors=listOf(0)),TextureOperation("bricks",cellSize=4,scale=8),
            TextureOperation("bevel"),TextureOperation("grain",amount=.5),TextureOperation("gradient",amount=.9),
            TextureOperation("edge",amount=Double.NaN),TextureOperation("bevel",highlight=9))
            .forEach {op->assertThrows(IllegalArgumentException::class.java){TextureRecipes.render(recipe(op))}}
    }
}
