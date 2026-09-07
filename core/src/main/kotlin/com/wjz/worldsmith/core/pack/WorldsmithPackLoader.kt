package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.TerrainPlan
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.model.WorldsmithPackManifest
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.StructureIndex
import com.wjz.worldsmith.core.structure.StructurePackIO
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

fun interface WorldsmithPackSource {
    fun readText(relativePath: String): String
    fun readBytes(relativePath: String): ByteArray = error("Binary resources are not supported by this pack source")
}

class DirectoryWorldsmithPackSource(root: Path) : WorldsmithPackSource {
    private val root = root.toAbsolutePath().normalize()

    override fun readText(relativePath: String): String {
        val target = root.resolve(relativePath).normalize()
        require(target.startsWith(root)) { "Pack path escapes its root: $relativePath" }
        return Files.readString(target, StandardCharsets.UTF_8)
    }
    override fun readBytes(relativePath:String):ByteArray {
        val target=root.resolve(relativePath).normalize()
        require(target.startsWith(root) && target.toRealPath().startsWith(root.toRealPath()) && !Files.isSymbolicLink(target))
        require(Files.size(target)<=com.wjz.worldsmith.core.draw.DrawSnapshotCodec.MAX_BYTES) { "Drawing file too large" }
        return Files.readAllBytes(target)
    }
}

class ClasspathWorldsmithPackSource(
    root: String,
    private val classLoader: ClassLoader = ClasspathWorldsmithPackSource::class.java.classLoader,
) : WorldsmithPackSource {
    private val root = root.trim('/').also { require(it.isNotBlank()) { "Classpath pack root must not be blank" } }

    override fun readText(relativePath: String): String {
        require(!relativePath.startsWith('/') && ".." !in relativePath.split('/')) {
            "Pack path must be relative: $relativePath"
        }
        val resource = "$root/$relativePath"
        return classLoader.getResourceAsStream(resource)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
            ?: error("Pack resource '$resource' was not found")
    }
    override fun readBytes(relativePath:String):ByteArray {
        require(relativePath.matches(Regex("drawings/[a-f0-9]{64}\\.wsdraw")))
        return requireNotNull(classLoader.getResourceAsStream("$root/$relativePath")).use {
            val bytes=it.readNBytes(com.wjz.worldsmith.core.draw.DrawSnapshotCodec.MAX_BYTES+1)
            require(bytes.size<=com.wjz.worldsmith.core.draw.DrawSnapshotCodec.MAX_BYTES);bytes
        }
    }
}

object WorldsmithPackLoader {
    private const val MANIFEST = "worldsmith.json"

    @JvmStatic
    fun loadDirectory(root: Path): WorldsmithPack = load(DirectoryWorldsmithPackSource(root))

    @JvmStatic
    fun loadClasspath(root: String): WorldsmithPack = load(ClasspathWorldsmithPackSource(root))

    fun load(source: WorldsmithPackSource): WorldsmithPack {
        val manifest = WorldsmithJson.decode<WorldsmithPackManifest>(source.readText(MANIFEST))
        val contents = listOf(manifest.files.terrain, manifest.files.biomes, manifest.files.features, manifest.files.structures)
            .associateWith(source::readText).toMutableMap()
        val index = WorldsmithJson.decode<StructureIndex>(contents.getValue(manifest.files.structures))
        StructurePackIO.paths(index).forEach { contents[it] = source.readText(it) }
        val terrain = WorldsmithJson.decode<TerrainPlan>(contents.getValue(manifest.files.terrain))
        val biomes = WorldsmithJson.decode<BiomePlan>(contents.getValue(manifest.files.biomes))
        val features = WorldsmithJson.decode<FeatureLibrary>(contents.getValue(manifest.files.features))
        require(index.artifacts.size<=512 && index.artifacts.all { (id,v)->id.matches(Regex("[a-f0-9]{64}")) && v.id==id })
        val binaries=index.artifacts.values.associate { it.path to source.readBytes(it.path) }
        val computedId = WorldsmithHashUtil.computeGenerationId(manifest, contents,binaries)
        return WorldsmithPack(manifest, terrain, biomes, features, computedId, StructurePackIO.load(index, contents,binaries))
    }
}
