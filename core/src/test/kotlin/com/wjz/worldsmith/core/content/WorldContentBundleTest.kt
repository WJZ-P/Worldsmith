package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.pack.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import java.nio.ByteBuffer
import java.util.zip.CRC32

class WorldContentBundleTest {
    @TempDir lateinit var temp: Path
    private fun png(size: Int = 32, color: Int = 0xff826ab2.toInt()): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until size) for (x in 0 until size) image.setRGB(x, y, color)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
    private fun bundle(bytes: ByteArray = png()): WorldsmithPack {
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val texture = ContentAssetValidation.hash(bytes)
        val blocks = CustomBlockLibrary(blocks = listOf(CustomBlockDefinition("moonstone", "Moonstone", textureAsset = texture, light = 8)))
        val creatures = CreatureLibrary(creatures = listOf(CreatureDefinition("guardian", "Moonstone Guardian", CreatureCategory.HOSTILE,
            CreatureModel(texture, 32, 32, listOf(CreatureBone("body", pivot = CreatureVector(0f, 24f, 0f),
                cubes = listOf(CreatureCube(CreatureVector(-2f, -8f, -2f), CreatureVector(4f, 8f, 4f)))))),
            spawn = CreatureSpawn(biomes = listOf(base.biomes.biomes.first().id)))))
        val theme = base.theme.copy(beats = listOf(WorldNarrativeBeat("awaken", "The moon wakes", "Find the moonstone and its awakened guardian.",
            listOf(ContentKey("block", "moonstone"), ContentKey("creature", "guardian"), ContentKey("biome", base.biomes.biomes.first().id)))))
        return WorldContentBundleIO.create("Moonstone", "A connected theme", base.terrain.copy(defaultBlock = base.terrain.defaultBlock.copy(
            preferredIds = listOf("worldsmith:content/moonstone"))), base.biomes, base.features, base.structures,
            theme, blocks, creatures, mapOf(texture to bytes))
    }
    private fun freeze(pack: WorldsmithPack): WorldsmithPack = WorldContentBundleIO.encode(pack).let { pack.copy(manifest = it.manifest, computedId = it.manifest.id) }
    private fun write(pack: WorldsmithPack): WorldContentBundleFiles {
        val files = WorldContentBundleIO.encode(pack)
        Files.writeString(temp.resolve("worldsmith.json"), WorldsmithJson.encode(files.manifest))
        files.texts.forEach { (path, text) -> temp.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, text) } }
        files.binaries.forEach { (path, bytes) -> temp.resolve(path).also { Files.createDirectories(it.parent); Files.write(it, bytes) } }
        return files
    }

    @Test fun `seven typed modules and verified assets survive portable round trip`() {
        val pack = bundle()
        val files = write(pack)
        val loaded = WorldsmithPackLoader.loadDirectory(temp)
        assertEquals(3, loaded.manifest.formatVersion)
        assertEquals(WorldContentBundleIO.REQUIRED_MODULES, loaded.manifest.modules.keys)
        assertFalse(WorldsmithJson.encode(files.manifest).contains("\"files\""))
        assertEquals(pack.manifest.id, loaded.computedId)
        assertEquals(pack.theme, loaded.theme)
        assertEquals(pack.blocks, loaded.blocks)
        assertEquals(pack.creatures, loaded.creatures)
        assertTrue(WorldsmithPackValidator.validate(loaded).isEmpty(), WorldsmithPackValidator.validate(loaded).toString())
    }

    @Test fun `input and returned asset arrays do not mutate the frozen bundle`() {
        val bytes = png()
        val pack = bundle(bytes)
        val identity = pack.manifest.id
        bytes[0] = 0
        pack.assets.values.single()[0] = 0
        assertEquals(identity, WorldContentBundleIO.encode(pack).manifest.id)
        assertEquals(137.toByte(), pack.copy().assets.values.single()[0])
    }

    @Test fun `all world content including theme and textures changes immutable identity`() {
        val pack = bundle()
        assertNotEquals(pack.manifest.id, freeze(pack.copy(theme = pack.theme.copy(premise = "The moonstone is a remnant of an ocean civilization."))).manifest.id)
        assertNotEquals(pack.manifest.id, bundle(png(color = 0xff3377cc.toInt())).manifest.id)
        assertNotEquals(pack.manifest.id, freeze(pack.copy(creatures = pack.creatures.copy(creatures = pack.creatures.creatures.map {
            it.copy(attributes = it.attributes.copy(health = 70.0))
        }))).manifest.id)
        assertTrue(WorldsmithPackValidator.validate(pack.copy(theme = pack.theme.copy(title = "Changed"))).any { it.code == "PACK_CONTENT_MUTATED" })
    }

    @Test fun `local materials link to block symbols not external registries`() {
        val pack = bundle()
        val plan = ExistingWorldContentModules.registry().plan(ExistingWorldContentModules.input(pack))
        assertTrue(plan.catalogValid, plan.diagnostics.toString())
        val terrain = plan.catalog.entries.single { it.key == ContentKey("terrain", "main") }
        assertTrue(terrain.references.any { it.target == ContentKey("block", "moonstone") })
        assertFalse(terrain.nativeReferences.any { it.id.startsWith("worldsmith:content/") })
        val missing = pack.copy(blocks = CustomBlockLibrary())
        assertTrue(WorldsmithPackValidator.validate(missing).any { it.code == "CONTENT_REFERENCE_MISSING" && it.path.startsWith("terrain") })
    }

    @Test fun `narrative links need existing content not future quest placeholders`() {
        val pack = bundle()
        val invalid = pack.copy(theme = pack.theme.copy(beats = listOf(WorldNarrativeBeat("quest", "Find it", "Recover the relic.", listOf(ContentKey("quest", "relic"))))))
        assertTrue(WorldsmithPackValidator.validate(invalid).any { it.code == "CONTENT_REFERENCE_MISSING" && it.path.startsWith("theme") })
        assertTrue(WorldsmithPackValidator.validate(pack.copy(theme = WorldTheme())).any { it.code == "THEME_TEXT_INVALID" })
        val input = ExistingWorldContentModules.input(pack)
        val plan = ExistingWorldContentModules.registry().plan(input.copy(modules = input.modules + ("quests" to buildJsonObject { put("schemaVersion", 1) })))
        assertTrue(plan.diagnostics.any { it.code == "CONTENT_MODULE_UNAVAILABLE" })
    }

    @Test fun `asset corruption and missing bytes reject load before activation`() {
        val pack = bundle()
        val files = write(pack)
        val assetPath = temp.resolve(files.manifest.assets.single().path!!)
        val bytes = Files.readAllBytes(assetPath)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        Files.write(assetPath, bytes)
        assertThrows(IllegalArgumentException::class.java) { WorldsmithPackLoader.loadDirectory(temp) }
        Files.delete(assetPath)
        assertThrows(Exception::class.java) { WorldsmithPackLoader.loadDirectory(temp) }
    }

    @Test fun `MIME labels and freshly hashed corrupt PNG bytes are not trusted`() {
        val bytes = "not a texture".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { bundle(bytes) }
        val corrupt = png().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { bundle(corrupt) }
    }

    @Test fun `manifest is bounded exact and schema matched`() {
        val pack = bundle()
        val files = WorldContentBundleIO.encode(pack)
        assertThrows(IllegalArgumentException::class.java) { WorldContentBundleIO.validateManifest(pack.manifest.copy(formatVersion = 2)) }
        assertThrows(IllegalArgumentException::class.java) { WorldContentBundleIO.validateManifest(pack.manifest.copy(modules = pack.manifest.modules - "theme")) }
        val traversal = pack.manifest.modules + ("theme" to pack.manifest.modules.getValue("theme").copy(path = "../theme.json"))
        assertThrows(IllegalArgumentException::class.java) { WorldContentBundleIO.validateManifest(pack.manifest.copy(modules = traversal)) }
        val schema = files.texts + ("blocks.json" to "{\"schemaVersion\":2,\"blocks\":[]}")
        assertThrows(IllegalArgumentException::class.java) { WorldsmithHashUtil.computeGenerationId(files.manifest, schema, files.binaries) }
        assertThrows(IllegalArgumentException::class.java) { WorldsmithHashUtil.computeGenerationId(files.manifest, files.texts + ("untracked.json" to "{}"), files.binaries) }
    }

    @Test fun `valid plan does not imply native capabilities or activation`() {
        val pack = bundle()
        val registry = ExistingWorldContentModules.registry()
        val plan = registry.plan(ExistingWorldContentModules.input(pack))
        assertTrue(plan.catalogValid)
        assertFalse(plan.capabilitiesSatisfied)
        assertTrue(plan.missingCapabilities.any { it.lifecycle == ContentLifecycle.BOOTSTRAP })
        assertTrue(plan.missingCapabilities.any { it.lifecycle == ContentLifecycle.CLIENT_RESOURCES })
        assertTrue(plan.missingCapabilities.any { it.lifecycle == ContentLifecycle.WORLD_BINDING })
        val reversed = ExistingWorldContentModules.input(pack).let { it.copy(modules = it.modules.entries.reversed().associate { e -> e.toPair() }) }
        assertEquals(plan, registry.plan(reversed))
    }

    @Test fun `PNG dimensions are checked against block profiles and creature UV atlas`() {
        val pack = bundle(png(64))
        assertTrue(WorldsmithPackValidator.validate(pack).any { it.code == "CREATURE_TEXTURE_DIMENSIONS" })
        val large = bundle(png(512))
        assertTrue(WorldsmithPackValidator.validate(large).any { it.code == "BLOCK_TEXTURE_DIMENSIONS" })
    }

    @Test fun `native texture references must be the real digest rather than a hashed looking alias`() {
        val pack = bundle()
        val alias = "a".repeat(64)
        val changed = pack.copy(
            manifest = pack.manifest.copy(assets = pack.manifest.assets.map { it.copy(id = alias) }),
            blocks = pack.blocks.copy(blocks = pack.blocks.blocks.map { it.copy(textureAsset = alias) }),
            creatures = pack.creatures.copy(creatures = pack.creatures.creatures.map { it.copy(model = it.model.copy(texture = alias)) }),
            assets = mapOf(alias to pack.assets.values.single()),
        )
        val errors = WorldsmithPackValidator.validate(freeze(changed)).filter { it.code == "CONTENT_TEXTURE_ADDRESS_MISMATCH" }
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.path.startsWith("blocks.") })
        assertTrue(errors.any { it.path.startsWith("creatures.") })
    }

    @Test fun `block PNG encoded byte cap matches native resources independently of small dimensions`() {
        val source = png()
        val payload = ("Description\u0000" + "x".repeat(1024 * 1024)).toByteArray(Charsets.US_ASCII)
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val checksum = CRC32().apply { update(type); update(payload) }.value
        val chunk = ByteBuffer.allocate(payload.size + 12).putInt(payload.size).put(type).put(payload).putInt(checksum.toInt()).array()
        val bytes = source.copyOfRange(0, 33) + chunk + source.copyOfRange(33, source.size)
        val pack = bundle(bytes)
        assertTrue(bytes.size > 1024 * 1024 && bytes.size < ContentAssetValidation.MAX_ASSET_BYTES)
        assertTrue(WorldsmithPackValidator.validate(pack).any { it.code == "BLOCK_TEXTURE_BYTE_BUDGET" })
    }

    @Test fun `loader stops reading module files immediately when cumulative UTF8 budget is exceeded`() {
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val calls = mutableListOf<String>()
        val chunk = "界".repeat(WorldContentBundleIO.MAX_TEXT_BYTES / 6 + 1)
        val manifest = base.manifest.copy(modules = base.manifest.modules.toSortedMap())
        val source = WorldsmithPackSource { path ->
            calls += path
            if (path == "worldsmith.json") WorldsmithJson.encode(manifest) else chunk
        }
        val failure = assertThrows(IllegalArgumentException::class.java) { WorldsmithPackLoader.load(source) }
        assertTrue(failure.message!!.contains("text budget"))
        assertEquals(listOf("worldsmith.json") + manifest.modules.values.take(2).map { it.path }, calls,
            "No later module or blueprint may be read after the cumulative limit fails")
    }
}
