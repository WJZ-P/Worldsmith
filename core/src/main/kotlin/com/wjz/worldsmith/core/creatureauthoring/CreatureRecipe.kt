package com.wjz.worldsmith.core.creatureauthoring

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.validation.Diagnostic
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/** Authoring input only. Runtime schema 2 explicitly enables boss phases; ordinary schema-1 recipes stay unchanged. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class CreatureRecipe(
    val id: String,
    val displayName: String,
    val category: CreatureCategory = CreatureCategory.HOSTILE,
    val schemaVersion: Int = 1,
    val atlasWidth: Int = 512,
    val atlasHeight: Int = 512,
    val padding: Int = 2,
    val themeRole: String = "",
    val attributes: CreatureAttributes = CreatureAttributes(),
    val behavior: CreatureBehavior = CreatureBehavior(),
    val spawn: CreatureSpawn = CreatureSpawn(),
    val bones: List<BoneRecipe>,
    val mirrors: List<MirrorRecipe> = emptyList(),
    val drops: List<CreatureDrop> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val boss: CreatureBossProfile? = null,
)

@Serializable
data class BoneRecipe(
    val id: String,
    val parent: String? = null,
    val pivot: CreatureVector = CreatureVector(),
    val rotation: CreatureVector = CreatureVector(),
    val role: CreatureBoneRole = CreatureBoneRole.NONE,
    val gaitPhase: Float = 0f,
    val cubes: List<CubeRecipe> = emptyList(),
)

@Serializable
data class CubeRecipe(
    val id: String,
    val origin: CreatureVector,
    val size: CreatureVector,
    val materialRole: String = "body",
    val mirror: Boolean = false,
)

/** Reflects a complete limb in its parent's local X=0 plane, preserving the existing parent. */
@Serializable
data class MirrorRecipe(val sourceRoot: String, val targetRoot: String, val shareUv: Boolean = true)

@Serializable
data class UvFaceRect(
    val face: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val flipV: Boolean = false,
)

@Serializable
data class CreatureUvIsland(
    val id: String,
    val boneId: String,
    val cubeId: String,
    val cubeIndex: Int,
    val materialRole: String,
    val origin: CreatureVector,
    val size: CreatureVector,
    val uv: CreatureUv,
    val width: Int,
    val height: Int,
    val mirror: Boolean,
    val sharedWith: String? = null,
    val faces: List<UvFaceRect>,
)

@Serializable
data class CreatureUvLayout(
    val schemaVersion: Int = 1,
    val textureWidth: Int,
    val textureHeight: Int,
    val padding: Int,
    val uniqueIslands: Int,
    val usedBoxPixels: Int,
    val islands: List<CreatureUvIsland>,
    val coordinateSystem: String = "model units = 1/16 block; X right, Y down, Z back; root Y=24 is ground",
    val uvConvention: String = "Vanilla box UV, one texel per model unit; mirrored islands reuse source UV with native mirror semantics",
)

data class AuthoredCreature(val definition: CreatureDefinition, val uvLayout: CreatureUvLayout)

/** A real but deliberately diagnostic PNG, never described as a finished painted skin. */
data class CreatureGuide(
    val definition: CreatureDefinition,
    val uvLayout: CreatureUvLayout,
    val png: ByteArray,
    val asset: ContentAsset,
    val diagnostics: List<Diagnostic>,
)

/** Local Java source entry point; MCP consumes CreatureRecipe data rather than executing this interface. */
fun interface CreatureProgram { fun create(): CreatureBuilder }
