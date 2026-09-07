package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

/** Verifies design declarations against real compiled plans, not just labels in prose. */
object StructureArchitectureValidator {
    const val MIN_GROUPS = 2
    const val MIN_STANDALONE = 1
    const val LANDMARK_MIN_PARTS = 3
    const val LANDMARK_MIN_SOLID_CELLS = 8192
    const val LANDMARK_MIN_HORIZONTAL_SPAN = 64
    const val LANDMARK_MIN_HEIGHT = 32
    private val ID = Regex("[a-z0-9_][a-z0-9_-]{0,63}")

    @JvmStatic
    fun validatePlan(plan: StructureArchitecture): List<Diagnostic> = buildList {
        fun check(ok: Boolean, path: String, code: String, message: String) { if (!ok) add(error(path, code, message)) }
        fun prose(text: String, path: String) = check(text.isNotBlank() && text.length <= 2000, path, "ARCHITECTURE_DESCRIPTION", "Provide a focused nonblank description, at most 2000 characters")
        check(plan.policyVersion == 1, "policyVersion", "ARCHITECTURE_POLICY_VERSION", "Architecture policy must be 1")
        prose(plan.worldTheme, "worldTheme")
        check(plan.groups.size in MIN_GROUPS..12, "groups", "ARCHITECTURE_GROUPS_REQUIRED", "Design at least two distinct groups, at most twelve")
        check(plan.standalone.size in MIN_STANDALONE..36, "standalone", "ARCHITECTURE_STANDALONE_REQUIRED", "Include at least one independent structure, at most thirty-six")
        check(plan.groups.any { it.role == StructureGroupRole.LANDMARK }, "groups", "ARCHITECTURE_LANDMARK_REQUIRED", "Include a monumental group that best embodies the world theme")
        val ids = mutableSetOf<String>()
        plan.groups.forEachIndexed { i, group ->
            val p = "groups[$i]"
            check(ID.matches(group.structure) && ids.add(group.structure), "$p.structure", "ARCHITECTURE_DUPLICATE_STRUCTURE", "Group and standalone structure ids must be valid and distinct")
            check(ID.matches(group.centerpiece), "$p.centerpiece", "ARCHITECTURE_CENTERPIECE", "Name the root blueprint of this group")
            prose(group.title, "$p.title"); prose(group.themeFit, "$p.themeFit"); prose(group.layoutIntent, "$p.layoutIntent")
            prose(group.distinction, "$p.distinction"); prose(group.discovery, "$p.discovery")
            check(group.required.isNotEmpty() && group.required.size + group.optional.size <= 16, p, "ARCHITECTURE_MEMBER_RULES", "Declare required roles and at most sixteen total member rules")
            val roles = mutableSetOf<String>(); val blueprints = mutableSetOf<String>()
            for ((kind, rules) in listOf("required" to group.required, "optional" to group.optional)) rules.forEachIndexed { j, rule ->
                val rp = "$p.$kind[$j]"
                prose(rule.role, "$rp.role")
                check(roles.add(rule.role), "$rp.role", "ARCHITECTURE_DUPLICATE_ROLE", "Member role names must be unique inside the group")
                check(rule.minCount in 0..16 && rule.maxCount in 1..16 && rule.maxCount >= rule.minCount &&
                    (if (kind == "required") rule.minCount >= 1 else rule.minCount == 0), rp, "ARCHITECTURE_MEMBER_COUNT", "Required roles have minCount>=1; optional roles have minCount=0; maxCount is 1..16 and >=minCount")
                check(rule.blueprints.size in 1..16 && rule.blueprints.all { ID.matches(it) && blueprints.add(it) }, "$rp.blueprints", "ARCHITECTURE_MEMBER_BLUEPRINTS", "Assign 1..16 distinct blueprint ids to each role, without overlapping roles")
            }
            check(group.required.sumOf { it.minCount.toLong() } <= 16, "$p.required", "ARCHITECTURE_MEMBER_BUDGET", "Required members exceed the current sixteen-piece deployment budget")
            check(group.required.any { group.centerpiece in it.blueprints }, "$p.required", "ARCHITECTURE_REQUIRED_CENTERPIECE", "The centerpiece must belong to a required role")
        }
        plan.standalone.forEachIndexed { i, entry ->
            val p = "standalone[$i]"
            check(ID.matches(entry.structure) && ids.add(entry.structure), "$p.structure", "ARCHITECTURE_DUPLICATE_STRUCTURE", "Standalone ids must be valid and distinct from group ids")
            prose(entry.purpose, "$p.purpose"); prose(entry.themeFit, "$p.themeFit")
        }
    }.map { it.copy(path = "architecture.${it.path}") }

    @JvmStatic
    fun missingPlan(): Diagnostic = error("architecture", "ARCHITECTURE_PLAN_REQUIRED", "Submit a world-specific architecture plan with worldsmith_plan_architecture before publishing a guided world")

    @JvmStatic
    fun validate(library: StructureLibrary, catalog: CompiledStructureCatalog): List<Diagnostic> {
        val plan = library.architecture ?: return emptyList() // Existing standalone packs remain readable.
        val syntax = validatePlan(plan)
        if (syntax.any { it.severity == DiagnosticSeverity.ERROR }) return syntax
        return buildList {
            addAll(syntax)
            val definitions = library.structures.associateBy { it.id }
            val declared = (plan.groups.map { it.structure } + plan.standalone.map { it.structure }).toSet()
            for (id in declared - definitions.keys) add(error("architecture", "ARCHITECTURE_MISSING_STRUCTURE", "No executable structure definition for '$id'"))
            for (id in definitions.keys - declared) add(error("architecture", "ARCHITECTURE_UNDECLARED_STRUCTURE", "Classify '$id' as a group or an independent structure"))
            plan.groups.forEachIndexed { i, group ->
                val p = "architecture.groups[$i]"
                val definition = definitions[group.structure] ?: return@forEachIndexed
                if (definition.assembly == null) add(error(p, "ARCHITECTURE_GROUP_ASSEMBLY_REQUIRED", "A group must have an executable assembly, not a renamed single building"))
                if (definition.blueprint.id != group.centerpiece) add(error("$p.centerpiece", "ARCHITECTURE_CENTERPIECE_MISMATCH", "The centerpiece must match the definition's root blueprint"))
                val known = setOf(definition.blueprint.id) + definition.assembly?.pieces.orEmpty().keys
                val rules = group.required + group.optional
                for (id in rules.flatMap { it.blueprints } - known) add(error(p, "ARCHITECTURE_UNKNOWN_MEMBER", "Member '$id' is not a blueprint in this group"))
                val classified = rules.flatMap { it.blueprints }.toSet()
                val plans = catalog.plans[group.structure].orEmpty()
                plans.forEachIndexed { variant, compiled ->
                    val vp = "$p.variants[$variant]"
                    if (compiled.parts.size < 2) add(error(vp, "ARCHITECTURE_GROUP_TOO_SMALL", "Every group plan needs at least two building pieces"))
                    for (rule in rules) {
                        val count = compiled.parts.count { it.blueprintId in rule.blueprints }
                        if (count !in rule.minCount..rule.maxCount) add(error(vp, "ARCHITECTURE_MEMBER_COUNT_MISMATCH", "Role '${rule.role}' has $count members; expected ${rule.minCount}..${rule.maxCount}. Repair assembly pools/required ports."))
                    }
                    if (compiled.parts.any { it.blueprintId !in classified }) add(error(vp, "ARCHITECTURE_UNCLASSIFIED_MEMBER", "Every emitted building must have a declared role"))
                    if (group.role == StructureGroupRole.LANDMARK) {
                        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
                        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE; var solids = 0L
                        for (part in compiled.parts) for (voxel in part.geometry.voxels) if (!voxel.material.isAir()) {
                            val at = StructureCatalogCompiler.transform(voxel.position, part); solids++
                            minX = minOf(minX, at.x); minY = minOf(minY, at.y); minZ = minOf(minZ, at.z)
                            maxX = maxOf(maxX, at.x); maxY = maxOf(maxY, at.y); maxZ = maxOf(maxZ, at.z)
                        }
                        val span = if (solids == 0L) 0 else maxOf(maxX - minX + 1, maxZ - minZ + 1)
                        val height = if (solids == 0L) 0 else maxY - minY + 1
                        if (compiled.parts.size < LANDMARK_MIN_PARTS || solids < LANDMARK_MIN_SOLID_CELLS ||
                            span < LANDMARK_MIN_HORIZONTAL_SPAN && height < LANDMARK_MIN_HEIGHT)
                            add(error(vp, "ARCHITECTURE_LANDMARK_SCALE", "Landmarks need >=3 pieces, >=8192 non-air cells, and >=64 horizontal span OR >=32 height. These are scale checks, not an aesthetic score."))
                    }
                }
            }
            for ((id, definition) in definitions) definition.placement.region?.let { region ->
                if (region.chance <= 0 || region.minInfluence >= region.maxInfluence)
                    add(error("architecture", "ARCHITECTURE_DISABLED_STRUCTURE", "Planned structure '$id' needs nonzero candidate probability and a nonempty influence interval"))
            }
            for ((id, blueprint) in catalog.blueprints) if (blueprint.lighting == null)
                add(error("blueprints.$id.lighting", "ARCHITECTURE_LIGHTING_REQUIRED", "Declare READABLE spaces with lights for interiors, or EXTERIOR_ONLY for genuinely open structures"))
        }
    }

    private fun error(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
}
