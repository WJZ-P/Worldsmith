package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

/**
 * Review hints about what a declared room holds, never a publication gate. A
 * room is where a player learns what a building is for, and an empty one is the
 * step most often skipped; a bare hall, arena or cleared vault stays valid.
 */
object StructureInteriorChecks {
    /** Rooms this many floor cells or larger are judged; a closet may well be empty. */
    const val MIN_AREA = 12

    /**
     * A warning for each declared room with almost nothing standing on its floor:
     * fewer than one floor cell in twenty, and fewer than two, with any block at
     * feet or head height or the one above. Room boxes are the declared floor
     * planes, in the same coordinates as [voxels].
     */
    @JvmStatic fun bareRooms(blueprint: StructureBlueprint, voxels: Collection<StructureVoxel>): List<Diagnostic> {
        val solid = voxels.asSequence().filter { !it.material.isAir() }.map { it.position }.toHashSet()
        return blueprint.rooms.mapIndexedNotNull { i, room ->
            val area = (room.to.x - room.from.x + 1) * (room.to.z - room.from.z + 1)
            if (area < MIN_AREA) return@mapIndexedNotNull null
            val standing = (room.from.x..room.to.x).sumOf { x ->
                (room.from.z..room.to.z).count { z -> (room.from.y..room.from.y + 2).any { y -> BuildPos(x, y, z) in solid } }
            }
            if (standing >= maxOf(2, area / 20)) null
            else Diagnostic("rooms[$i]", "BARE_ROOM", DiagnosticSeverity.WARNING,
                "Room ${i + 1} has $area floor cells and something stands on only $standing of them",
                region = room, metrics = mapOf("floorCells" to area, "furnishedCells" to standing),
                hint = "Furnish it for what it is used for - Rooms.furnish in the drawing SDK lays a bedroom, kitchen, tavern, library, forge or storeroom - or keep it bare on purpose: a hall, an arena, a cleared vault")
        }
    }
}
