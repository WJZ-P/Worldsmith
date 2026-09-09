package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.validation.Diagnostic
import kotlinx.serialization.Serializable
import java.nio.file.Path
import com.wjz.worldsmith.core.draw.DrawStructure
import com.wjz.worldsmith.core.content.CustomBlockLibrary

@Serializable data class PublicationStatus(val stage: String, val message: String = "", val diagnostics: List<Diagnostic> = emptyList()) {
    val complete: Boolean get() = stage == "PUBLISHED"
}

/** Native platform callback, injected by MC. Core alone never claims a native publication succeeded. */
fun interface PublicationHost {
    fun request(pack: WorldsmithPack, directory: Path): PublicationStatus
    companion object {
        @JvmField val UNAVAILABLE = PublicationHost { _,_ -> PublicationStatus("WAITING_NATIVE_CONTEXT","Open Minecraft's Create World screen on a native host to validate and activate this pack") }
    }
}

fun interface DrawingExportHost {
    fun export(drawing: DrawStructure): ByteArray
    fun exportContent(drawing:DrawStructure, scope:String, blocks:CustomBlockLibrary):ByteArray {
        require(blocks.blocks.isEmpty()) { "This drawing export host does not support scoped custom blocks" }
        return export(drawing)
    }
}
