package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.CustomBlockLibrary
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Bounded preview-only palette. World publication still independently rechecks every original PNG. */
class PreviewMaterialPalette(private val texture:(WorkflowSession,String)->ByteArray) {
    data class Palette(val colors:Map<String,Int> = emptyMap(),val warnings:List<String> = emptyList())
    private val cache=object:LinkedHashMap<String,Palette>(16,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Palette>?)=size>64}
    @Synchronized fun resolve(session:WorkflowSession?):Palette {
        if(session==null)return Palette()
        val raw=session.contentModules["blocks"] ?: return Palette()
        val key=session.id+":"+raw+":"+session.contentAssets.keys.sorted().joinToString()
        cache[key]?.let {return it}
        val colors=linkedMapOf<String,Int>();val warnings=mutableListOf<String>()
        val library=runCatching {McpJson.decode<CustomBlockLibrary>(raw)}.getOrElse {return Palette(warnings=listOf("Custom block draft did not decode; showing generic material colours"))}
        for(block in library.blocks.take(128))runCatching {
            val bytes=texture(session,block.textureAsset);require(bytes.size<=1024*1024) {"Block texture exceeds 1 MiB"}
            val image=ImageIO.read(ByteArrayInputStream(bytes)) ?: error("PNG did not decode")
            require(image.width==image.height && image.width in 16..256 && image.width.countOneBits()==1) {"Block texture dimensions must be square powers of two, 16..256"}
            var red=0L;var green=0L;var blue=0L;var weight=0L
            for(y in 0 until image.height)for(x in 0 until image.width){val color=image.getRGB(x,y);val alpha=(color ushr 24)and 255;red+=((color ushr 16)and 255).toLong()*alpha;green+=((color ushr 8)and 255).toLong()*alpha;blue+=(color and 255).toLong()*alpha;weight+=alpha}
            require(weight>0) {"Texture is entirely transparent"}
            colors["worldsmith:content/${block.id}"]=((red/weight).toInt() shl 16)or((green/weight).toInt() shl 8)or(blue/weight).toInt()
        }.onFailure {warnings+="${block.id}: ${it.message}; generic colour retained"}
        return Palette(colors.toMap(),warnings.toList()).also {if(warnings.isEmpty())cache[key]=it}
    }
}
