package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Immutable native physical presets. Data selects a preset, never mutates cached registry properties. */
@Serializable enum class CustomBlockProfile { STONE, WOOD, METAL, GLASS }

@Serializable
data class CustomBlockDefinition @JvmOverloads constructor(
    val id: String,
    val displayName: String,
    val profile: CustomBlockProfile = CustomBlockProfile.STONE,
    val textureAsset: String,
    val light: Int = 0,
    val themeRole: String = "",
)

@Serializable
data class CustomBlockLibrary @JvmOverloads constructor(
    val schemaVersion: Int = 1,
    val blocks: List<CustomBlockDefinition> = emptyList(),
)

@Serializable
data class CustomBlockBinding(val id: String, val profile: CustomBlockProfile, val slot: Int, val light: Int) {
    fun nativeId(): String = "worldsmith:content/block/${profile.name.lowercase()}/${slot.toString().padStart(2, '0')}"
    fun logicalId(): String = "worldsmith:content/$id"
}

/** Store with the save, not independently per dimension. This mapping is part of chunk identity. */
@Serializable
data class CustomBlockBindingSnapshot(val schemaVersion: Int = 1, val scope: String, val bindings: List<CustomBlockBinding>)

object CustomBlockValidation {
    const val SLOTS_PER_PROFILE = 32
    private val id = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
    private val sha = Regex("[0-9a-f]{64}")
    @JvmStatic fun validate(library: CustomBlockLibrary): List<Diagnostic> = buildList {
        fun error(path: String, code: String, message: String) { add(Diagnostic(path, code, DiagnosticSeverity.ERROR, message)) }
        if (library.schemaVersion != 1) error("schemaVersion", "blocks.schema", "Custom blocks require schema version 1")
        val seen = mutableSetOf<String>()
        library.blocks.forEachIndexed { index, block ->
            val path = "blocks[$index]"
            if (!id.matches(block.id)) error("$path.id", "blocks.id", "Use a local lowercase content id of 1 to 64 characters")
            if (!seen.add(block.id)) error("$path.id", "blocks.duplicate", "Duplicate custom block id: ${block.id}")
            if (block.displayName.isBlank() || block.displayName.length > 128 || block.displayName.any { it.isISOControl() })
                error("$path.displayName", "blocks.name", "A display name must have 1 to 128 printable characters")
            if (!sha.matches(block.textureAsset)) error("$path.textureAsset", "blocks.texture", "Texture asset must be a lowercase SHA-256 id")
            if (block.light !in 0..15) error("$path.light", "blocks.light", "Light must be 0 through 15; native hosts predeclare these states")
            if (block.themeRole.length > 2048) error("$path.themeRole", "blocks.theme_role", "Theme role exceeds 2048 characters")
        }
        library.blocks.groupingBy { it.profile }.eachCount().forEach { (profile, size) ->
            if (size > SLOTS_PER_PROFILE) error("blocks", "blocks.capacity", "$profile supports $SLOTS_PER_PROFILE native slots, requested $size")
        }
    }
}

/** Pure planning also allows authoring/validation without loading any Minecraft classes. */
object CustomBlockBindings {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @JvmStatic @JvmOverloads
    fun plan(scope: String, library: CustomBlockLibrary, previous: CustomBlockBindingSnapshot? = null): CustomBlockBindingSnapshot {
        require(scope.isNotBlank() && scope.length <= 256) { "A bounded world scope is required" }
        val errors = CustomBlockValidation.validate(library)
        require(errors.isEmpty()) { errors.joinToString("; ") { "${it.path}: ${it.message}" } }
        if (previous != null) {
            validateSnapshot(previous)
            require(previous.scope == scope) { "A binding snapshot belongs to a different world scope" }
        }
        val definitions = library.blocks.associateBy { it.id }
        val assigned = previous?.bindings?.associateBy { it.id }?.toMutableMap() ?: mutableMapOf()
        assigned.values.forEach { binding ->
            val block = requireNotNull(definitions[binding.id]) { "Removing bound block ${binding.id} requires an explicit save migration" }
            require(block.profile == binding.profile && block.light == binding.light) {
                "Changing native profile or light for bound block ${binding.id} requires an explicit save migration"
            }
        }
        for (block in library.blocks.sortedBy { it.id }) {
            if (block.id in assigned) continue
            val used = assigned.values.filter { it.profile == block.profile }.map { it.slot }.toSet()
            val slot = (0 until CustomBlockValidation.SLOTS_PER_PROFILE).firstOrNull { it !in used }
            requireNotNull(slot) { "Native slot capacity exhausted for ${block.profile}" }
            assigned[block.id] = CustomBlockBinding(block.id, block.profile, slot, block.light)
        }
        return CustomBlockBindingSnapshot(scope = scope, bindings = java.util.List.copyOf(assigned.values.sortedBy { it.id }))
    }

    @JvmStatic fun validateSnapshot(snapshot: CustomBlockBindingSnapshot) {
        require(snapshot.schemaVersion == 1 && snapshot.scope.isNotBlank() && snapshot.scope.length <= 256) { "Invalid block binding snapshot header" }
        val ids = mutableSetOf<String>()
        val hosts = mutableSetOf<String>()
        snapshot.bindings.forEach {
            require(it.id.matches(Regex("[a-z0-9][a-z0-9_.-]{0,63}")) && ids.add(it.id)) { "Duplicate or invalid binding id" }
            require(it.slot in 0 until CustomBlockValidation.SLOTS_PER_PROFILE && it.light in 0..15) { "Invalid native slot or light" }
            require(hosts.add(it.nativeId())) { "Two blocks share one native slot" }
        }
    }
    @JvmStatic fun encode(snapshot: CustomBlockBindingSnapshot): String { validateSnapshot(snapshot); return json.encodeToString(snapshot) }
    @JvmStatic fun decode(document: String): CustomBlockBindingSnapshot = json.decodeFromString<CustomBlockBindingSnapshot>(document)
        .also(::validateSnapshot).let { it.copy(bindings = java.util.List.copyOf(it.bindings)) }
}
