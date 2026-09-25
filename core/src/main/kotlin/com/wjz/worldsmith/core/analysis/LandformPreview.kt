package com.wjz.worldsmith.core.analysis

import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.TerrainPlanValidator
import kotlinx.serialization.Serializable
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@Serializable
data class LandformSectionSample(
    val distanceBlocks: Double,
    val surfaceY: Double,
    val lowerRoughnessY: Double,
    val upperRoughnessY: Double,
)

@Serializable
data class LandformPreviewReport(
    val anchor: Anchor,
    val incomingSurfaceY: Double,
    val seaLevel: Int,
    val samples: List<LandformSectionSample>,
    val minimumSurfaceY: Double,
    val maximumSurfaceY: Double,
    val maximumSampledGrade: Double,
    val flatCoreWidthBlocks: Double?,
    val notes: List<String>,
    val previewType: String = "unwarped-landform-section-not-seeded-terrain",
    val minecraftStarted: Boolean = false,
    val actualTerrainSampled: Boolean = false,
    val buildingSiteVerified: Boolean = false,
)

/** A bounded diagram of the actual authored profile, not a second terrain generator. */
object LandformPreview {
    const val SAMPLES = 257
    private const val MARGIN = 1.12
    const val WIDTH = 1280
    const val HEIGHT = 840

    fun analyze(terrain: TerrainPlan, anchorId: String, incomingSurfaceY: Double): LandformPreviewReport {
        val errors = TerrainPlanValidator.validate(terrain).filter { it.severity == DiagnosticSeverity.ERROR }
        require(errors.isEmpty()) { "Repair terrain before preview: ${errors.take(8).joinToString { "${it.path}: ${it.message}" }}" }
        require(incomingSurfaceY.isFinite() && incomingSurfaceY >= terrain.minY && incomingSurfaceY <= terrain.minY.toDouble() + terrain.height - 1) {
            "incomingSurfaceY is an explicit constant reference plane inside the terrain height range, not an inferred height"
        }
        val shape = terrain.shape as? TerrainShape.Procedural ?: throw IllegalArgumentException("Landform sections require procedural terrain")
        val anchor = requireNotNull(shape.anchors.singleOrNull { it.id == anchorId }) { "Unknown terrain anchor '$anchorId'" }
        val samples = (0 until SAMPLES).map { index ->
            val normalized = (index.toDouble() / (SAMPLES - 1) * 2.0 - 1.0) * MARGIN
            fun height(texture: Double) = AnchorReliefSampler.sample(anchor.relief, abs(normalized), incomingSurfaceY, texture, anchor.falloff)
            LandformSectionSample(normalized * anchor.radius, height(0.0), height(-1.0), height(1.0))
        }
        val coreWidth = when (val relief = anchor.relief) {
            is AnchorRelief.Offset -> null
            is AnchorRelief.Mesa -> 2.0 * relief.topRadius * anchor.radius
            is AnchorRelief.Caldera -> 2.0 * relief.floorRadius * anchor.radius
        }
        return LandformPreviewReport(anchor, incomingSurfaceY, terrain.seaLevel, samples,
            samples.minOf { it.surfaceY }, samples.maxOf { it.surfaceY },
            samples.zipWithNext().maxOf { (a, b) -> abs(b.surfaceY - a.surfaceY) / (b.distanceBlocks - a.distanceBlocks) }, coreWidth,
            buildList {
                add("Cross-section through one representative anchor instance; line placements are viewed across their width. Position, seed and boundary warp are not sampled.")
                add("The dashed baseline is the supplied constant incomingSurfaceY, not a measured surrounding landscape. Change it to inspect joins at different surrounding heights; the baseline has no slope.")
                add("The shaded envelope is the mathematical local-texture range -1..1, not sampled noise. The solid line uses localTexture=0.")
                add("Sea level is a reference line only; fluid occupancy, caves, terrain bands, structure foundations, climate and traversal are not evaluated.")
                add("Global bottom/ceiling density fades are excluded. Offset profiles can mathematically enter those fade zones even though actual generation restricts the final world surface.")
                if (coreWidth != null) add("flatCoreWidthBlocks is the unwarped nominal core diameter before roughness, overlapping anchors, caves and bands; it is not guaranteed usable building width.")
                if (shape.anchors.size > 1) add("Other anchors are excluded from this isolated section. Actual generation composes anchors in their declared order.")
                if (shape.bands.isNotEmpty()) add("This terrain includes ${shape.bands.size} additive/carving bands which can change this silhouette after the anchor profile is applied.")
            })
    }

    fun png(report: LandformPreviewReport): ByteArray {
        require(report.samples.size == SAMPLES) { "Use a complete bounded profile report" }
        require(report.samples.all { it.distanceBlocks.isFinite() && it.surfaceY.isFinite() && it.lowerRoughnessY.isFinite() && it.upperRoughnessY.isFinite() })
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0x15232C); g.fillRect(0, 0, WIDTH, HEIGHT)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 25); g.color = Color(0xECF4F4)
            g.drawString("Worldsmith / ${report.anchor.id} / ${profileName(report.anchor.relief)}", 64, 56)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 16); g.color = Color(0xA4BCC7)
            g.drawString("AUTHORED LANDFORM SECTION  -  unwarped profile, explicit reference plane", 64, 88)

            val left = 96.0; val right = 1200.0; val top = 134.0; val bottom = 590.0
            val rawLow = min(report.samples.minOf { it.lowerRoughnessY }, min(report.incomingSurfaceY, report.seaLevel.toDouble()))
            val rawHigh = max(report.samples.maxOf { it.upperRoughnessY }, max(report.incomingSurfaceY, report.seaLevel.toDouble()))
            val padding = max(8.0, (rawHigh - rawLow) * .12)
            val low = floor((rawLow - padding) / 10) * 10
            val high = ceil((rawHigh + padding) / 10) * 10
            val halfWidth = report.anchor.radius * MARGIN
            fun x(distance: Double) = left + (distance + halfWidth) / (2 * halfWidth) * (right - left)
            fun y(height: Double) = bottom - (height - low) / (high - low) * (bottom - top)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            for (i in 0..6) {
                val height = low + (high - low) * i / 6
                val py = y(height).toInt()
                g.color = Color(0x31444E); g.drawLine(left.toInt(), py, right.toInt(), py)
                g.color = Color(0xAAC0CA); g.drawString("%.0f".format(java.util.Locale.ROOT, height), 45, py + 5)
            }
            g.drawString("Y", 47, 119)

            val fill = Path2D.Double()
            fill.moveTo(x(report.samples.first().distanceBlocks), bottom)
            report.samples.forEach { fill.lineTo(x(it.distanceBlocks), y(it.surfaceY)) }
            fill.lineTo(x(report.samples.last().distanceBlocks), bottom); fill.closePath()
            g.color = Color(0x344A43); g.fill(fill)
            val envelope = Path2D.Double()
            report.samples.forEachIndexed { i, s -> if (i == 0) envelope.moveTo(x(s.distanceBlocks), y(s.upperRoughnessY)) else envelope.lineTo(x(s.distanceBlocks), y(s.upperRoughnessY)) }
            report.samples.asReversed().forEach { envelope.lineTo(x(it.distanceBlocks), y(it.lowerRoughnessY)) }; envelope.closePath()
            g.color = Color(0x728D6A); g.fill(envelope)

            g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(7f, 5f), 0f)
            g.color = Color(0x9EABB1); g.drawLine(left.toInt(), y(report.incomingSurfaceY).toInt(), right.toInt(), y(report.incomingSurfaceY).toInt())
            g.color = Color(0x61BAE5); g.drawLine(left.toInt(), y(report.seaLevel.toDouble()).toInt(), right.toInt(), y(report.seaLevel.toDouble()).toInt())
            for (distance in listOf(-report.anchor.radius.toDouble(), 0.0, report.anchor.radius.toDouble())) {
                g.color = Color(0x617983); g.drawLine(x(distance).toInt(), top.toInt(), x(distance).toInt(), bottom.toInt())
                g.color = Color(0xB4C9D0); g.drawString("%.0f".format(java.util.Locale.ROOT, distance), x(distance).toInt() - 16, bottom.toInt() + 24)
            }
            val line = Path2D.Double()
            report.samples.forEachIndexed { i, s -> if (i == 0) line.moveTo(x(s.distanceBlocks), y(s.surfaceY)) else line.lineTo(x(s.distanceBlocks), y(s.surfaceY)) }
            g.color = Color(0xD5E8B3); g.stroke = BasicStroke(3f); g.draw(line)
            g.color = Color(0xB4C9D0); g.drawString("distance across local anchor (blocks)", 496, 634)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
            g.drawString("Reference base Y = ${report.incomingSurfaceY}   |   sea level = ${report.seaLevel}   |   radius = ${report.anchor.radius}", 64, 679)
            report.flatCoreWidthBlocks?.let { g.drawString("Nominal flat core width = %.1f blocks (before warp, texture and later carving)".format(java.util.Locale.ROOT, it), 64, 707) }
            g.color = Color(0xEDBF83); g.drawString("Not seeded terrain, a game screenshot, fluid simulation or a verified building site.", 64, 758)
            g.color = Color(0xA4BCC7); g.drawString("Horizontal and vertical scales differ. Compare matching base heights; inspect placement separately.", 64, 788)
        } finally { g.dispose() }
        return ByteArrayOutputStream().use { output -> check(ImageIO.write(image, "png", output)); output.toByteArray() }
    }

    private fun profileName(relief: AnchorRelief) = when (relief) {
        is AnchorRelief.Offset -> "OFFSET"
        is AnchorRelief.Mesa -> "MESA"
        is AnchorRelief.Caldera -> "CALDERA"
    }
}
