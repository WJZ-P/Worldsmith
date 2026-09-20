package com.wjz.worldsmith.core.ability

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.Serializable
import java.util.Collections

/** Source is part of the world pack, not a JVM class or an executable filesystem path. */
@Serializable
data class AbilityLibrary @JvmOverloads constructor(
    val schemaVersion: Int = 1,
    val programs: List<AbilityProgramDefinition> = emptyList(),
)

@Serializable
data class AbilityProgramDefinition @JvmOverloads constructor(
    val id: String,
    val name: String,
    val source: String,
    val maxTicks: Int = 1200,
    val maxOperations: Int = 32768,
    val requires: Map<String, Int> = emptyMap(),
)

object AbilityPrograms {
    @JvmStatic fun validId(id: String): Boolean = id.matches(Regex("[a-z0-9][a-z0-9_.-]{0,63}")) && ".." !in id
    @JvmStatic @JvmOverloads
    fun validate(library: AbilityLibrary, registry: AbilityCapabilityRegistry = AbilityCapabilities.standard()): List<Diagnostic> {
        val result = mutableListOf<Diagnostic>()
        fun error(path: String, code: String, message: String) {
            result += Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
        }
        if (library.schemaVersion != 1) error("abilities.schemaVersion", "ability.schema", "Ability schemaVersion must be 1.")
        if (library.programs.size > 64) error("abilities.programs", "ability.limit", "At most 64 ability programs are allowed.")
        val ids = mutableSetOf<String>()
        library.programs.take(65).forEachIndexed { index, program ->
            if (!ids.add(program.id)) error("abilities.programs[$index].id", "ability.duplicate", "Duplicate ability id '${program.id}'.")
            try { AbilityCompiler.compile(program, registry) }
            catch (e: IllegalArgumentException) {
                error("abilities.programs[$index]", "ability.compile", e.message ?: "Invalid ability program.")
            }
        }
        return Collections.unmodifiableList(result)
    }

    @JvmStatic
    fun freeze(library: AbilityLibrary): AbilityLibrary = library.copy(programs = Collections.unmodifiableList(
        library.programs.map { freezeDefinition(it) }
    ))

    internal fun freezeDefinition(definition: AbilityProgramDefinition): AbilityProgramDefinition = definition.copy(
        requires = Collections.unmodifiableMap(LinkedHashMap(definition.requires))
    )

    internal fun checkDefinition(definition: AbilityProgramDefinition) {
        require(validId(definition.id)) { "Ability id must be a lower-case content identifier of at most 64 characters, without '..'." }
        require(definition.name.isNotBlank() && definition.name.length <= 128) { "Ability name must contain 1..128 characters." }
        require(definition.source.isNotBlank() && definition.source.length <= 32768) { "Ability source must contain 1..32768 characters." }
        require(definition.maxTicks in 1..12000) { "Ability maxTicks must be 1..12000." }
        require(definition.maxOperations in 128..65536) { "Ability maxOperations must be 128..65536." }
        require(definition.requires.size <= 128) { "At most 128 capability requirements are allowed." }
    }
}

fun interface AbilityHost {
    fun call(name: String, arguments: List<AbilityValue>): AbilityValue
}

data class AbilityTickResult(val operations: Int, val completed: Boolean, val error: String?)
