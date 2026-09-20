package com.wjz.worldsmith.core.content

import kotlinx.serialization.Serializable

/** Local north is the authored front. Orientation is a native state contract, not an arbitrary model. */
@Serializable enum class BlockOrientation { FIXED, HORIZONTAL }

/** Clockwise UV rotation in the face's native Minecraft UV plane. */
@Serializable data class BlockFaceTexture @JvmOverloads constructor(val textureAsset: String, val quarterTurns: Int = 0)

/** One canonical six-face full cube. All seven references participate in validation and bundle identity. */
@Serializable data class BlockAppearance @JvmOverloads constructor(
    val up: BlockFaceTexture,
    val down: BlockFaceTexture,
    val north: BlockFaceTexture,
    val south: BlockFaceTexture,
    val west: BlockFaceTexture,
    val east: BlockFaceTexture,
    val particle: String,
    val orientation: BlockOrientation = BlockOrientation.FIXED,
) {
    fun faces(): Map<String, BlockFaceTexture> = linkedMapOf("up" to up, "down" to down, "north" to north,
        "south" to south, "west" to west, "east" to east)
    fun assetIds(): List<String> = (faces().values.map { it.textureAsset } + particle).distinct()

    companion object {
        /** Authoring shorthand only: serialization, linking and rendering always use the same full face set. */
        @JvmStatic @JvmOverloads fun uniform(textureAsset: String, orientation: BlockOrientation = BlockOrientation.FIXED): BlockAppearance {
            val face = BlockFaceTexture(textureAsset)
            return BlockAppearance(face, face, face, face, face, face, textureAsset, orientation)
        }
    }
}
