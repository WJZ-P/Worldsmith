package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.draw.DrawSnapshotCodec

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
                StructureAssemblyIndex(a.pieces.mapValues {(id,b)->require(id==b.id);save(b)},a.pools,a.variants,a.maxPieces,a.maxDepth,a.maxRadius,a.terrainFollowing,a.maxElevationDifference,a.roads)
            }
            StructureIndexEntry(structure.id,file,structure.placement,assembly)
        }
        contents[INDEX_FILE]=WorldsmithJson.encode(StructureIndex(library.schemaVersion,entries,library.architecture,library.artifacts,library.sources))
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
    @JvmOverloads fun load(index:StructureIndex,contents:Map<String,String>,binaries:Map<String,ByteArray> = emptyMap()):StructureLibrary {
        paths(index)
        fun read(path:String):StructureBlueprint {
            val b=WorldsmithJson.decode<StructureBlueprint>(requireNotNull(contents[path]))
            require(path=="structures/${b.id}.json") { "Blueprint id must match its file name" }
            return b
        }
        val definitions=index.structures.map { entry ->
            val blueprint=read(entry.blueprint)
            val assembly=entry.assembly?.let {a->StructureAssembly(a.pieces.mapValues {(id,path)->read(path).also {require(it.id==id)}},a.pools,a.variants,a.maxPieces,a.maxDepth,a.maxRadius,a.terrainFollowing,a.maxElevationDifference,a.roads)}
            WorldStructureDefinition(entry.id,blueprint,entry.placement,assembly)
        }
        require(index.artifacts.size<=512 && index.sources.size<=64) { "Drawing/source catalog budget exceeded" }
        require(index.sources.values.sumOf { s->s.files.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } }<=8*1024*1024) { "Source provenance budget exceeded" }
        require(index.schemaVersion==2 || index.artifacts.isEmpty()) { "SDK drawings require structure format 2" }
        val drawings=index.artifacts.mapValues { (id,meta)->
            require(id.matches(Regex("[0-9a-f]{64}")) && meta.id==id && meta.sourceHash in index.sources && meta.codecVersion==DrawSnapshotCodec.VERSION) { "Invalid drawing artifact manifest" }
            val bytes=requireNotNull(binaries[meta.path]) { "Missing drawing '${meta.path}'" }
            require(DrawSnapshotCodec.hash(bytes)==meta.dataHash) { "Frozen drawing hash mismatch" }
            DrawSnapshotCodec.decode(bytes)
        }
        return StructureLibrary(index.schemaVersion,definitions,index.architecture,index.artifacts,index.sources,drawings)
    }

    @JvmStatic fun binaryFiles(library:StructureLibrary):Map<String,ByteArray> = library.artifacts.map { (id,meta)->
        require(id.matches(Regex("[0-9a-f]{64}")) && meta.id==id)
        val bytes=DrawSnapshotCodec.encode(requireNotNull(library.drawingAssets[id]) { "Missing frozen drawing '$id'" })
        require(DrawSnapshotCodec.hash(bytes)==meta.dataHash) { "Frozen drawing changed since construction" };meta.path to bytes
    }.toMap()
}
