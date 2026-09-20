package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.ability.AbilityLibrary
import com.wjz.worldsmith.core.ability.AbilityProgramDefinition
import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.*
import java.nio.file.Files
import java.nio.file.Path

/** Source-authored behavior on three hosts, inside the existing small physical exploration slice. */
object AbilityRuntimeExample {
    const val ECHO = "echo_positions"
    const val CHANNEL = "fragile_channel"
    const val ORB = "branching_orb"
    const val ECHO_WAND = "echo_wand"
    const val CHANNEL_WAND = "channel_wand"
    const val ORB_WAND = "orb_wand"
    const val PEDESTAL = "echo_pedestal"
    @JvmField val PEDESTAL_POSITION = BuildPos(11, 1, 5)

    @JvmStatic fun source(id: String): String {
        require(id in setOf(ECHO, CHANNEL, ORB)) { "Unknown bundled source example" }
        return requireNotNull(javaClass.getResourceAsStream("/worldsmith/abilities/examples/$id.ability"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @JvmStatic fun programs(): AbilityLibrary = AbilityLibrary(programs = listOf(
        AbilityProgramDefinition(ECHO, "位置回声", source(ECHO), maxTicks = 400, maxOperations = 8192),
        AbilityProgramDefinition(CHANNEL, "可打断的蓄能", source(CHANNEL), maxTicks = 400, maxOperations = 8192),
        AbilityProgramDefinition(ORB, "带回调的分裂火花", source(ORB), maxTicks = 400, maxOperations = 8192),
    ))

    @JvmStatic fun create(): WorldsmithPack {
        val base = MechanicDiscoveryExample.create()
        val guardian = base.creatures.creatures.single().let { creature -> creature.copy(
            displayName = "记忆守灯人",
            attributes = creature.attributes.copy(health = 60.0, speed = 0.16, attackDamage = 3.0),
            boss = CreatureBossProfile(phases = emptyList(), barTitle = "记忆守灯人", barColor = CreatureBossBarColor.YELLOW),
            ability = CreatureAbilityBinding(ECHO, range = 8.0, cooldownTicks = 40),
            themeRole = "通过同一 AbilityScript 程序记录目标的位置后逐一引爆；没有硬编码招式或固定阶段表。",
            model = creature.model.copy(bones = creature.model.bones + listOf(
                CreatureBone("left_arm", parent = "body", pivot = CreatureVector(5f, -16f), role = CreatureBoneRole.ARM_LEFT,
                    cubes = listOf(CreatureCube(CreatureVector(-1f, -1f, -2f), CreatureVector(3f, 10f, 4f), CreatureUv(42, 0)))),
                CreatureBone("right_arm", parent = "body", pivot = CreatureVector(-5f, -16f), role = CreatureBoneRole.ARM_RIGHT,
                    cubes = listOf(CreatureCube(CreatureVector(-2f, -1f, -2f), CreatureVector(3f, 10f, 4f), CreatureUv(42, 16)))),
            )),
        ) }
        val icon = base.items.items.single().textureAsset
        fun wand(id: String, name: String, program: String, description: String) = CustomItemDefinition(
            id, name, icon, kind = CustomItemKind.RELIC, maxStackSize = 1, rarity = CustomItemRarity.RARE,
            description = description, themeRole = "玩家 USE 入口运行随包保存的 $program 源码。",
            actions = listOf(ItemAction(cooldownTicks = 120, effects = listOf(ItemEffect.RunProgram(program)))),
        )
        val items = CustomItemLibrary(3, base.items.items + listOf(
            wand(ECHO_WAND, "回声试杖", ECHO, "主手使用：记录瞄准位置，随后逐点回响。与守灯人和右侧演示基座共用同一个源码程序。"),
            wand(CHANNEL_WAND, "脆响试杖", CHANNEL, "主手使用：原地蓄能；受伤或打破施法起点六格内方块会打断，并使自己短暂虚弱。"),
            wand(ORB_WAND, "分星试杖", ORB, "主手使用：发射标记为 seed 的火花；首次碰撞由源码回调生成两枚 spark，不再递归分裂。"),
        ))
        val pedestal = WorldMechanicDefinition(PEDESTAL, "回声演示基座", description =
            "空手使用右侧钻石块，可运行与守灯人和回声试杖完全相同的程序。只示范机关的程序启动入口，不是通关条件。",
            rules = listOf(WorldMechanicRule("replay", WorldMechanicEvent.USE_BLOCK,
                pattern = listOf(MechanicPatternCell(MechanicOffset(), MechanicBlockPredicate("minecraft:diamond_block"))),
                actions = listOf(MechanicAction.RunProgram(ECHO)), rotateY = false,
                cooldownTicks = 120, fromState = "idle", toState = "idle")))
        val blueprint = base.structures.structures.single().blueprint.let { original -> original.copy(
            palette = original.palette + ("ability_pedestal" to BuildMaterial("minecraft:diamond_block")),
            build = original.build + listOf(BuildOperation.SetBlock("ability_pedestal", PEDESTAL_POSITION, "ability_pedestal"),
                BuildOperation.SetBlock("ability_clue", BuildPos(11, 1, 3), "sign")),
            interactions = original.interactions.map { interaction -> when (interaction) {
                is StructureInteraction.Container -> interaction.copy(items = interaction.items + listOf(
                    StructureItem(3, "worldsmith:item/$ECHO_WAND"), StructureItem(4, "worldsmith:item/$CHANNEL_WAND"), StructureItem(5, "worldsmith:item/$ORB_WAND")))
                is StructureInteraction.Sign -> if (interaction.at == BuildPos(5, 1, 8)) interaction.copy(front =
                    listOf("守灯人记录落点", "看见光圈请移动", "回声不追踪旧点", "拾钥后打开石门")) else interaction
                else -> interaction
            } } + StructureInteraction.Sign(BuildPos(11, 1, 3), listOf("左箱三根试杖", "右侧基座可施法", "受伤打断脆响", "源码随世界保存")),
        ) }
        val quests = base.quests.copy(quests = base.quests.quests.map { it.copy(description = it.description +
            "\n守灯人会记录你站过的位置，再逐个引爆光圈；持续移动，利用石墙遮挡。补给箱另有三根源码试杖；右侧钻石基座与守卫、回声试杖共用程序。") })
        val theme = base.theme.copy(title = "记忆旧庭", premise = "灯台保存的不是招式名，而是能等待、分支、接收事件的程序。",
            worldRules = base.theme.worldRules + listOf("回声只记住采样时的位置；离开预兆光圈就能躲避。", "脆响蓄能可被受伤或附近的破坏事件打断。"),
            beats = base.theme.beats.map { it.copy(content = it.content + listOf(ContentKey("ability", ECHO), ContentKey("ability", CHANNEL), ContentKey("ability", ORB))) })
        return WorldContentBundleIO.create("记忆旧庭 · 可编程能力样板", "三份源码程序、三种宿主入口和可复现的小型探索场景；不是完整世界或最终美术。",
            base.terrain, base.biomes, base.features, base.structures.copy(structures = listOf(base.structures.structures.single().copy(blueprint = blueprint))),
            theme, blocks = base.blocks, creatures = CreatureLibrary(4, listOf(guardian)), assets = base.assets, items = items,
            quests = quests, representativeContent = ContentKey("item", ECHO_WAND),
            mechanics = base.mechanics.copy(mechanics = base.mechanics.mechanics + pedestal), abilities = programs())
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: AbilityRuntimeExample [output-directory]" }
        val output = Path.of(args.firstOrNull() ?: "build/ability-runtime").toAbsolutePath().normalize()
        val pack = create()
        StructureGeometryCompiler.compile(pack.structures.structures.single().blueprint)
        val archive = output.resolve("programmed-trial.wspack")
        val info = WorldsmithResourceArchive.write(pack, archive)
        val bundle = output.resolve("programmed-trial-bundle")
        val encoded = WorldContentBundleIO.encode(pack)
        (encoded.texts + ("worldsmith.json" to WorldsmithJson.encode(encoded.manifest))).forEach { (name, text) ->
            bundle.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
        }
        encoded.binaries.forEach { (name, bytes) -> bundle.resolve(name).also { Files.createDirectories(it.parent); Files.write(it, bytes) } }
        pack.abilities.programs.forEach { program -> output.resolve("sources/${program.id}.ability").also {
            Files.createDirectories(it.parent); Files.writeString(it, program.source)
        } }
        require(WorldsmithPackLoader.loadDirectory(bundle).computedId == pack.computedId)
        println("archive=$archive\nbundle=$bundle\nbundleId=${pack.computedId}\narchiveSha256=${info.archiveSha256}")
    }
}
