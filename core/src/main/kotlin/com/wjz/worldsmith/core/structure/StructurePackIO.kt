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
        fun save(blueprint:StructureBlueprint):String {
            require(ID.matches(blueprint.id)) { "Invalid blueprint identifier" }
            val file="structures/${blueprint.id}.json";val text=WorldsmithJson.encode(blueprint)
            val previous=contents.putIfAbsent(file,text)
            require(previous==null || previous==text) { "Conflicting blueprint ${blueprint.id}" }
            return file
        }
        val entries=library.structures.map { structure ->
            require(ID.matches(structure.id) && ID.matches(structure.blueprint.id)) { "Invalid structure identifier" }
            val file=save(structure.blueprint)
            val assembly=structure.assembly?.let {a->
                require(a.pieces.size<=16)
                StructureAssemblyIndex(a.pieces.mapValues {(id,b)->require(id==b.id);save(b)},a.pools,a.variants,a.maxPieces,a.maxDepth,a.maxRadius)
            }
            StructureIndexEntry(structure.id,file,structure.placement,assembly)
        }
        contents[INDEX_FILE]=WorldsmithJson.encode(StructureIndex(library.schemaVersion,entries))
        return contents
    }

    @JvmStatic
    fun paths(index:StructureIndex):List<String> {
        require(index.structures.size<=StructureValidator.MAX_STRUCTURES) { "Too many structures" }
        require(index.structures.all {it.assembly==null||it.assembly.pieces.size<=16})
        return index.structures.flatMap { listOf(it.blueprint)+(it.assembly?.pieces?.values ?: emptyList()) }.distinct().sorted().also { paths ->
            require(paths.all { it.matches(Regex("structures/[a-z0-9_][a-z0-9_-]{0,63}\\.json")) }) { "Blueprint files must be under structures/ with simple file names" }
        }
    }

    @JvmStatic
    fun load(index:StructureIndex,contents:Map<String,String>):StructureLibrary {
        paths(index)
        fun read(path:String):StructureBlueprint {
            val b=WorldsmithJson.decode<StructureBlueprint>(requireNotNull(contents[path]))
            require(path=="structures/${b.id}.json") { "Blueprint id must match its file name" }
            return b
        }
        val definitions=index.structures.map { entry ->
            val blueprint=read(entry.blueprint)
            val assembly=entry.assembly?.let {a->StructureAssembly(a.pieces.mapValues {(id,path)->read(path).also {require(it.id==id)}},a.pools,a.variants,a.maxPieces,a.maxDepth,a.maxRadius)}
            WorldStructureDefinition(entry.id,blueprint,entry.placement,assembly)
        }
        return StructureLibrary(index.schemaVersion,definitions)
    }
}
