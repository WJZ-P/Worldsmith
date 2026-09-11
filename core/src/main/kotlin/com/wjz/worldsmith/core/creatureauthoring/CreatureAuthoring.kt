package com.wjz.worldsmith.core.creatureauthoring

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Deterministic data compiler. Never loads Minecraft classes or executes recipe-supplied code. */
object CreatureAuthoring {
    private val NAME = Regex("[a-zA-Z][a-zA-Z0-9_]{0,47}")
    private data class Rig(val bones: List<CreatureBone>, val layout: CreatureUvLayout)
    private data class Seed(val id: String, val bone: BoneRecipe, val index: Int, val cube: CubeRecipe) {
        val width get() = 2 * (cube.size.x.toInt() + cube.size.z.toInt())
        val height get() = cube.size.y.toInt() + cube.size.z.toInt()
    }
    private data class Row(val y: Int, val height: Int, var used: Int = 0)

    @JvmStatic fun compile(recipe: CreatureRecipe, textureAsset: String): AuthoredCreature {
        require(WorldContentRegistry.SHA256.matches(textureAsset)) { "Use the actual texture's lowercase SHA-256 asset id" }
        val rig = rig(recipe)
        return AuthoredCreature(definition(recipe, rig, textureAsset), rig.layout)
    }

    @JvmStatic fun guide(recipe: CreatureRecipe): CreatureGuide {
        val rig = rig(recipe)
        val bytes = guidePng(rig.layout)
        val hash = ContentAssetValidation.hash(bytes)
        val asset = ContentAsset(hash, hash, "image/png", bytes.size.toLong(), ContentAssetValidation.path(hash))
        ContentAssetValidation.verify(asset, bytes)
        val diagnostic = Diagnostic("model.texture", "CREATURE_TEXTURE_GUIDE_ONLY", DiagnosticSeverity.WARNING,
            "This PNG is a labeled UV placement guide, not a finished painted texture. Paint/upload a real skin, then rebuild with its verified asset digest.")
        return CreatureGuide(definition(recipe, rig, hash), rig.layout, bytes, asset, listOf(diagnostic))
    }

    private fun definition(recipe: CreatureRecipe, rig: Rig, texture: String): CreatureDefinition {
        val result = CreatureDefinition(recipe.id, recipe.displayName, recipe.category,
            CreatureModel(texture, recipe.atlasWidth, recipe.atlasHeight, rig.bones), recipe.attributes, recipe.behavior,
            recipe.spawn.copy(biomes = recipe.spawn.biomes.toList()), recipe.themeRole,recipe.drops.toList(),recipe.boss)
        val library = CreatureLibrary(schemaVersion=recipe.schemaVersion,creatures = listOf(result))
        val errors = CustomCreatureValidator.validate(library)
        require(errors.isEmpty()) { errors.take(16).joinToString("; ") { "${it.path}: ${it.message}" } }
        return CustomCreatureValidator.freeze(library).creatures.single()
    }

    private fun rig(recipe: CreatureRecipe): Rig {
        require(recipe.schemaVersion in 1..2) { "Creature authoring recipe schema must be 1 or 2" }
        require(recipe.boss == null || recipe.schemaVersion == 2) { "Boss authoring requires explicit recipe schemaVersion 2" }
        require(listOf(recipe.atlasWidth, recipe.atlasHeight).all { it in 16..512 && it.countOneBits() == 1 }) { "Atlas dimensions must be powers of two, 16..512" }
        require(recipe.padding in 0..8 && recipe.mirrors.size <= 32) { "Use padding 0..8 and at most 32 mirror operations" }
        val bones = recipe.bones.map { it.copy(cubes = it.cubes.toList()) }.toMutableList()
        val shared = mutableMapOf<String, String>()
        checkBones(bones)
        for (mirror in recipe.mirrors) {
            require(NAME.matches(mirror.targetRoot) && bones.none { it.id == mirror.targetRoot }) { "Mirror target must be a new valid bone id" }
            val root = requireNotNull(bones.find { it.id == mirror.sourceRoot }) { "Unknown mirror source '${mirror.sourceRoot}'" }
            val subtree = mutableListOf<BoneRecipe>()
            fun collect(bone: BoneRecipe) { subtree += bone; bones.filter { it.parent == bone.id }.forEach(::collect) }
            collect(root)
            require(bones.size + subtree.size <= CustomCreatureValidator.MAX_BONES) { "Mirrored skeleton exceeds the 64-bone runtime budget" }
            val names = subtree.associate { bone -> bone.id to if (bone.id == root.id) mirror.targetRoot else
                mirror.targetRoot + if (bone.id.startsWith(root.id + "_")) bone.id.removePrefix(root.id) else "_${bone.id}" }
            require(names.values.distinct().size == names.size && names.values.none { id -> bones.any { it.id == id } }) { "Mirrored descendant names collide" }
            subtree.forEach { bone ->
                val target = names.getValue(bone.id)
                val cubes = bone.cubes.map { cube ->
                    if (mirror.shareUv) shared["$target/${cube.id}"] = shared["${bone.id}/${cube.id}"] ?: "${bone.id}/${cube.id}"
                    cube.copy(origin = cube.origin.copy(x = -cube.origin.x - cube.size.x), mirror = !cube.mirror)
                }
                bones += bone.copy(id = target, parent = names[bone.parent] ?: bone.parent,
                    pivot = bone.pivot.copy(x = -bone.pivot.x), rotation = bone.rotation.copy(y = -bone.rotation.y, z = -bone.rotation.z),
                    role = mirroredRole(bone.role), cubes = cubes)
            }
            checkBones(bones)
        }
        val seeds = bones.flatMap { bone -> bone.cubes.mapIndexed { i, cube -> Seed("${bone.id}/${cube.id}", bone, i, cube) } }
        require(seeds.size in 1..CustomCreatureValidator.MAX_CUBES) { "Compiled model needs 1..256 cubes" }
        val unique = seeds.filter { it.id !in shared }.sortedWith(compareByDescending<Seed> { it.height }.thenByDescending { it.width }.thenBy { it.id })
        val positions = linkedMapOf<String, CreatureUv>()
        val rows = mutableListOf<Row>()
        for (seed in unique) {
            val width = seed.width + 2 * recipe.padding
            val height = seed.height + 2 * recipe.padding
            require(width <= recipe.atlasWidth && height <= recipe.atlasHeight) { "UV island '${seed.id}' exceeds this atlas; choose a larger atlas or smaller cube" }
            val row = rows.filter { it.height >= height && it.used + width <= recipe.atlasWidth }
                .minWithOrNull(compareBy<Row> { it.height - height }.thenBy { recipe.atlasWidth - it.used - width })
                ?: Row(rows.lastOrNull()?.let { it.y + it.height } ?: 0, height).also {
                    require(it.y + height <= recipe.atlasHeight) { "UV atlas is full at '${seed.id}'; increase the atlas, share mirrored UVs, or simplify the model" }
                    rows += it
                }
            positions[seed.id] = CreatureUv(row.used + recipe.padding, row.y + recipe.padding)
            row.used += width
        }
        val islands = seeds.map { seed ->
            val source = shared[seed.id]
            val uv = positions.getValue(source ?: seed.id)
            CreatureUvIsland(seed.id, seed.bone.id, seed.cube.id, seed.index, seed.cube.materialRole,
                seed.cube.origin, seed.cube.size, uv, seed.width, seed.height, seed.cube.mirror, source,
                faces(uv, seed.cube.size))
        }
        val byKey = islands.associateBy { it.id }
        val compiled = bones.map { bone -> CreatureBone(bone.id, bone.parent, bone.pivot, bone.rotation, bone.role,
            bone.cubes.map { cube -> CreatureCube(cube.origin, cube.size, byKey.getValue("${bone.id}/${cube.id}").uv, cube.mirror) }, bone.gaitPhase) }
        return Rig(compiled, CreatureUvLayout(textureWidth = recipe.atlasWidth, textureHeight = recipe.atlasHeight,
            padding = recipe.padding, uniqueIslands = unique.size, usedBoxPixels = unique.sumOf { it.width * it.height }, islands = islands))
    }

    private fun checkBones(bones: List<BoneRecipe>) {
        require(bones.size in 1..64 && bones.map { it.id }.distinct().size == bones.size) { "Author 1..64 distinctly named bones" }
        val byId = bones.associateBy { it.id }
        bones.forEach { bone ->
            require(NAME.matches(bone.id)) { "Invalid bone id '${bone.id}'" }
            require(bone.parent == null || bone.parent in byId) { "Unknown parent '${bone.parent}' for '${bone.id}'" }
            val chain = mutableSetOf<String>(); var cursor: BoneRecipe? = bone
            while (cursor != null) {
                require(chain.add(cursor.id) && chain.size <= 16) { "Bone '${bone.id}' has a cyclic or over-deep hierarchy" }
                cursor = cursor.parent?.let(byId::get)
            }
            require(bone.cubes.size <= 256 && bone.cubes.map { it.id }.distinct().size == bone.cubes.size) { "Cube IDs must be unique inside '${bone.id}'" }
            bone.cubes.forEach { cube ->
                require(NAME.matches(cube.id) && cube.materialRole.matches(Regex("[a-z][a-z0-9_.-]{0,63}"))) { "Use short cube ids and lowercase material roles" }
                require(listOf(cube.size.x, cube.size.y, cube.size.z).all { it.isFinite() && it in 1f..64f && it == it.toInt().toFloat() }) { "Cube '${bone.id}/${cube.id}' sizes must be integers from 1 to 64" }
            }
        }
    }

    private fun mirroredRole(role: CreatureBoneRole) = when (role) {
        CreatureBoneRole.ARM_LEFT -> CreatureBoneRole.ARM_RIGHT
        CreatureBoneRole.ARM_RIGHT -> CreatureBoneRole.ARM_LEFT
        CreatureBoneRole.LEG_LEFT -> CreatureBoneRole.LEG_RIGHT
        CreatureBoneRole.LEG_RIGHT -> CreatureBoneRole.LEG_LEFT
        else -> role
    }

    /** Face rectangles match the native Cube UV net; bottom reverses V, mirroring is a separate geometry flag. */
    private fun faces(uv: CreatureUv, size: CreatureVector): List<UvFaceRect> {
        val u = uv.u; val v = uv.v; val w = size.x.toInt(); val h = size.y.toInt(); val d = size.z.toInt()
        return listOf(UvFaceRect("top", u + d, v, w, d), UvFaceRect("bottom", u + d + w, v, w, d, true),
            UvFaceRect("left", u, v + d, d, h), UvFaceRect("front", u + d, v + d, w, h),
            UvFaceRect("right", u + d + w, v + d, d, h), UvFaceRect("back", u + 2 * d + w, v + d, w, h))
    }

    @JvmStatic fun guidePng(layout: CreatureUvLayout): ByteArray {
        val image = BufferedImage(layout.textureWidth, layout.textureHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(14, 16, 22); graphics.fillRect(0, 0, image.width, image.height)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 7)
            layout.islands.filter { it.sharedWith == null }.forEach { island ->
                val hue = (island.materialRole.hashCode().toLong() and 0xffff) / 65536f
                val base = Color.getHSBColor(hue, .38f, .75f)
                island.faces.forEachIndexed { index, face ->
                    graphics.color = if (index % 2 == 0) base else base.darker()
                    graphics.fillRect(face.x, face.y, face.width, face.height)
                    graphics.color = Color(5, 8, 14)
                    graphics.drawRect(face.x, face.y, face.width - 1, face.height - 1)
                    if (face.width >= 12 && face.height >= 9) {
                        graphics.color = Color.WHITE; graphics.drawString(face.face.take(1).uppercase(), face.x + 2, face.y + 7)
                    }
                }
            }
        } finally { graphics.dispose() }
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) }.toByteArray()
    }
}
