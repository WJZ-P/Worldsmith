package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentAssetValidation

import kotlinx.serialization.Serializable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import javax.imageio.ImageIO

/** Vendor-independent pixel authoring data, not a prompt sent to an image model. */
@Serializable data class TextureRecipe(
    val schemaVersion:Int=1,val width:Int,val height:Int,val palette:List<String>,
    val seed:Long=0,val operations:List<TextureOperation>,
)
@Serializable data class TextureOperation(
    val kind:String,val x:Int=0,val y:Int=0,val width:Int?=null,val height:Int?=null,
    val color:Int=0,val colors:List<Int> = emptyList(),val probability:Double=1.0,
    val x2:Int?=null,val y2:Int?=null,val cellSize:Int=1,
    val rows:List<String> = emptyList(),val glyphs:Map<String,Int> = emptyMap(),val scale:Int=1,
)

/** Deterministic, bounded raster compiler shared by every MCP caller. No executable code or network. */
object TextureRecipes {
    const val VERSION="worldsmith-pixel-recipe-1"
    private const val MAX_WORK=16_777_216L
    @JvmStatic fun render(recipe:TextureRecipe):TextureAsset {
        require(recipe.schemaVersion==1 && recipe.width in 1..512 && recipe.height in 1..512) {"Texture recipe schema 1 supports 1..512 pixels per edge"}
        require(recipe.palette.size in 1..256 && recipe.operations.size in 1..256) {"Use 1..256 colors and operations"}
        val palette=recipe.palette.map(::rgba)
        val image=BufferedImage(recipe.width,recipe.height,BufferedImage.TYPE_INT_ARGB)
        var work=0L
        recipe.operations.forEachIndexed {index,op->
            fun paint(x:Int,y:Int,color:Int) {require(x in 0 until recipe.width && y in 0 until recipe.height) {"Operation $index writes outside the canvas"};require(color in palette.indices) {"Invalid palette index"};image.setRGB(x,y,palette[color])}
            fun budget(n:Long){require(n>=0);work+=n;require(work<=MAX_WORK) {"Texture recipe pixel-work budget exceeded"}}
            val random=Random(recipe.seed xor (index.toLong()*6364136223846793005L))
            when(op.kind) {
                "fill","noise","checker" -> {
                    val w=op.width ?: recipe.width;val h=op.height ?: recipe.height
                    require(w>0 && h>0 && op.x>=0 && op.y>=0 && op.x.toLong()+w<=recipe.width && op.y.toLong()+h<=recipe.height) {"Operation $index has invalid rectangle bounds"}
                    budget(w.toLong()*h)
                    require(op.color in palette.indices && op.colors.all {it in palette.indices}) {"Invalid palette index"}
                    if(op.kind=="noise")require(op.colors.isNotEmpty() && op.probability.isFinite() && op.probability in 0.0..1.0)
                    if(op.kind=="checker")require(op.colors.size==2 && op.cellSize in 1..512)
                    for(y in 0 until h)for(x in 0 until w)when(op.kind){
                        "fill"->paint(op.x+x,op.y+y,op.color)
                        "noise"->if(random.nextDouble()<op.probability)paint(op.x+x,op.y+y,op.colors[random.nextInt(op.colors.size)])
                        else->paint(op.x+x,op.y+y,op.colors[(x/op.cellSize+y/op.cellSize)%2])
                    }
                }
                "line" -> {
                    val endX=requireNotNull(op.x2);val endY=requireNotNull(op.y2)
                    require(op.x in 0 until recipe.width && endX in 0 until recipe.width && op.y in 0 until recipe.height && endY in 0 until recipe.height)
                    budget(maxOf(kotlin.math.abs(endX-op.x),kotlin.math.abs(endY-op.y)).toLong()+1)
                    var x=op.x;var y=op.y;val dx=kotlin.math.abs(endX-x);val dy=-kotlin.math.abs(endY-y);val sx=if(x<endX)1 else -1;val sy=if(y<endY)1 else -1;var error=dx+dy
                    while(true){paint(x,y,op.color);if(x==endX&&y==endY)break;val e=2*error;if(e>=dy){error+=dy;x+=sx};if(e<=dx){error+=dx;y+=sy}}
                }
                "stamp" -> {
                    require(op.rows.size in 1..512 && op.rows.first().length in 1..512 && op.rows.all {it.length==op.rows.first().length} && op.scale in 1..8)
                    require(op.glyphs.keys.all {it.length==1 && it!="."} && op.glyphs.values.all {it in palette.indices}) {"Stamp maps one-character glyphs to palette indices; '.' means preserve the underlying pixel"}
                    val w=op.rows.first().length*op.scale;val h=op.rows.size*op.scale
                    require(op.x>=0 && op.y>=0 && op.x.toLong()+w<=recipe.width && op.y.toLong()+h<=recipe.height)
                    budget(w.toLong()*h)
                    op.rows.forEachIndexed {y,row->row.forEachIndexed {x,c->if(c!='.'){val color=requireNotNull(op.glyphs[c.toString()]) {"Unmapped stamp glyph"};for(dy in 0 until op.scale)for(dx in 0 until op.scale)paint(op.x+x*op.scale+dx,op.y+y*op.scale+dy,color)}}}
                }
                else->error("Unsupported texture operation '${op.kind}'; use fill, noise, checker, line or stamp")
            }
        }
        return encode(image)
    }

    /** Explicit whole-atlas import adaptation; no crop, UV rearrangement, remote fetch or implicit resampling. */
    @JvmStatic fun fit(asset:TextureAsset,width:Int,height:Int,resample:String):TextureAsset {
        require(width in 1..512 && height in 1..512) {"Imported target dimensions must be 1..512"}
        require(resample in setOf("none","nearest")) {"Use resample=none or explicitly choose nearest"}
        ContentAssetValidation.verify(asset.descriptor,asset.bytes)
        if(asset.width==width && asset.height==height)return asset
        require(resample=="nearest") {"Image dimensions differ; choose nearest explicitly for whole-image resizing or supply a correctly sized PNG"}
        val source=ImageIO.read(ByteArrayInputStream(asset.bytes)) ?: error("PNG did not decode")
        val target=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB)
        for(y in 0 until height)for(x in 0 until width){val sx=minOf(source.width-1,((x+0.5)*source.width/width).toInt());val sy=minOf(source.height-1,((y+0.5)*source.height/height).toInt());target.setRGB(x,y,source.getRGB(sx,sy))}
        return encode(target)
    }
    private fun rgba(text:String):Int {
        require(text.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) {"Colors use #RRGGBB or #RRGGBBAA"}
        val n=text.substring(1).toLong(16)
        return if(text.length==7)(0xff000000L or n).toInt() else (((n and 255) shl 24) or (n ushr 8)).toInt()
    }
    private fun encode(image:BufferedImage):TextureAsset {
        val out=ByteArrayOutputStream();check(ImageIO.write(image,"png",out))
        return ContentTextureMcp.upload(Base64.getEncoder().encodeToString(out.toByteArray()))
    }
}
