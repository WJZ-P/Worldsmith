package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentAssetValidation
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/** Tiny isolated previews; this never prepares a world, allocates native content slots or reloads resources. */
object ResourcePackIconPreview {
    const val SIZE=32
    data class Pixels(val width:Int,val height:Int,val argb:IntArray)

    @JvmStatic fun read(source:Path,icon:ResourcePackIcon):Pixels {
        val path=source.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(path) && path.toRealPath()==path) {"Linked preview source"}
        val asset=icon.asset
        require(asset.path==ContentAssetValidation.path(asset.sha256)) {"Invalid preview asset path"}
        val bytes=if(Files.isDirectory(path,NOFOLLOW_LINKS)) {
            val target=path.resolve(requireNotNull(asset.path)).normalize()
            require(target.startsWith(path) && Files.isRegularFile(target,NOFOLLOW_LINKS) && !Files.isSymbolicLink(target) && target.toRealPath()==target) {"Linked or missing preview image"}
            Files.newInputStream(target,NOFOLLOW_LINKS).use(::bounded)
        } else {
            require(Files.isRegularFile(path,NOFOLLOW_LINKS) && Files.size(path) in 22..WorldsmithResourceArchive.MAX_ARCHIVE_BYTES) {"Invalid preview archive"}
            ZipFile(path.toFile(),Charsets.UTF_8).use {zip ->
                val entry=requireNotNull(zip.getEntry(asset.path)) {"Missing preview image"}
                require(!entry.isDirectory && entry.size in 1..ContentAssetValidation.MAX_ASSET_BYTES.toLong()) {"Preview image exceeds its budget"}
                zip.getInputStream(entry).use(::bounded)
            }
        }
        ContentAssetValidation.verify(asset,bytes)
        val image=requireNotNull(ImageIO.read(ByteArrayInputStream(bytes))) {"Preview PNG did not decode"}
        val scale=minOf(SIZE.toDouble()/image.width,SIZE.toDouble()/image.height)
        val width=maxOf(1,(image.width*scale).toInt()); val height=maxOf(1,(image.height*scale).toInt())
        val left=(SIZE-width)/2;val top=(SIZE-height)/2;val pixels=IntArray(SIZE*SIZE)
        // Nearest-neighbour scaling preserves the authored pixel palette and transparency.
        for(y in 0 until height) for(x in 0 until width)
            pixels[(top+y)*SIZE+left+x]=image.getRGB(x*image.width/width,y*image.height/height)
        return Pixels(SIZE,SIZE,pixels)
    }

    private fun bounded(input:InputStream):ByteArray=input.readNBytes(ContentAssetValidation.MAX_ASSET_BYTES+1).also {
        require(it.size in 1..ContentAssetValidation.MAX_ASSET_BYTES) {"Preview image exceeds its budget"}
    }
}
