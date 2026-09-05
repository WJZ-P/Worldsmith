package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.serialization.WorldsmithJson

/** The single disk layout shared by hashing, the loader and MCP persistence. */
object StructurePackIO {
    const val INDEX_FILE = "structures.json"
    private val ID=Regex("^[a-z0-9_][a-z0-9_-]{0,63}$")

    @JvmStatic
    fun files(library:StructureLibrary):Map<String,String> {
        require(library.structures.size<=StructureValidator.MAX_STRUCTURES) { "Too many structures" }
        val contents=linkedMapOf<String,String>()
        val entries=library.structures.map { structure ->
            require(ID.matches(structure.id) && ID.matches(structure.blueprint.id)) { "Invalid structure identifier" }
            val file="structures/${structure.blueprint.id}.json"
            val text=WorldsmithJson.encode(structure.blueprint)
            val previous=contents.putIfAbsent(file,text)
            require(previous==null || previous==text) { "Conflicting blueprint ${structure.blueprint.id}" }
            StructureIndexEntry(structure.id,file,structure.placement)
        }
        contents[INDEX_FILE]=WorldsmithJson.encode(StructureIndex(library.schemaVersion,entries))
        return contents
    }

    @JvmStatic
    fun paths(index:StructureIndex):List<String> {
        require(index.structures.size<=StructureValidator.MAX_STRUCTURES) { "Too many structures" }
        return index.structures.map { it.blueprint }.distinct().sorted().also { paths ->
            require(paths.all { it.matches(Regex("structures/[a-z0-9_][a-z0-9_-]{0,63}\\.json")) }) { "Blueprint files must be under structures/ with simple file names" }
        }
    }

    @JvmStatic
    fun load(index:StructureIndex,contents:Map<String,String>):StructureLibrary {
        paths(index)
        val definitions=index.structures.map { entry ->
            val blueprint=WorldsmithJson.decode<StructureBlueprint>(requireNotNull(contents[entry.blueprint]))
            require(entry.blueprint=="structures/${blueprint.id}.json") { "Blueprint id must match its file name" }
            WorldStructureDefinition(entry.id,blueprint,entry.placement)
        }
        return StructureLibrary(index.schemaVersion,definitions)
    }
}
