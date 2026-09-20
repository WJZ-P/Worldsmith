package com.wjz.worldsmith.core.ability

import java.util.Collections
import kotlin.math.*

data class AbilityRegionBounds(val minX: Double, val minY: Double, val minZ: Double, val maxX: Double, val maxY: Double, val maxZ: Double) {
    init {
        listOf(minX, minY, minZ, maxX, maxY, maxZ).forEach(AbilityValues::checkNumber)
        require(minX <= maxX && minY <= maxY && minZ <= maxZ) { "Invalid region bounds." }
        require(maxX-minX <= 64 && maxY-minY <= 64 && maxZ-minZ <= 64) { "Region half extent exceeds 32 blocks." }
    }
}

/** Providers are reusable geometry, never a closed list of attacks. Region data is serialized as values. */
interface AbilityRegionProvider {
    fun validate(region: AbilityValue.MapValue)
    fun bounds(region: AbilityValue.MapValue): AbilityRegionBounds
    fun contains(region: AbilityValue.MapValue, x: Double, y: Double, z: Double): Boolean
    fun outline(region: AbilityValue.MapValue, maxPoints: Int): List<AbilityValue.VectorValue>
}

object AbilityRegions {
    @Volatile private var providers: Map<String, AbilityRegionProvider> = mapOf(
        "sphere" to SphereProvider,
        "box" to BoxProvider,
        "union" to UnionProvider,
        "translate" to TranslateProvider,
    )

    /** Registration is additive; replacing a provider would change already prepared programs. */
    @JvmStatic @Synchronized fun register(name: String, provider: AbilityRegionProvider) {
        require(name.matches(Regex("[a-z][a-z0-9_.]{0,63}"))) { "Invalid region provider name." }
        require(name !in providers) { "Region provider '$name' is already registered." }
        providers = Collections.unmodifiableMap(LinkedHashMap(providers).apply { put(name, provider) })
    }

    @JvmStatic fun registeredProviders(): Map<String, AbilityRegionProvider> = Collections.unmodifiableMap(LinkedHashMap(providers))

    @JvmStatic fun create(provider: String, fields: Map<String, AbilityValue>): AbilityValue.MapValue {
        require("provider" !in fields) { "Region fields must not override provider." }
        return AbilityValues.map(fields + ("provider" to AbilityValues.text(provider))).also(::validate)
    }

    @JvmStatic fun sphere(center: AbilityValue.VectorValue, radius: Double): AbilityValue.MapValue = create("sphere", mapOf("center" to center, "radius" to AbilityValues.number(radius)))
    @JvmStatic fun box(center: AbilityValue.VectorValue, halfExtents: AbilityValue.VectorValue): AbilityValue.MapValue = create("box", mapOf("center" to center, "halfExtents" to halfExtents))
    @JvmStatic fun union(regions: List<AbilityValue>): AbilityValue.MapValue = create("union", mapOf("regions" to AbilityValues.list(regions)))
    @JvmStatic fun translate(region: AbilityValue, offset: AbilityValue.VectorValue): AbilityValue.MapValue = create("translate", mapOf("region" to region, "offset" to offset))

    private fun resolve(region: AbilityValue): Pair<AbilityValue.MapValue, AbilityRegionProvider> {
        AbilityValues.validate(region)
        val map = region as? AbilityValue.MapValue ?: throw IllegalArgumentException("Expected REGION.")
        val name = (map.values["provider"] as? AbilityValue.TextValue)?.value ?: throw IllegalArgumentException("Region requires a provider tag.")
        return map to requireNotNull(providers[name]) { "Unknown region provider '$name'." }
    }

    @JvmStatic fun validate(region: AbilityValue) {
        val (map, provider) = resolve(region)
        provider.validate(map)
        provider.bounds(map) // Also validates finite/bounded composite geometry.
    }

    @JvmStatic fun bounds(region: AbilityValue): AbilityRegionBounds {
        val (map, provider) = resolve(region)
        provider.validate(map)
        return provider.bounds(map)
    }

    @JvmStatic fun contains(region: AbilityValue, x: Double, y: Double, z: Double): Boolean {
        AbilityValues.checkNumber(x); AbilityValues.checkNumber(y); AbilityValues.checkNumber(z)
        val (map, provider) = resolve(region)
        provider.validate(map)
        val bounds = provider.bounds(map)
        if (x < bounds.minX || x > bounds.maxX || y < bounds.minY || y > bounds.maxY || z < bounds.minZ || z > bounds.maxZ) return false
        return provider.contains(map, x, y, z)
    }

    @JvmStatic @JvmOverloads fun outline(region: AbilityValue, maxPoints: Int = 48): List<AbilityValue.VectorValue> {
        require(maxPoints in 1..64) { "Region outline budget must be 1..64." }
        val (map, provider) = resolve(region)
        provider.validate(map)
        val bounds = provider.bounds(map)
        val points = provider.outline(map, maxPoints)
        require(points.size <= maxPoints) { "Region provider exceeded outline budget." }
        points.forEach {
            require(it.x >= bounds.minX-1e-7 && it.x <= bounds.maxX+1e-7 && it.y >= bounds.minY-1e-7 && it.y <= bounds.maxY+1e-7 && it.z >= bounds.minZ-1e-7 && it.z <= bounds.maxZ+1e-7) { "Region outline point is outside provider bounds." }
        }
        return Collections.unmodifiableList(ArrayList(points))
    }

    private fun field(region: AbilityValue.MapValue, key: String): AbilityValue = requireNotNull(region.values[key]) { "Region is missing '$key'." }
    private fun vector(region: AbilityValue.MapValue, key: String) = AbilityValues.vectorOf(field(region, key))
    private fun number(region: AbilityValue.MapValue, key: String) = AbilityValues.numberOf(field(region, key))

    private object SphereProvider : AbilityRegionProvider {
        override fun validate(region: AbilityValue.MapValue) {
            vector(region, "center")
            require(number(region, "radius") in 0.0..32.0) { "Sphere radius must be 0..32." }
        }
        override fun bounds(region: AbilityValue.MapValue): AbilityRegionBounds {
            val c = vector(region, "center"); val r = number(region, "radius")
            return AbilityRegionBounds(c.x-r, c.y-r, c.z-r, c.x+r, c.y+r, c.z+r)
        }
        override fun contains(region: AbilityValue.MapValue, x: Double, y: Double, z: Double): Boolean {
            val c = vector(region, "center"); val r = number(region, "radius")
            return (x-c.x).pow(2) + (y-c.y).pow(2) + (z-c.z).pow(2) <= r*r
        }
        override fun outline(region: AbilityValue.MapValue, maxPoints: Int): List<AbilityValue.VectorValue> {
            val c = vector(region, "center"); val r = number(region, "radius")
            return (0 until maxPoints).map { val angle = 2*PI*it/maxPoints; AbilityValues.vector(c.x+cos(angle)*r, c.y, c.z+sin(angle)*r) }
        }
    }

    private object BoxProvider : AbilityRegionProvider {
        override fun validate(region: AbilityValue.MapValue) {
            vector(region, "center"); val h = vector(region, "halfExtents")
            require(h.x in 0.0..32.0 && h.y in 0.0..32.0 && h.z in 0.0..32.0) { "Box half extents must be 0..32." }
        }
        override fun bounds(region: AbilityValue.MapValue): AbilityRegionBounds {
            val c = vector(region, "center"); val h = vector(region, "halfExtents")
            return AbilityRegionBounds(c.x-h.x, c.y-h.y, c.z-h.z, c.x+h.x, c.y+h.y, c.z+h.z)
        }
        override fun contains(region: AbilityValue.MapValue, x: Double, y: Double, z: Double) = true // bounds already checked
        override fun outline(region: AbilityValue.MapValue, maxPoints: Int): List<AbilityValue.VectorValue> {
            val c = vector(region, "center"); val h = vector(region, "halfExtents")
            return (0 until maxPoints).map {
                val step = it * 4.0 / maxPoints; val fraction = step-floor(step)
                val (x, z) = when (floor(step).toInt()) {
                    0 -> Pair(-h.x+2*h.x*fraction, -h.z)
                    1 -> Pair(h.x, -h.z+2*h.z*fraction)
                    2 -> Pair(h.x-2*h.x*fraction, h.z)
                    else -> Pair(-h.x, h.z-2*h.z*fraction)
                }
                AbilityValues.vector(c.x+x, c.y, c.z+z)
            }
        }
    }

    private object UnionProvider : AbilityRegionProvider {
        private fun children(region: AbilityValue.MapValue) = AbilityValues.listOf(field(region, "regions"))
        override fun validate(region: AbilityValue.MapValue) {
            require(children(region).size in 1..16) { "Region union needs 1..16 children." }
            children(region).forEach(AbilityRegions::validate)
        }
        override fun bounds(region: AbilityValue.MapValue): AbilityRegionBounds {
            val boxes = children(region).map(AbilityRegions::bounds)
            return AbilityRegionBounds(boxes.minOf { it.minX }, boxes.minOf { it.minY }, boxes.minOf { it.minZ }, boxes.maxOf { it.maxX }, boxes.maxOf { it.maxY }, boxes.maxOf { it.maxZ })
        }
        override fun contains(region: AbilityValue.MapValue, x: Double, y: Double, z: Double) = children(region).any { AbilityRegions.contains(it, x, y, z) }
        override fun outline(region: AbilityValue.MapValue, maxPoints: Int): List<AbilityValue.VectorValue> {
            val children = children(region).take(maxPoints)
            return children.flatMapIndexed { index, child -> AbilityRegions.outline(child, maxPoints/children.size + if (index < maxPoints%children.size) 1 else 0) }
        }
    }

    private object TranslateProvider : AbilityRegionProvider {
        override fun validate(region: AbilityValue.MapValue) { vector(region, "offset"); AbilityRegions.validate(field(region, "region")) }
        override fun bounds(region: AbilityValue.MapValue): AbilityRegionBounds {
            val b = AbilityRegions.bounds(field(region, "region")); val o = vector(region, "offset")
            return AbilityRegionBounds(b.minX+o.x, b.minY+o.y, b.minZ+o.z, b.maxX+o.x, b.maxY+o.y, b.maxZ+o.z)
        }
        override fun contains(region: AbilityValue.MapValue, x: Double, y: Double, z: Double): Boolean {
            val o = vector(region, "offset"); return AbilityRegions.contains(field(region, "region"), x-o.x, y-o.y, z-o.z)
        }
        override fun outline(region: AbilityValue.MapValue, maxPoints: Int): List<AbilityValue.VectorValue> {
            val o = vector(region, "offset")
            return AbilityRegions.outline(field(region, "region"), maxPoints).map { AbilityValues.vector(it.x+o.x, it.y+o.y, it.z+o.z) }
        }
    }
}
