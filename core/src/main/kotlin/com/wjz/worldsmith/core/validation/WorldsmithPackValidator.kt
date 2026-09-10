package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.model.RiverFill
import com.wjz.worldsmith.core.model.AnchorPlacement
import com.wjz.worldsmith.core.model.SurfaceHydrology
import com.wjz.worldsmith.core.model.TerrainShape
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.structure.StructureValidator
import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.structure.StructureInteraction

object WorldsmithPackValidator {
    private val ID = Regex("^[0-9a-f]{64}$")

    fun validate(pack: WorldsmithPack): List<Diagnostic> = buildList {
        val manifest = pack.manifest
        if (manifest.formatVersion !in WorldContentBundleIO.LEGACY_FORMAT_VERSION..WorldContentBundleIO.FORMAT_VERSION) {
            add(error("manifest.formatVersion", "UNSUPPORTED_PACK_FORMAT", "Unsupported pack format ${manifest.formatVersion}"))
        }
        if (!ID.matches(manifest.id)) {
            add(error("manifest.id", "INVALID_PACK_ID", "Pack id must be a lowercase SHA-256"))
        } else if (!WorldsmithHashUtil.matches(manifest, pack.computedId)) {
            add(error("manifest.id", "PACK_HASH_MISMATCH", "Pack id does not match its generation content"))
        }
        if (manifest.displayName.isBlank()) {
            add(error("manifest.displayName", "EMPTY_DISPLAY_NAME", "Pack display name must not be blank"))
        }
        try { WorldContentBundleIO.validateManifest(manifest) } catch (e: IllegalArgumentException) {
            add(error("manifest", "INVALID_CONTENT_MANIFEST", e.message ?: "Invalid module manifest"))
        }
        if (manifest.formatVersion == WorldContentBundleIO.LEGACY_FORMAT_VERSION) {
            if (pack.items.items.isNotEmpty()) add(error("items", "ITEMS_REQUIRE_FORMAT4", "Format 3 contains no ordinary item module; freeze linked content as a new current-format bundle"))
            if (pack.creatures.creatures.any { it.drops.isNotEmpty() }) add(error("creatures", "CREATURE_DROPS_REQUIRE_FORMAT4", "Format 3 does not contain creature drop behavior; freeze linked content as a new current-format bundle"))
        }
        if (manifest.formatVersion < 5 && pack.quests.quests.isNotEmpty())
            add(error("quests", "QUESTS_REQUIRE_FORMAT5", "Formats 3/4 contain no quest gameplay; freeze the main line as format 5"))

        val contentPlan = ExistingWorldContentModules.registry().plan(ExistingWorldContentModules.input(pack))
        addAll(contentPlan.diagnostics)
        val assets = try { ContentAssetValidation.verifyAll(manifest.assets, pack.assets) } catch (e: Exception) {
            add(error("assets", "CONTENT_ASSET_INTEGRITY", e.message ?: "Asset integrity check failed")); emptyMap()
        }
        val descriptors = manifest.assets.associateBy { it.id }
        fun checkTextureAddress(id: String, path: String) {
            descriptors[id]?.let { asset ->
                if (asset.id != asset.sha256)
                    add(error(path, "CONTENT_TEXTURE_ADDRESS_MISMATCH", "Native texture references must be their asset's SHA-256, not a different logical alias"))
            }
        }
        pack.blocks.blocks.forEachIndexed { i, block ->
            checkTextureAddress(block.textureAsset, "blocks.blocks[$i].textureAsset")
            if ((descriptors[block.textureAsset]?.byteLength ?: 0L) > 1024 * 1024)
                add(error("blocks.blocks[$i].textureAsset", "BLOCK_TEXTURE_BYTE_BUDGET", "Native block PNG textures must be at most 1 MiB"))
            assets[block.textureAsset]?.let { size ->
                if (size.width != size.height || size.width !in 16..256 || size.width and (size.width - 1) != 0)
                    add(error("blocks.blocks[$i].textureAsset", "BLOCK_TEXTURE_DIMENSIONS", "Block textures must be square power-of-two PNGs, 16..256 pixels"))
            }
        }
        pack.items.items.forEachIndexed { i, item ->
            checkTextureAddress(item.textureAsset, "items.items[$i].textureAsset")
            if ((descriptors[item.textureAsset]?.byteLength ?: 0L) > CustomItemValidation.MAX_TEXTURE_BYTES)
                add(error("items.items[$i].textureAsset", "ITEM_TEXTURE_BYTE_BUDGET", "Ordinary item PNG icons must be at most 1 MiB"))
            assets[item.textureAsset]?.let { size ->
                if (size.width != size.height || size.width !in 16..256 || size.width and (size.width - 1) != 0)
                    add(error("items.items[$i].textureAsset", "ITEM_TEXTURE_DIMENSIONS", "Item icons must be square power-of-two PNGs, 16..256 pixels"))
            }
        }
        val itemDefinitions = pack.items.items.associateBy { it.id }
        fun checkItemStack(reference: String, maximum: Int, path: String) {
            if (reference.startsWith(ExistingWorldContentModules.LOCAL_ITEM_PREFIX)) {
                itemDefinitions[reference.removePrefix(ExistingWorldContentModules.LOCAL_ITEM_PREFIX)]?.let { item ->
                    if (maximum > item.maxStackSize) add(error(path, "CONTENT_ITEM_STACK_LIMIT", "Each reward entry is one stack: ${item.id} permits at most ${item.maxStackSize}, requested $maximum"))
                }
            }
        }
        pack.quests.quests.forEachIndexed { i, quest ->
            quest.rewards.forEachIndexed { j, reward -> checkItemStack(reward.item, reward.count, "quests.quests[$i].rewards[$j].count") }
            // Delivery counts deliberately cross stack boundaries and accumulate real contributions.
            // They are not capped to one item's maxStackSize as reward entries are.
        }
        pack.creatures.creatures.forEachIndexed { i, creature ->
            checkTextureAddress(creature.model.texture, "creatures.creatures[$i].model.texture")
            assets[creature.model.texture]?.let { size ->
                if (size.width != creature.model.textureWidth || size.height != creature.model.textureHeight)
                    add(error("creatures.creatures[$i].model.texture", "CREATURE_TEXTURE_DIMENSIONS", "PNG dimensions must match the model UV atlas"))
            }
            creature.drops.forEachIndexed { j, drop -> checkItemStack(drop.item, drop.maxCount, "creatures.creatures[$i].drops[$j].maxCount") }
        }
        pack.structures.structures.forEachIndexed { i, structure ->
            val blueprints = listOf(structure.blueprint to "structures.structures[$i].blueprint") +
                structure.assembly?.pieces.orEmpty().map { (id, blueprint) -> blueprint to "structures.structures[$i].assembly.pieces.$id" }
            blueprints.forEach { (blueprint, path) -> blueprint.interactions.forEachIndexed { j, interaction ->
                if (interaction is StructureInteraction.Container) {
                    interaction.items.forEachIndexed { k, item -> checkItemStack(item.item, item.count, "$path.interactions[$j].items[$k].count") }
                    interaction.loot?.entries?.forEachIndexed { k, entry -> checkItemStack(entry.item, entry.maxCount, "$path.interactions[$j].loot.entries[$k].maxCount") }
                }
            } }
        }
        val knownBlocks = pack.blocks.blocks.map { it.id }.toSet()
        pack.structures.drawingAssets.forEach { (drawingId, drawing) ->
            drawing.voxels().map { it.block().state().id() }.toSet().filter { it.startsWith(ExistingWorldContentModules.LOCAL_BLOCK_PREFIX) }.forEach { ref ->
                if (ref.removePrefix(ExistingWorldContentModules.LOCAL_BLOCK_PREFIX) !in knownBlocks)
                    add(error("structures.artifacts.$drawingId", "CONTENT_REFERENCE_MISSING", "Frozen drawing references missing logical block '$ref'"))
            }
        }
        if (manifest.formatVersion in WorldContentBundleIO.LEGACY_FORMAT_VERSION..WorldContentBundleIO.FORMAT_VERSION) {
            try {
                val actual = WorldContentBundleIO.encode(pack).manifest.id
                if (actual != pack.computedId) add(error("manifest.id", "PACK_CONTENT_MUTATED", "Typed content differs from its immutable loaded hash; freeze a new bundle"))
            } catch (e: Exception) {
                add(error("content", "CONTENT_BUNDLE_INVALID", e.message ?: "Bundle encoding failed"))
            }
        }

        addAll(TerrainPlanValidator.validate(pack.terrain).map { it.prefixed("terrain") })
        addAll(FeatureLibraryValidator.validate(pack.features).map { it.prefixed("features") })
        addAll(BiomePlanValidator.validate(pack.biomes, pack.features).map { it.prefixed("biomes") })
        addAll(StructureValidator.validate(pack.structures, pack.biomes).map { it.prefixed("structures") })
        pack.structures.structures.forEachIndexed {i,s->
            s.placement.terrainFit.verticalRange?.let {range->
                if(range.maxY<pack.terrain.minY || range.minY>=pack.terrain.minY+pack.terrain.height)
                    add(error("structures.structures[$i].placement.terrainFit.verticalRange","UNREACHABLE_STRUCTURE_HEIGHT","Structure height window does not intersect this world's vertical extent"))
            }
        }
        addAll(validateBiomeTerrainLinks(pack))
        addAll(validateSurfaceTerrainLinks(pack))
        addAll(validateAnchorReferences(pack))
    }

    private fun validateBiomeTerrainLinks(pack: WorldsmithPack): List<Diagnostic> = buildList {
        val spatial = pack.biomes.spatial
        if (pack.terrain.shape !is TerrainShape.Procedural &&
            (spatial.regionScale != 1.0 || spatial.boundaryRoughness != 0.0)
        ) {
            add(
                error(
                    "biomes.spatial",
                    "BIOME_SPATIAL_REQUIRES_PROCEDURAL_TERRAIN",
                    "Non-default biome spatial controls require procedural terrain",
                ),
            )
        }
    }

    /**
     * Anchor references are resolved by name at compile time and throw when a
     * name is wrong, so an unknown one has to be caught here or it becomes a
     * crash while a world is being created.
     */
    private fun validateAnchorReferences(pack: WorldsmithPack): List<Diagnostic> = buildList {
        val shape = pack.terrain.shape
        val known = if (shape is TerrainShape.Procedural) shape.anchors.mapTo(mutableSetOf()) { it.id } else emptySet()

        pack.structures.structures.forEachIndexed { index, structure ->
            val target = structure.placement.anchor ?: return@forEachIndexed
            val path = "structures.structures[$index].placement.anchor"
            if (shape !is TerrainShape.Procedural) {
                add(error(path, "ANCHOR_REQUIRES_PROCEDURAL_TERRAIN", "Structure anchor placement requires procedural terrain"))
                return@forEachIndexed
            }
            val anchor = shape.anchors.find { it.id == target.id }
            if (anchor == null) {
                add(error(path, "UNKNOWN_ANCHOR", "Structure references terrain anchor '${target.id}', which does not exist"))
                return@forEachIndexed
            }
            val placement = anchor.placement
            if (placement !is AnchorPlacement.Line && target.along != 0.5) {
                add(error("$path.along", "UNUSED_ANCHOR_ALONG", "Only a line anchor consumes along; omit it for fixed or scattered anchors"))
            }
            val position = when (placement) {
                is AnchorPlacement.Fixed -> placement.x.toDouble() to placement.z.toDouble()
                is AnchorPlacement.Line ->
                    (placement.startX + (placement.endX.toDouble() - placement.startX) * target.along) to
                    (placement.startZ + (placement.endZ.toDouble() - placement.startZ) * target.along)
                is AnchorPlacement.Scattered -> null
            }
            if (position != null && (position.first + target.offsetX !in -29_999_000.0..29_999_000.0 ||
                position.second + target.offsetZ !in -29_999_000.0..29_999_000.0)) {
                add(error(path, "STRUCTURE_ANCHOR_OUTSIDE_WORLD", "The resolved structure pivot must stay within +/-29999000 blocks"))
            }
        }

        if (shape is TerrainShape.Procedural) {
            shape.bands.forEachIndexed { index, band ->
                val name = band.anchor ?: return@forEachIndexed
                if (name !in known) {
                    add(
                        error(
                            "terrain.shape.bands[$index].anchor",
                            "UNKNOWN_ANCHOR",
                            "Band references anchor '" + name + "', which the terrain does not define",
                        ),
                    )
                }
            }
        }

        pack.biomes.biomes.forEachIndexed { biomeIndex, biome ->
            biome.surface.rules.forEachIndexed { ruleIndex, rule ->
                val band = rule.conditions.anchor ?: return@forEachIndexed
                val path = "biomes.biomes[$biomeIndex].surface.rules[$ruleIndex].conditions.anchor"
                if (shape !is TerrainShape.Procedural) {
                    add(error(path, "ANCHOR_REQUIRES_PROCEDURAL_TERRAIN", "Anchor surface conditions require procedural terrain"))
                    return@forEachIndexed
                }
                if (band.anchor !in known) {
                    add(
                        error(
                            path,
                            "UNKNOWN_ANCHOR",
                            "Surface rule references anchor '" + band.anchor + "', which the terrain does not define",
                        ),
                    )
                }
                if (band.min !in 0.0..1.0 || band.max !in 0.0..1.0) {
                    add(error(path, "ANCHOR_BAND_OUT_OF_RANGE", "Anchor influence bounds must be between 0 and 1"))
                } else if (band.min >= band.max) {
                    add(error(path, "REVERSED_RANGE", "Anchor influence must start below where it ends"))
                }
            }
        }
    }

    private fun validateSurfaceTerrainLinks(pack: WorldsmithPack): List<Diagnostic> = buildList {
        val shape = pack.terrain.shape
        val minY = pack.terrain.minY
        val maxY = minY + pack.terrain.height - 1
        pack.biomes.biomes.forEachIndexed { biomeIndex, biome ->
            biome.surface.rules.forEachIndexed { ruleIndex, rule ->
                val path = "biomes.biomes[$biomeIndex].surface.rules[$ruleIndex].conditions"
                rule.conditions.altitude?.let { altitude ->
                    if (altitude.min != null && altitude.min > maxY || altitude.max != null && altitude.max < minY) {
                        add(error("$path.altitude", "UNREACHABLE_ALTITUDE", "Altitude range does not intersect terrain height $minY..$maxY"))
                    }
                }
                val signal = rule.conditions.hydrology ?: return@forEachIndexed
                if (shape !is TerrainShape.Procedural) {
                    add(error("$path.hydrology", "HYDROLOGY_REQUIRES_PROCEDURAL_TERRAIN", "Hydrology surface conditions require procedural terrain"))
                    return@forEachIndexed
                }
                val hydrology = shape.hydrology
                when (signal) {
                    SurfaceHydrology.DRY_RIVERBED -> {
                        if (hydrology.riverCoverage == 0.0 || hydrology.riverFill != RiverFill.DRY) {
                            add(error("$path.hydrology", "UNREACHABLE_HYDROLOGY_SIGNAL", "DRY_RIVERBED requires non-zero DRY rivers"))
                        }
                    }
                    SurfaceHydrology.WET_RIVERBED -> {
                        if (hydrology.riverCoverage == 0.0 || hydrology.riverFill != RiverFill.FLUID) {
                            add(error("$path.hydrology", "UNREACHABLE_HYDROLOGY_SIGNAL", "WET_RIVERBED requires non-zero FLUID rivers"))
                        }
                    }
                    SurfaceHydrology.RIVER_BANK -> {
                        if (hydrology.riverCoverage == 0.0) {
                            add(error("$path.hydrology", "UNREACHABLE_HYDROLOGY_SIGNAL", "RIVER_BANK requires non-zero river coverage"))
                        }
                    }
                    SurfaceHydrology.LAKEBED -> {
                        if (hydrology.lakeDensity == 0.0) {
                            add(error("$path.hydrology", "UNREACHABLE_HYDROLOGY_SIGNAL", "LAKEBED requires non-zero lake density"))
                        }
                    }
                }
            }
        }
    }

    private fun Diagnostic.prefixed(prefix: String) = copy(path = "$prefix.$path")

    private fun error(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
}
