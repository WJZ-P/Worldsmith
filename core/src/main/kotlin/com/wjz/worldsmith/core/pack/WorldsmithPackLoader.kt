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
import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.structure.StructureLibrary

fun interface WorldsmithPackSource {
    fun readText(relativePath: String): String
    fun readBytes(relativePath: String): ByteArray = error("Binary resources are not supported by this pack source")
}

class DirectoryWorldsmithPackSource(root: Path) : WorldsmithPackSource {
    private val root = root.toAbsolutePath().normalize()

    override fun readText(relativePath: String): String {
        return readBounded(relativePath, WorldContentBundleIO.MAX_TEXT_BYTES).toString(StandardCharsets.UTF_8)
    }
    override fun readBytes(relativePath:String):ByteArray {
        return readBounded(relativePath, com.wjz.worldsmith.core.draw.DrawSnapshotCodec.MAX_BYTES)
    }
    private fun readBounded(relativePath: String, limit: Int): ByteArray {
        require(WorldContentRegistry.validRelativePath(relativePath)) { "Invalid pack relative path" }
        val target=root.resolve(relativePath).normalize()
        require(target.startsWith(root) && target.toRealPath().startsWith(root.toRealPath()) && !Files.isSymbolicLink(target) && Files.isRegularFile(target)) { "Pack path escapes its root" }
        require(Files.size(target) <= limit) { "Pack file too large" }
        return Files.newInputStream(target).use { it.readNBytes(limit + 1) }.also { require(it.size <= limit) { "Pack file grew beyond budget" } }
    }
}

class ClasspathWorldsmithPackSource(
    root: String,
    private val classLoader: ClassLoader = ClasspathWorldsmithPackSource::class.java.classLoader,
) : WorldsmithPackSource {
    private val root = root.trim('/').also { require(it.isNotBlank()) { "Classpath pack root must not be blank" } }

    override fun readText(relativePath: String): String {
        return readBounded(relativePath, WorldContentBundleIO.MAX_TEXT_BYTES).toString(StandardCharsets.UTF_8)
    }
    override fun readBytes(relativePath:String):ByteArray {
        return readBounded(relativePath, com.wjz.worldsmith.core.draw.DrawSnapshotCodec.MAX_BYTES)
    }
    private fun readBounded(relativePath: String, limit: Int): ByteArray {
        require(WorldContentRegistry.validRelativePath(relativePath)) { "Invalid classpath pack path" }
        return requireNotNull(classLoader.getResourceAsStream("$root/$relativePath")) { "Pack resource '$root/$relativePath' was not found" }.use {
            it.readNBytes(limit + 1).also { bytes -> require(bytes.size <= limit) { "Pack resource too large" } }
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
        WorldContentBundleIO.validateManifest(manifest)
        val contents = linkedMapOf<String, String>()
        var textBytes = 0L
        fun readText(path: String) {
            if (path in contents) return
            val text = source.readText(path)
            textBytes += text.toByteArray(StandardCharsets.UTF_8).size
            require(textBytes <= WorldContentBundleIO.MAX_TEXT_BYTES) { "Bundle text budget exceeded while reading '$path'" }
            contents[path] = text
        }
        manifest.modules.values.forEach { readText(it.path) }
        val index = WorldsmithJson.decode<StructureIndex>(contents.getValue(manifest.modulePath("structures")))
        StructurePackIO.paths(index).forEach(::readText)
        val terrain = WorldsmithJson.decode<TerrainPlan>(contents.getValue(manifest.modulePath("terrain")))
        val biomes = WorldsmithJson.decode<BiomePlan>(contents.getValue(manifest.modulePath("biomes")))
        val features = WorldsmithJson.decode<FeatureLibrary>(contents.getValue(manifest.modulePath("features")))
        val theme = WorldsmithJson.decode<WorldTheme>(contents.getValue(manifest.modulePath("theme")))
        val blocks = WorldsmithJson.decode<CustomBlockLibrary>(contents.getValue(manifest.modulePath("blocks")))
        val creatures = WorldsmithJson.decode<CreatureLibrary>(contents.getValue(manifest.modulePath("creatures")))
        require(index.artifacts.size<=512 && index.artifacts.all { (id,v)->id.matches(Regex("[a-f0-9]{64}")) && v.id==id })
        var drawingBytes = 0L
        val binaries=index.artifacts.values.associate { artifact ->
            val bytes = source.readBytes(artifact.path)
            drawingBytes += bytes.size
            require(drawingBytes <= WorldContentBundleIO.MAX_DRAWING_BYTES) { "Frozen drawing byte budget exceeded" }
            artifact.path to bytes
        }.toMutableMap()
        manifest.assets.forEach { asset -> binaries.getOrPut(requireNotNull(asset.path)) { source.readBytes(asset.path) } }
        val computedId = WorldsmithHashUtil.computeGenerationId(manifest, contents,binaries)
        return WorldsmithPack(manifest, terrain, biomes, features, computedId, StructurePackIO.load(index, contents,binaries),
            theme, blocks, creatures, manifest.assets.associate { it.id to binaries.getValue(requireNotNull(it.path)) })
    }
}
