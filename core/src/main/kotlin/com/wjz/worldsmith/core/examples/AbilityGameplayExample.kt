package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.ability.AbilityLibrary
import com.wjz.worldsmith.core.ability.AbilityProgramDefinition
import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.StructureInteraction
import com.wjz.worldsmith.core.structure.StructureItem
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import java.nio.file.Files
import java.nio.file.Path

/** A reproducible source kit, not a claim of a complete world or a human-tested campaign. */
object AbilityGameplayExample {
    const val CONDUCTOR = "ward_conductor"
    const val BUILDER = "ward_builder"
    const val WITNESS = "ward_witness"
    const val SIGIL = "oath_sigil"

    @JvmStatic fun source(id: String): String {
        require(id in setOf(CONDUCTOR, BUILDER, WITNESS)) { "Unknown gameplay source example" }
        return requireNotNull(javaClass.getResourceAsStream("/worldsmith/abilities/examples/$id.ability"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @JvmStatic fun programs() = AbilityLibrary(programs = listOf(
        AbilityProgramDefinition(CONDUCTOR, "盟灯的主持者", source(CONDUCTOR), maxTicks = 260, maxOperations = 8192),
        AbilityProgramDefinition(BUILDER, "短暂的灯石", source(BUILDER), maxTicks = 160, maxOperations = 2048),
        AbilityProgramDefinition(WITNESS, "三次共鸣", source(WITNESS), maxTicks = 150, maxOperations = 4096),
    ))

    @JvmStatic fun create(): WorldsmithPack {
        val base = AbilityRuntimeExample.create()
        val sigil = CustomItemDefinition(SIGIL, "盟灯试印", base.items.items.first().textureAsset,
            kind = CustomItemKind.RELIC, maxStackSize = 1, rarity = CustomItemRarity.RARE,
            description = "主手使用：东侧两格须留空。花费三点灯息凝出短暂灯石；三次回声后，余下灯息抵挡至多三次来袭。请等待回声结束再试。",
            themeRole = "以暂存灯石、共同节拍与有限灯息展示旧庭的盟约。",
            abilityBindings = listOf(AbilityEventBinding("oath_input", CONDUCTOR, listOf("use_start"), cooldownTicks = 80)))
        val structures = base.structures.copy(structures = base.structures.structures.map { structure -> structure.copy(
            blueprint = structure.blueprint.copy(interactions = structure.blueprint.interactions.map { interaction ->
                if (interaction is StructureInteraction.Container) interaction.copy(items = interaction.items + StructureItem(6, "worldsmith:item/$SIGIL")) else interaction
            })) })
        val theme = base.theme.copy(title = "旧庭盟灯", beats = base.theme.beats.map { it.copy(content = it.content + listOf(
            ContentKey("item", SIGIL), ContentKey("ability", CONDUCTOR), ContentKey("ability", BUILDER), ContentKey("ability", WITNESS))) })
        return WorldContentBundleIO.create("旧庭盟灯 · 通用玩法源码样板", "在可编程能力样板上增加临时世界修改、灯息护盾和三个程序的场景协作；非完整世界或人类通关验收。",
            base.terrain, base.biomes, base.features, structures, theme, base.blocks, base.creatures, base.assets,
            CustomItemLibrary(4, base.items.items + sigil), base.quests, ContentKey("item", SIGIL), base.mechanics,
            AbilityLibrary(programs = base.abilities.programs + programs().programs))
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: AbilityGameplayExample [output-directory]" }
        val output = Path.of(args.firstOrNull() ?: "build/ability-runtime/expansion").toAbsolutePath().normalize()
        val pack = create()
        val errors = WorldsmithPackValidator.validate(pack).filter { it.severity == DiagnosticSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString { "${it.path}: ${it.message}" } }
        val archive = output.resolve("gameplay-trial.wspack")
        val info = WorldsmithResourceArchive.write(pack, archive)
        val files = WorldContentBundleIO.encode(pack)
        val bundle = output.resolve("gameplay-trial-bundle")
        (files.texts + ("worldsmith.json" to WorldsmithJson.encode(files.manifest))).forEach { (name, text) ->
            bundle.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
        }
        files.binaries.forEach { (name, bytes) -> bundle.resolve(name).also { Files.createDirectories(it.parent); Files.write(it, bytes) } }
        programs().programs.forEach { definition -> output.resolve("gameplay-sources/${definition.id}.ability").also {
            Files.createDirectories(it.parent); Files.writeString(it, definition.source)
        } }
        require(WorldsmithPackLoader.loadDirectory(bundle).computedId == pack.computedId)
        require(WorldsmithResourceArchive.read(archive).pack.computedId == pack.computedId)
        println("archive=$archive\nbundle=$bundle\nbundleId=${pack.computedId}\narchiveSha256=${info.archiveSha256}")
    }
}
