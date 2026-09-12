package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

/** Authoring-only lint. Existing immutable packs and saved advancement lore are never rewritten or rejected here. */
object PlayerTextPolicy {
    private val engineering = listOf(
        Regex("(?i)\\b(?:MCP|JSON|schemaVersion|expectedRevision|textureAsset|runtimeSchema|bundleId|SHA-?256|worldsmith_(?:write_pack|finish_world))\\b"),
        Regex("(?:本包|世界包).{0,20}(?:不包含|不含|不携带|不保存).{0,12}(?:玩家进度|玩家记录|存档记录)"),
        Regex("(?:实际)?建筑.{0,20}(?:受|通过|依赖).{0,12}(?:地形检查|原生检查|校验)"),
        Regex("(?:不承诺|不保证|未实现|尚未实现|未安装|没有安装).{0,24}(?:脚本|NPC|AI|全球唯一|生成|道路|机制|功能)"),
        Regex("(?:原生|服务端|客户端).{0,12}(?:校验|验证|绑定|快照|同步|编译|资源重载)"),
        Regex("(?i)(?:pack does not (?:contain|include|store)|no player (?:progress|save data)|subject to terrain (?:checks|validation)|not (?:implemented|installed)|native (?:validation|compilation)|server[- ](?:owned|authoritative))"),
    )
    @JvmStatic fun validate(displayName: String, description: String, theme: WorldTheme, items: CustomItemLibrary, quests: QuestLibrary): List<Diagnostic> = buildList {
        fun check(path: String, text: String) {
            if (engineering.any { it.containsMatchIn(text) }) add(Diagnostic(path, "PLAYER_TEXT_ENGINEERING_LEAK", DiagnosticSeverity.ERROR,
                "Player-visible writing must describe the world, its story or gameplay. Move implementation/validation notes to authoring diagnostics; revise this field without silently filtering its displayed text."))
        }
        check("manifest.displayName", displayName); check("manifest.description", description)
        check("theme.title", theme.title); check("theme.premise", theme.premise); check("theme.playerRole", theme.playerRole); check("theme.mainConflict", theme.mainConflict)
        theme.worldRules.forEachIndexed { i, rule -> check("theme.worldRules[$i]", rule) }
        theme.beats.forEachIndexed { i, beat -> check("theme.beats[$i].title", beat.title); check("theme.beats[$i].description", beat.description) }
        items.items.forEachIndexed { i, item -> check("items.items[$i].displayName", item.displayName); check("items.items[$i].description", item.description) }
        quests.quests.forEachIndexed { i, quest -> check("quests.quests[$i].title", quest.title); check("quests.quests[$i].description", quest.description) }
    }
}
