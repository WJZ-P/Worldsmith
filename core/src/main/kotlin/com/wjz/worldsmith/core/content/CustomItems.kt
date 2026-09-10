package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.Serializable

/** Descriptive categories only: neither category enables tools, weapons, food or scripted actions. */
@Serializable enum class CustomItemKind { RESOURCE, RELIC }
@Serializable enum class CustomItemRarity { COMMON, UNCOMMON, RARE, EPIC }

@Serializable
data class CustomItemDefinition @JvmOverloads constructor(
    val id: String,
    val displayName: String,
    val textureAsset: String,
    val kind: CustomItemKind = CustomItemKind.RESOURCE,
    val maxStackSize: Int = 64,
    val rarity: CustomItemRarity = CustomItemRarity.COMMON,
    val description: String = "",
    val themeRole: String = "",
)

@Serializable
data class CustomItemLibrary @JvmOverloads constructor(
    val schemaVersion: Int = 1,
    val items: List<CustomItemDefinition> = emptyList(),
)

object CustomItemValidation {
    const val MAX_ITEMS = 256
    const val MAX_TEXTURE_BYTES = 1024 * 1024
    const val LOGICAL_PREFIX = "worldsmith:item/"
    private val ID = Regex("[a-z0-9][a-z0-9_.-]{0,63}")

    @JvmStatic fun validate(library: CustomItemLibrary): List<Diagnostic> = buildList {
        fun error(path: String, code: String, message: String) { add(Diagnostic(path, code, DiagnosticSeverity.ERROR, message)) }
        if (library.schemaVersion != 1) error("schemaVersion", "items.schema", "Ordinary item library schema must be 1")
        if (library.items.size > MAX_ITEMS) {
            error("items", "items.capacity", "At most $MAX_ITEMS ordinary item definitions per world")
            return@buildList
        }
        val seen = mutableSetOf<String>()
        library.items.forEachIndexed { index, item ->
            val path = "items[$index]"
            if (!validId(item.id)) error("$path.id", "items.id", "Use a local lowercase item id of 1..64 characters; '..' and a trailing dot are reserved resource-path sequences")
            if (!seen.add(item.id)) error("$path.id", "items.duplicate", "Duplicate ordinary item '${item.id}'")
            if (item.displayName.isBlank() || item.displayName.length > 128 || item.displayName.any(Char::isISOControl))
                error("$path.displayName", "items.name", "Display names need 1..128 printable characters")
            if (!WorldContentRegistry.SHA256.matches(item.textureAsset))
                error("$path.textureAsset", "items.texture", "Item icons reference their actual lowercase SHA-256 asset id")
            if (item.maxStackSize !in 1..64) error("$path.maxStackSize", "items.stack_size", "Ordinary item stacks support 1..64 items")
            if (item.description.length > 2048 || item.description.any { it.isISOControl() && it != '\n' })
                error("$path.description", "items.description", "Description is plain text, up to 2048 characters; only newline control characters are supported")
            if (item.description.lineSequence().count() > 256)
                error("$path.description", "items.description_lines", "Item tooltip lore supports at most 256 lines")
            if (item.themeRole.length > 2048) error("$path.themeRole", "items.theme_role", "Theme role is limited to 2048 characters")
        }
    }

    @JvmStatic fun freeze(library: CustomItemLibrary) = library.copy(items = java.util.List.copyOf(library.items))
    @JvmStatic fun validId(value: String): Boolean = ID.matches(value) && ".." !in value && !value.endsWith('.')
}
