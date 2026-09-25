package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.draw.Box
import com.wjz.worldsmith.core.draw.DrawStructure
import com.wjz.worldsmith.core.draw.DrawVoxel
import kotlinx.serialization.Serializable

@Serializable
data class StructureVisualEvidenceReport(
    val frame: BuildBox,
    val occupiedBounds: BuildBox?,
    val nonAirCells: Int,
    val footprintColumns: Int,
    val projections: List<StructureProjectionEvidence>,
    val review: List<StructureVisualReview>,
    val scope: String = "Current filtered preview source; frame fixes camera scale and does not crop geometry. Aggregate counts precede per-view slicing; slice projections measure only sliceY. Isometric images use orthographic companion measurements.",
    val interpretation: String = "Geometry evidence, not an aesthetic score or publication gate. All non-air blocks are unit cubes, including glass, stairs and foliage; missing cells and authored air are both absent from projections. Depth changes may reveal a rear wall through an opening. Inspect images and theme before changing intentional quiet surfaces.",
)

@Serializable
data class StructureProjectionEvidence(
    val view: String,
    val horizontalAxis: String,
    val verticalAxis: String,
    /** Direction toward the right of the corresponding image, not a coordinate transform. */
    val screenRight: String,
    val visibleCells: Int,
    val depthLayers: Int,
    val depthSpan: Long,
    val reliefEdges: Int,
    val largestPlanarPanel: StructurePlanarPanel?,
    val profileRunCount: Int,
    val profile: List<StructureProfileRun>,
    val materialCount: Int,
    val materials: List<StructureVisibleMaterial>,
)

/** Inclusive coordinates on the named horizontal/vertical axes, sorted by world horizontal coordinate. */
@Serializable
data class StructureProfileRun(val from: Int, val to: Int, val low: Int, val high: Int)

/** Largest completely occupied projected rectangle at a single depth; material changes do not split it. */
@Serializable
data class StructurePlanarPanel(val region: BuildBox, val width: Int, val height: Int, val cells: Int)

@Serializable
data class StructureVisibleMaterial(val block: String, val cells: Int)

@Serializable
data class StructureVisualReview(val view: String, val region: BuildBox, val observation: String, val suggestion: String)

/**
 * Sparse, registry-independent evidence for the visual repair loop. No bounding-volume scan, block-name
 * style heuristic, minimum ornament requirement or beauty score. Hidden interior palettes never count
 * toward an elevation's material rhythm. Output is bounded even for fragmented silhouettes.
 */
object StructureVisualEvidence {
    const val MAX_PROFILE_RUNS = 24
    const val MAX_MATERIALS = 8
    private val ELEVATIONS = listOf("front", "back", "left", "right")

    @JvmStatic
    @JvmOverloads
    fun inspect(drawing: DrawStructure, views: List<String> = ELEVATIONS, sliceY: Int? = null, frame: Box = drawing.bounds()): StructureVisualEvidenceReport {
        require(views.isNotEmpty() && views.all { it in ELEVATIONS || it in listOf("top", "slice", "isometric", "isometric_back") }) { "Use supported preview views" }
        require("slice" !in views || sliceY != null && sliceY in drawing.bounds().min().y()..drawing.bounds().max().y()) { "A slice needs an in-bounds sliceY" }
        val cells = drawing.voxels().filterNot { it.block().state().isAir }
        val projections = views.flatMap { if (it.startsWith("isometric")) ELEVATIONS else listOf(it) }.distinct().map { view ->
            project(if (view == "slice") cells.filter { it.position().y() == sliceY } else cells, view)
        }
        val reviews = projections.mapNotNull { projection ->
            val panel = projection.largestPlanarPanel ?: return@mapNotNull null
            // A review opportunity, deliberately not a warning. Flat roofs, blank defensive walls and
            // calm facades are valid choices; colour speckling must not conceal geometric flatness.
            if (projection.view !in ELEVATIONS || panel.width < 8 || panel.height < 4 || panel.cells.toLong() * 2 < projection.visibleCells) return@mapNotNull null
            StructureVisualReview(projection.view, panel.region,
                "A ${panel.width} x ${panel.height} unbroken coplanar panel occupies ${panel.cells} of ${projection.visibleCells} visible cells; material changes do not create depth.",
                "Review this elevation in clay at the same frame. If the panel is not intentional, express the building's use with a recessed bay, structural rhythm, projecting entry or secondary mass; retain quiet surfaces where the theme calls for them.")
        }.take(4)
        return StructureVisualEvidenceReport(buildBox(frame), bounds(cells), cells.size,
            cells.asSequence().map { it.position().x() to it.position().z() }.toHashSet().size, projections, reviews)
    }

    private data class Ray(val u: Int, val v: Int)
    private data class Cell(val u: Int, val v: Int, val depth: Int, val block: String)
    private data class Bar(val depth: Int, val height: Int)

    private fun project(voxels: List<DrawVoxel>, view: String): StructureProjectionEvidence {
        val top = view == "top" || view == "slice"
        val xAxis = view in listOf("front", "back", "top", "slice")
        val nearestMaximum = view in listOf("back", "right", "top", "slice")
        val visible = HashMap<Ray, Cell>()
        for (voxel in voxels) {
            val p = voxel.position()
            val u = if (xAxis) p.x() else p.z()
            val v = if (top) p.z() else p.y()
            val depth = if (top) p.y() else if (xAxis) p.z() else p.x()
            val key = Ray(u, v)
            val old = visible[key]
            if (old == null || if (nearestMaximum) depth > old.depth else depth < old.depth)
                visible[key] = Cell(u, v, depth, voxel.block().state().id())
        }
        val rows = visible.values.groupBy { it.v }.toSortedMap()
        val materialCounts = visible.values.groupingBy { it.block }.eachCount()
        val profile = visible.values.groupBy { it.u }.toSortedMap().map { (u, column) ->
            StructureProfileRun(u, u, column.minOf { it.v }, column.maxOf { it.v })
        }.fold(mutableListOf<StructureProfileRun>()) { runs, next ->
            val previous = runs.lastOrNull()
            if (previous != null && previous.to.toLong() + 1 == next.from.toLong() && previous.low == next.low && previous.high == next.high)
                runs[runs.lastIndex] = previous.copy(to = next.to)
            else runs += next
            runs
        }
        var relief = 0
        for ((ray, cell) in visible) {
            if (ray.u < Int.MAX_VALUE && visible[Ray(ray.u + 1, ray.v)]?.let { it.depth != cell.depth } == true) relief++
            if (ray.v < Int.MAX_VALUE && visible[Ray(ray.u, ray.v + 1)]?.let { it.depth != cell.depth } == true) relief++
        }
        val depths = visible.values.map { it.depth }.toHashSet()
        val panel = largestPanel(rows, top, xAxis)
        return StructureProjectionEvidence(view, if (xAxis) "x" else "z", if (top) "z" else "y",
            when (view) { "back" -> "-x"; "left" -> "-z"; "right" -> "+z"; else -> "+x" },
            visible.size, depths.size, if (depths.isEmpty()) 0 else depths.max().toLong() - depths.min().toLong(), relief, panel,
            profile.size, profile.take(MAX_PROFILE_RUNS), materialCounts.size,
            materialCounts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(MAX_MATERIALS).map { StructureVisibleMaterial(it.key, it.value) })
    }

    /** Largest equal-depth rectangle in a sparse projection, using monotone row histograms. */
    private fun largestPanel(rows: Map<Int, List<Cell>>, top: Boolean, xAxis: Boolean): StructurePlanarPanel? {
        var previousV: Int? = null
        var previous = emptyMap<Int, Bar>()
        var best: StructurePlanarPanel? = null
        fun point(u: Int, v: Int, depth: Int) = if (top) BuildPos(u, depth, v) else if (xAxis) BuildPos(u, v, depth) else BuildPos(depth, v, u)
        for ((v, row) in rows) {
            val ordered = row.sortedBy { it.u }
            val adjacentRow = previousV?.toLong()?.plus(1) == v.toLong()
            val current = ordered.associate { cell ->
                val old = if (adjacentRow) previous[cell.u] else null
                cell.u to Bar(cell.depth, if (old?.depth == cell.depth) old.height + 1 else 1)
            }
            var start = 0
            while (start < ordered.size) {
                var end = start + 1
                while (end < ordered.size && ordered[end].u.toLong() == ordered[end - 1].u.toLong() + 1 && ordered[end].depth == ordered[start].depth) end++
                val stack = ArrayDeque<Pair<Int, Int>>() // first column index, histogram height
                for (i in start..end) {
                    val height = if (i == end) 0 else current.getValue(ordered[i].u).height
                    var left = i
                    while (stack.isNotEmpty() && stack.last().second > height) {
                        val (from, h) = stack.removeLast()
                        val width = i - from
                        val area = width * h // bounded by the number of projected source cells
                        if (area > (best?.cells ?: 0)) best = StructurePlanarPanel(
                            BuildBox(point(ordered[from].u, (v.toLong() - h + 1).toInt(), ordered[start].depth), point(ordered[i - 1].u, v, ordered[start].depth)), width, h, area)
                        left = from
                    }
                    if (height > 0 && (stack.isEmpty() || stack.last().second < height)) stack.addLast(left to height)
                }
                start = end
            }
            previous = current
            previousV = v
        }
        return best
    }

    private fun bounds(voxels: List<DrawVoxel>): BuildBox? = if (voxels.isEmpty()) null else BuildBox(
        BuildPos(voxels.minOf { it.position().x() }, voxels.minOf { it.position().y() }, voxels.minOf { it.position().z() }),
        BuildPos(voxels.maxOf { it.position().x() }, voxels.maxOf { it.position().y() }, voxels.maxOf { it.position().z() }))

    private fun buildBox(box: Box) = BuildBox(BuildPos(box.min().x(), box.min().y(), box.min().z()), BuildPos(box.max().x(), box.max().y(), box.max().z()))
}
