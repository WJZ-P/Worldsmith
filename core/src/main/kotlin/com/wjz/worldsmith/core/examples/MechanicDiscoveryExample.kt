package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** A reproducible, deliberately small exploration slice, not a COMPLETE_WORLD showcase. */
object MechanicDiscoveryExample {
    const val ALTAR = "lantern_altar"
    const val GATE = "lantern_gate"
    const val GUARDIAN = "lantern_guardian"
    const val KEY = "lantern_key"
    const val QUEST = "lantern_path"
    @JvmField val ALTAR_POSITION = BuildPos(7, 1, 6)
    @JvmField val GATE_POSITION = BuildPos(5, 1, 13)
    @JvmField val MATERIALS_POSITION = BuildPos(3, 1, 4)
    @JvmField val ENTRY_SIGN_POSITION = BuildPos(4, 1, 2)

    @JvmStatic fun create(): WorldsmithPack {
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val shape = base.terrain.shape as TerrainShape.Procedural
        // Gentle land and no caves/trees near the little courtyard. The fixed anchor is a
        // discoverable reference, not a promise that every seed's native spawn is identical.
        val terrain = base.terrain.copy(seed = 20260915L, spawnTargets = listOf(ClimateBox()), shape = shape.copy(
            landRatio = 0.95, relief = ReliefDistribution(1.0, 0.0, 0.0), verticalScale = 0.3,
            caves = shape.caves.copy(tunnelDensity = 0.0, cavernDensity = 0.0, noodleDensity = 0.0, entranceDensity = 0.0),
            hydrology = shape.hydrology.copy(riverCoverage = 0.0, lakeDensity = 0.0),
            anchors = listOf(Anchor("lantern_court", AnchorPlacement.Fixed(0, 0), 128, 8.0,
                climateBias = AnchorClimateBias(continentalness = 0.6, erosion = 0.8))),
        ))
        val biomes = base.biomes.copy(biomes = base.biomes.biomes.map { it.copy(features = emptyList(),
            surface = it.surface.copy(rules = it.surface.rules.filter { rule -> rule.conditions.hydrology == null })) })
        val atlas = png(false)
        val icon = png(true)
        val atlasHash = ContentAssetValidation.hash(atlas)
        val iconHash = ContentAssetValidation.hash(icon)
        val items = CustomItemLibrary(items = listOf(CustomItemDefinition(KEY, "余烬铜钥", iconHash,
            kind = CustomItemKind.RELIC, rarity = CustomItemRarity.UNCOMMON,
            description = "守灯人留下的铜钥。主手持有，对通路左侧的錾制石砖基座使用；一次消耗一把。",
            themeRole = "守卫死亡的确定性掉落，也是石门的唯一机关代价。")))
        val creatures = CreatureLibrary(creatures = listOf(CreatureDefinition(GUARDIAN, "守灯石偶", CreatureCategory.HOSTILE,
            CreatureModel(atlasHash, 64, 64, listOf(
                CreatureBone("body", pivot = CreatureVector(y = 24f), cubes = listOf(
                    CreatureCube(CreatureVector(-4f, -18f, -3f), CreatureVector(8f, 11f, 6f)))),
                CreatureBone("head", parent = "body", pivot = CreatureVector(y = -18f), role = CreatureBoneRole.HEAD,
                    cubes = listOf(CreatureCube(CreatureVector(-4f, -7f, -4f), CreatureVector(8f, 7f, 8f), CreatureUv(0, 18)))),
                CreatureBone("left_leg", parent = "body", pivot = CreatureVector(2f, -7f), role = CreatureBoneRole.LEG_LEFT,
                    cubes = listOf(CreatureCube(CreatureVector(-1.5f, 0f, -2f), CreatureVector(3f, 7f, 4f), CreatureUv(32, 0)))),
                CreatureBone("right_leg", parent = "body", pivot = CreatureVector(-2f, -7f), role = CreatureBoneRole.LEG_RIGHT,
                    cubes = listOf(CreatureCube(CreatureVector(-1.5f, 0f, -2f), CreatureVector(3f, 7f, 4f), CreatureUv(32, 12)))),
            )), attributes = CreatureAttributes(health = 12.0, speed = 0.16, followRange = 16.0, attackDamage = 2.0, width = 0.7f, height = 1.6f),
            behavior = CreatureBehavior(territoryRadius = 16, windupTicks = 24, recoveryTicks = 35),
            drops = listOf(CreatureDrop("worldsmith:item/$KEY", requirePlayerKill = true)),
            themeRole = "由补完祭台召来的入门守卫；不自然刷新，不使用 Boss 数值伪装难度。")))
        val mechanics = WorldMechanicLibrary(mechanics = listOf(
            WorldMechanicDefinition(ALTAR, "残缺灯台", rules = listOf(WorldMechanicRule("complete", WorldMechanicEvent.BLOCK_PLACED,
                pattern = listOf(
                    cell(0, 0, 0, "lodestone"), cell(-1, 0, 0, "copper_block", true), cell(1, 0, 0, "iron_block", true),
                    cell(0, 1, 0, "air"), cell(0, 2, 0, "air")),
                actions = listOf(MechanicAction.SpawnCreature(GUARDIAN, MechanicOffset(y = 1))), rotateY = false)),
                description = "磁石是中央锚点。按日志构型补上缺少的铁块；铜块与铁块在召唤成功时消耗，磁石保留。先从左侧补给箱取剑与食物，祭台上方保持空旷。"),
            WorldMechanicDefinition(GATE, "守灯石门", rules = listOf(WorldMechanicRule("open", WorldMechanicEvent.USE_BLOCK,
                pattern = listOf(cell(0, 0, 0, "chiseled_stone_bricks"), cell(2, 0, 1, "iron_bars"), cell(2, 1, 1, "iron_bars")),
                heldItem = MechanicItemCost("worldsmith:item/$KEY"), rotateY = false,
                actions = listOf(MechanicAction.SetBlock(MechanicOffset(2, 0, 1), MechanicBlockPredicate("minecraft:air")),
                    MechanicAction.SetBlock(MechanicOffset(2, 1, 1), MechanicBlockPredicate("minecraft:air"))))),
                description = "入口左侧的錾制石砖是锚点，不是铁栏杆。击败守灯石偶、拾取余烬铜钥后，主手持钥使用基座。石门只打开一次。"),
        ))
        val quests = QuestLibrary(quests = listOf(Quest(QUEST, "让灯火照进旧庭",
            "寻找原点附近的石砖灯柱庭院（中心 X=0、Z=0），从无墙的一端进入。按 J 翻开日志，可随时打开两个机关目标的说明；不必先摆对。\n" +
                "先读入口刻文，打开左侧补给箱，取铁块、剑与食物。对照残缺灯台的分层图补阵，击败现身的守灯石偶并拾取余烬铜钥。\n" +
                "带钥匙到庭院另一端，对铁门左侧的錾制石砖基座使用；走进门后的小庭院。让旧庭的灯火见证你读懂了守灯人的誓言。",
            objectives = listOf(QuestObjective.ActivateMechanic(ALTAR), QuestObjective.ActivateMechanic(GATE)),
            rewards = listOf(QuestReward("minecraft:golden_carrot", 2)), themeBeat = "rekindle")))
        val blueprint = blueprint()
        val structures = StructureLibrary(structures = listOf(WorldStructureDefinition("lantern_court", blueprint,
            StructurePlacement(biomes = biomes.biomes.map { it.id }, rotations = listOf(BuildRotation.NONE),
                anchor = StructureAnchorTarget("lantern_court"),
                terrainFit = StructureTerrainFit(maxHeightDifference = 12,
                    foundation = StructureFoundation(FoundationMode.FILL, "stone", 16), earthwork = StructureEarthwork(8, 8192))))))
        val theme = WorldTheme(title = "灯火旧庭", premise = "一座断去传承的灯台，一道等待铜钥的石门。",
            playerRole = "读懂旧庭刻文的旅人", mainConflict = "补全祭台、面对守卫，让旧庭的通路重新开放。",
            worldRules = listOf("铜铁合于磁石两侧，空旷的灯台将唤醒守卫。", "补给箱存放旧日物资，守灯石偶保管通路的铜钥。"),
            beats = listOf(WorldNarrativeBeat("rekindle", "补灯开路", "读刻文、按图补阵、拾钥开门。",
                listOf(ContentKey("structure", "lantern_court"), ContentKey("creature", GUARDIAN), ContentKey("item", KEY),
                    ContentKey("mechanic", ALTAR), ContentKey("mechanic", GATE), ContentKey("quest", QUEST)))))
        return WorldContentBundleIO.create(theme.title + " · 交互探索样板", "含实体刻文、补给箱和两种机关的小型验证路线；并非完整随机世界或 Boss 成品。",
            terrain, biomes, base.features, structures, theme, creatures = creatures, assets = mapOf(atlasHash to atlas, iconHash to icon),
            items = items, quests = quests, representativeContent = ContentKey("item", KEY), mechanics = mechanics)
    }

    @JvmStatic fun blueprint(): StructureBlueprint {
        fun p(x: Int, y: Int, z: Int) = BuildPos(x, y, z)
        return StructureBlueprint(id = "lantern_court", size = p(15, 9, 19), origin = p(7, 0, 9), palette = mapOf(
            "stone" to BuildMaterial("minecraft:stone_bricks"), "dark" to BuildMaterial("minecraft:deepslate_bricks"),
            "lamp" to BuildMaterial("minecraft:sea_lantern"), "anchor" to BuildMaterial("minecraft:lodestone"),
            "copper" to BuildMaterial("minecraft:copper_block"), "gate_anchor" to BuildMaterial("minecraft:chiseled_stone_bricks"),
            "bars" to BuildMaterial("minecraft:iron_bars"), "chest" to BuildMaterial("minecraft:chest", mapOf("facing" to "south")),
            "sign" to BuildMaterial("minecraft:oak_sign", mapOf("rotation" to "8"))),
            build = listOf(
                BuildOperation.Fill("foundation", p(0, 0, 0), p(14, 0, 18), "stone"),
                BuildOperation.Clear("courtyard_air", p(0, 1, 0), p(14, 8, 18)),
                BuildOperation.Fill("west_border", p(0, 1, 3), p(0, 2, 18), "dark"),
                BuildOperation.Fill("east_border", p(14, 1, 3), p(14, 2, 18), "dark"),
                BuildOperation.Fill("gate_wall", p(0, 1, 14), p(14, 3, 14), "dark"),
                BuildOperation.Fill("gate_bars", p(7, 1, 14), p(7, 2, 14), "bars"),
                BuildOperation.Fill("beacon_column", p(1, 1, 1), p(1, 6, 1), "stone"),
                BuildOperation.SetBlock("beacon", p(1, 7, 1), "lamp"),
                BuildOperation.SetBlock("altar", ALTAR_POSITION, "anchor"),
                BuildOperation.SetBlock("altar_existing_copper", p(6, 1, 6), "copper"),
                BuildOperation.SetBlock("key_pedestal", GATE_POSITION, "gate_anchor"),
                BuildOperation.SetBlock("supplies", MATERIALS_POSITION, "chest"),
                BuildOperation.SetBlock("arrival_clue", ENTRY_SIGN_POSITION, "sign"),
                BuildOperation.SetBlock("altar_clue", p(5, 1, 8), "sign"),
                BuildOperation.SetBlock("gate_clue", p(4, 1, 12), "sign"),
                BuildOperation.SetBlock("ending_clue", p(7, 1, 17), "sign"),
                BuildOperation.SetBlock("left_gate_light", p(4, 3, 14), "lamp"),
                BuildOperation.SetBlock("right_gate_light", p(10, 3, 14), "lamp")),
            keepClear = listOf(BuildBox(p(0, 1, 0), p(14, 8, 18))),
            // The closed gate intentionally separates the final court: it is not a pre-open access promise.
            access = StructureAccess(listOf(p(7, 1, 0)), listOf(p(3, 1, 3), p(7, 1, 5), p(5, 1, 12))),
            interactions = listOf(
                StructureInteraction.Container(MATERIALS_POSITION, items = listOf(StructureItem(0, "minecraft:iron_block"),
                    StructureItem(1, "minecraft:iron_sword"), StructureItem(2, "minecraft:bread", 8))),
                StructureInteraction.Sign(ENTRY_SIGN_POSITION, listOf("灯火旧庭", "按 J 翻日志", "机关目标可看图", "左箱取材与剑")),
                StructureInteraction.Sign(p(5, 1, 8), listOf("磁石为阵心", "西铜东铁补缺", "上方留空唤守卫", "击败后拾铜钥")),
                StructureInteraction.Sign(p(4, 1, 12), listOf("主手持铜钥", "使用左侧石基", "不是点击铁栏", "图纸可查缺漏")),
                StructureInteraction.Sign(p(7, 1, 17), listOf("灯火再入旧庭", "旅程目标已完成", "日志领取馈赠", "感谢读懂旧誓"))))
    }

    private fun cell(x: Int, y: Int, z: Int, block: String, consume: Boolean = false) =
        MechanicPatternCell(MechanicOffset(x, y, z), MechanicBlockPredicate("minecraft:$block"), consume)

    /** Small deterministic fixture textures, deliberately not presented as finished authored art. */
    private fun png(key: Boolean): ByteArray {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        for (x in 0..63) for (y in 0..63) {
            val ring = (x - 21) * (x - 21) + (y - 20) * (y - 20)
            val keyPixel = ring in 64..144 || x in 18..24 && y in 29..51 || x in 24..35 && y in 43..49
            image.setRGB(x, y, if (key) { if (keyPixel) 0xffdfaa56.toInt() else 0 }
                else if ((x / 4 + y / 4) % 2 == 0) 0xffb99a68.toInt() else 0xff625849.toInt())
        }
        return ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: MechanicDiscoveryExample [output-directory]" }
        val output = Path.of(args.firstOrNull() ?: "build/mechanic-discovery").toAbsolutePath().normalize()
        val pack = create()
        // Compile the exact same geometry consumed by native template export before writing any archive.
        val geometry = StructureGeometryCompiler.compile(pack.structures.structures.single().blueprint)
        val archive = output.resolve("lantern-vault.wspack")
        val info = WorldsmithResourceArchive.write(pack, archive)
        val bundle = output.resolve("lantern-vault-bundle")
        val encoded = WorldContentBundleIO.encode(pack)
        Files.createDirectories(bundle)
        (encoded.texts + ("worldsmith.json" to WorldsmithJson.encode(encoded.manifest))).forEach { (name, text) ->
            bundle.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
        }
        encoded.binaries.forEach { (name, bytes) -> bundle.resolve(name).also { Files.createDirectories(it.parent); Files.write(it, bytes) } }
        require(WorldsmithPackLoader.loadDirectory(bundle).computedId == pack.computedId)
        println("archive=$archive\nbundle=$bundle\nbundleId=${pack.computedId}\narchiveSha256=${info.archiveSha256}\nvoxels=${geometry.voxels.size}")
    }
}
