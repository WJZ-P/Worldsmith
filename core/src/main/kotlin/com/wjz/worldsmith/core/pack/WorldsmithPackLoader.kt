package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.model.BiomeLayoutPlan
import com.wjz.worldsmith.core.model.BiomeSkinSet
import com.wjz.worldsmith.core.model.TerrainPlan
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.model.WorldsmithPackManifest
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

fun interface WorldsmithPackSource {
    fun readText(relativePath: String): String
}

class DirectoryWorldsmithPackSource(root: Path) : WorldsmithPackSource {
    private val root = root.toAbsolutePath().normalize()

    override fun readText(relativePath: String): String {
        val target = root.resolve(relativePath).normalize()
        require(target.startsWith(root)) { "Pack path escapes its root: $relativePath" }
        return Files.readString(target, StandardCharsets.UTF_8)
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
}

object WorldsmithPackLoader {
    private const val MANIFEST = "worldsmith.json"

    @JvmStatic
    fun loadDirectory(root: Path): WorldsmithPack = load(DirectoryWorldsmithPackSource(root))

    @JvmStatic
    fun loadClasspath(root: String): WorldsmithPack = load(ClasspathWorldsmithPackSource(root))

    fun load(source: WorldsmithPackSource): WorldsmithPack {
        val manifest = WorldsmithJson.decode<WorldsmithPackManifest>(source.readText(MANIFEST))
        val terrain = WorldsmithJson.decode<TerrainPlan>(source.readText(manifest.files.terrain))
        val biomeLayout = WorldsmithJson.decode<BiomeLayoutPlan>(source.readText(manifest.files.biomeLayout))
        val biomeSkins = WorldsmithJson.decode<BiomeSkinSet>(source.readText(manifest.files.biomeSkins))
        return WorldsmithPack(manifest, terrain, biomeLayout, biomeSkins)
    }
}
