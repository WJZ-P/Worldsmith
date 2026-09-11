package com.wjz.worldsmith.core.creatureauthoring

import com.wjz.worldsmith.core.content.*

/** Fluent, version-independent authoring API usable from Java source as well as Kotlin. */
class CreatureBuilder private constructor(private val id: String, private val displayName: String, private val category: CreatureCategory) {
    private var atlasWidth = 512
    private var atlasHeight = 512
    private var padding = 2
    private var themeRole = ""
    private var attributes = CreatureAttributes()
    private var behavior = CreatureBehavior()
    private var spawn = CreatureSpawn()
    private var drops:List<CreatureDrop> = emptyList()
    private var boss:CreatureBossProfile? = null
    private val bones = linkedMapOf<String, BoneRecipe>()
    private val mirrors = mutableListOf<MirrorRecipe>()

    companion object {
        @JvmStatic fun create(id: String, displayName: String, category: CreatureCategory) = CreatureBuilder(id, displayName, category)
    }

    @JvmOverloads fun atlas(width: Int, height: Int, padding: Int = 2) = apply {
        atlasWidth = width; atlasHeight = height; this.padding = padding
    }
    fun themeRole(text: String) = apply { themeRole = text }
    fun attributes(value: CreatureAttributes) = apply { attributes = value }
    fun attributes(health: Double, speed: Double, followRange: Double, attackDamage: Double,
        knockbackResistance: Double, width: Float, height: Float) = attributes(CreatureAttributes(health, speed, followRange, attackDamage, knockbackResistance, width, height))
    fun behavior(value: CreatureBehavior) = apply { behavior = value }
    fun behavior(passiveMode: CreaturePassiveMode, territoryRadius: Int, attackReach: Double, windupTicks: Int, recoveryTicks: Int) =
        behavior(CreatureBehavior(passiveMode, territoryRadius, attackReach, windupTicks, recoveryTicks))
    fun spawn(value: CreatureSpawn) = apply { spawn = value }
    fun drops(value:List<CreatureDrop>) = apply { drops = value.toList() }
    fun boss(value:CreatureBossProfile?) = apply { boss = value?.copy(phases = value.phases.toList()) }
    fun spawn(biomes: List<String>, weight: Int, minGroup: Int, maxGroup: Int, minLight: Int, maxLight: Int) =
        spawn(CreatureSpawn(biomes.toList(), weight, minGroup, maxGroup, minLight, maxLight))

    fun bone(id: String, parent: String?, x: Float, y: Float, z: Float): BoneBuilder {
        require(id !in bones) { "Bone '$id' already exists; select it with bone(id)" }
        bones[id] = BoneRecipe(id, parent, CreatureVector(x, y, z))
        return BoneBuilder(id)
    }
    fun bone(id: String): BoneBuilder {
        require(id in bones) { "Unknown authored bone '$id'" }
        return BoneBuilder(id)
    }
    @JvmOverloads fun mirrorSubtree(sourceRoot: String, targetRoot: String, shareUv: Boolean = true) = apply {
        mirrors += MirrorRecipe(sourceRoot, targetRoot, shareUv)
    }

    fun recipe() = CreatureRecipe(id, displayName, category, schemaVersion = if(boss == null) 1 else 2, atlasWidth = atlasWidth, atlasHeight = atlasHeight,
        padding = padding, themeRole = themeRole, attributes = attributes, behavior = behavior,
        spawn = spawn.copy(biomes = spawn.biomes.toList()), bones = bones.values.map { it.copy(cubes = it.cubes.toList()) }, mirrors = mirrors.toList(), drops=drops.toList(), boss=boss?.let {it.copy(phases=it.phases.toList())})
    fun build(textureSha256: String) = CreatureAuthoring.compile(recipe(), textureSha256)
    fun guide() = CreatureAuthoring.guide(recipe())

    inner class BoneBuilder internal constructor(private val id: String) {
        private fun change(f: (BoneRecipe) -> BoneRecipe) = apply { bones[id] = f(bones.getValue(id)) }
        fun pivot(x: Float, y: Float, z: Float) = change { it.copy(pivot = CreatureVector(x, y, z)) }
        fun rotation(x: Float, y: Float, z: Float) = change { it.copy(rotation = CreatureVector(x, y, z)) }
        fun role(value: CreatureBoneRole) = change { it.copy(role = value) }
        fun gaitPhase(degrees: Float) = change { it.copy(gaitPhase = degrees) }
        @JvmOverloads fun cube(cubeId: String, x: Float, y: Float, z: Float, width: Int, height: Int, depth: Int,
            materialRole: String = "body", mirror: Boolean = false) = change { bone ->
            require(bone.cubes.none { it.id == cubeId }) { "Duplicate cube '$id/$cubeId'" }
            bone.copy(cubes = bone.cubes + CubeRecipe(cubeId, CreatureVector(x, y, z),
                CreatureVector(width.toFloat(), height.toFloat(), depth.toFloat()), materialRole, mirror))
        }
        fun end(): CreatureBuilder = this@CreatureBuilder
    }
}
