package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.ability.AbilityLibrary
import com.wjz.worldsmith.core.ability.AbilityProgramDefinition
import com.wjz.worldsmith.core.content.CreatureAbilityBinding
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import java.nio.file.Files
import java.nio.file.Path

/** Three independent scripts compose presentation and projectile APIs, never a renderer-side named skill. */
object AbilityVisualExample {
    const val SHOWCASE = "visual_showcase"
    const val INTRUDER = "visual_intruder"
    const val STEERING = "projectile_control_demo"
    const val RETIRE = "projectile_retire_demo"

    @JvmStatic fun create() = MechanicDiscoveryExample.create().let { base ->
        val original = AbilityRuntimeExample.create()
        val guardian = original.creatures.creatures.single().copy(displayName = "演出石偶",
            ability = CreatureAbilityBinding(SHOWCASE, range = 8.0, cooldownTicks = 80, cancelOnTargetLoss = false),
            attributes = original.creatures.creatures.single().attributes.copy(attackDamage = 0.0),
            themeRole = "程序驱动粒子、线束、物品展示和任意骨骼轨道；此样板以可见性验收为主。")
        val theme = original.theme.copy(title = "源代码小剧场", premise = "同一个有界运行时负责自由路径、资产粒子、物品展示与骨骼关键帧。")
        WorldContentBundleIO.create("源代码小剧场 · 表现与投射物样板", "自定义粒子、任意路径、物品Display、可编程骨骼与owned projectile控制；工程样板而非成品战斗。",
            original.terrain, original.biomes, original.features, original.structures, theme, blocks = original.blocks,
            creatures = original.creatures.copy(creatures = listOf(guardian)), assets = original.assets, items = original.items,
            quests = original.quests, representativeContent = original.manifest.representativeContent, mechanics = original.mechanics,
            abilities = AbilityLibrary(programs = original.abilities.programs + programs(base.items.items.single().textureAsset)))
    }

    @JvmStatic fun programs(texture: String): List<AbilityProgramDefinition> = listOf(
        AbilityProgramDefinition(SHOWCASE, "源码表现小剧场", """
            on start {
                let movement = 0;
                if (entity.kind(self) == "creature") {
                    movement = control.claim(50, 180);
                    if (movement == 0) { return; }
                }
                motion.stop(self);
                state.path = fx.path([vector.add(origin, vec(-2, 1, 2)), vector.add(origin, vec(0, 3, 2)), vector.add(origin, vec(2, 1, 2))], 0.12, 16758060, 160);
                state.item = fx.item("worldsmith:item/echo_wand", vector.add(origin, vec(2, 1, 0)), vec(0, 0, 0), vec(1.5, 1.5, 1.5), 160);
                state.particles = fx.particles("$texture", vector.add(origin, vec(-2, 1, 0)), vec(0, 0.015, 0), 24, 160, 1.5, 5631743);
                state.clip = animation.play([["left_arm", [[0, vec(0,0,0), vec(0,0,0), vec(1,1,1)], [24, vec(0,0,0), vec(-140,0,0), vec(1,1,1)], [120, vec(0,0,0), vec(-140,0,0), vec(1,1,1)], [160, vec(0,0,0), vec(0,0,0), vec(1,1,1)]]]], 160, 8);
                state.created = true;
                wait 40;
                state.transformed = fx.transform(state.item, vector.add(origin, vec(2, 2, 0)), vec(0, 90, 20), vec(2,2,2), 10);
                state.overlay = animation.play([["right_arm", [[0, vec(0,0,0), vec(0,0,0), vec(1,1,1)], [5, vec(0,0,0), vec(-150,0,0), vec(1,1,1)], [25, vec(0,0,0), vec(-150,0,0), vec(1,1,1)], [30, vec(0,0,0), vec(0,0,0), vec(1,1,1)]]]], 30, 3);
                state.overlay_started = true;
                wait 30;
                state.overlay_finished = true;
                wait 20;
                state.path_removed = fx.remove(state.path);
                state.clip_stopped = animation.stop(state.clip);
                state.cleared = true;
                wait 80;
                state.done = true;
                if (movement != 0) { control.release(movement); }
            }
        """.trimIndent(), 240, 4096),
        AbilityProgramDefinition(INTRUDER, "跨调用句柄验证", """
            on start { state.ready = true; wait 140; }
            on signal {
                if (event_tag == "visual") {
                    state.foreign_remove = fx.remove(map.get(event_data, "item"));
                    state.foreign_transform = fx.transform(map.get(event_data, "item"), origin, vec(0,0,0), vec(1,1,1), 0);
                    state.foreign_clip_stop = animation.stop(map.get(event_data, "clip"));
                    state.visual_checked = true;
                } else {
                    state.foreign_velocity = projectile.velocity(map.get(event_data, "projectile"), vec(1,0,0));
                    state.foreign_steer = projectile.steer(map.get(event_data, "projectile"), vec(1,0,0), 0.2);
                    state.foreign_retire = projectile.retire(map.get(event_data, "projectile"));
                    state.projectile_checked = true;
                }
            }
        """.trimIndent(), 240, 2048),
        AbilityProgramDefinition(STEERING, "源码控制投射物", """
            on start {
                state.projectile = projectile.emit(vector.add(origin, vec(0,1.2,0)), vec(0,0,0.3), 0, 80, "normal");
                wait 4;
                state.velocity_changed = projectile.velocity(state.projectile, vec(0.05,0,0.3));
                state.steered = projectile.steer(state.projectile, vec(0,0,0.4), 0.5);
                state.controlled = true;
                wait 60;
            }
            on projectile_hit {
                state.hit = true;
                state.normal = map.get(event_data, "normal");
                state.normal_kind = map.get(event_data, "normal_kind");
                state.incoming = map.get(event_data, "incoming_velocity");
                state.block_id = map.get(event_data, "block_id");
                state.hit_projectile = map.get(event_data, "projectile");
            }
        """.trimIndent(), 160, 2048),
        AbilityProgramDefinition(RETIRE, "主动退休投射物", """
            on start {
                state.projectile = projectile.emit(vector.add(origin, vec(0,3,0)), vec(0,0.2,0), 0, 100, "retire");
                wait 4;
                state.retired = projectile.retire(state.projectile);
                state.retired_twice = projectile.retire(state.projectile);
                state.done = true;
            }
        """.trimIndent(), 160, 1024),
    )

    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: AbilityVisualExample [output-directory]" }
        val output = Path.of(args.firstOrNull() ?: "build/ability-runtime/expansion/visual-example").toAbsolutePath().normalize()
        Files.createDirectories(output)
        val pack = create()
        val file = output.resolve("visual-playground.wspack")
        val archive = WorldsmithResourceArchive.write(pack, file)
        pack.abilities.programs.filter { it.id in setOf(SHOWCASE, INTRUDER, STEERING, RETIRE) }.forEach { program ->
            Files.writeString(output.resolve("${program.id}.ability"), program.source)
        }
        println("archive=$file\nbundleId=${pack.computedId}\narchiveSha256=${archive.archiveSha256}")
    }
}
