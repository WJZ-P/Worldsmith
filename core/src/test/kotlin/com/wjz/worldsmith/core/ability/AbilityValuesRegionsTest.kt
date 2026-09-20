package com.wjz.worldsmith.core.ability

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AbilityValuesRegionsTest {
    @Test fun `values copy source containers and reject mutation at every exposed layer`() {
        val entries = mutableListOf<AbilityValue>(AbilityValues.number(1.0))
        val list = AbilityValues.list(entries)
        val fields = mutableMapOf<String, AbilityValue>("list" to list)
        val map = AbilityValues.map(fields)
        entries.clear(); fields.clear()
        assertEquals(1, list.values.size)
        assertEquals(list, map.values["list"])
        assertThrows(UnsupportedOperationException::class.java) { (list.values as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (map.values as MutableMap).clear() }
    }

    @Test fun `all value kinds and nested geometry survive deterministic state round trip`() {
        val region = AbilityRegions.translate(AbilityRegions.union(listOf(
            AbilityRegions.sphere(AbilityValues.vector(1.0, 2.0, 3.0), 2.0),
            AbilityRegions.box(AbilityValues.vector(4.0, 2.0, 3.0), AbilityValues.vector(1.0, 2.0, 1.0)),
        )), AbilityValues.vector(0.0, 0.0, 1.0))
        val state = linkedMapOf(
            "text" to AbilityValues.text("Unicode 魔法 \" newline\n"), "null" to AbilityValues.none(),
            "bool" to AbilityValues.bool(true), "number" to AbilityValues.number(3.25),
            "actor" to AbilityValues.entity("opaque-handle"), "region" to region,
            "list" to AbilityValues.list(listOf(AbilityValues.number(1.0), AbilityValues.text("two"))),
        )
        val encoded = AbilityValues.encodeState(state)
        assertEquals(state, AbilityValues.decodeState(encoded))
        assertEquals(encoded, AbilityValues.encodeState(state.entries.reversed().associate { it.toPair() }))
    }

    @Test fun `number text list map depth and state byte limits are enforced`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 1e9 + 1).forEach { number ->
            assertThrows(IllegalArgumentException::class.java) { AbilityValues.number(number) }
        }
        assertThrows(IllegalArgumentException::class.java) { AbilityValues.text("a".repeat(4097)) }
        assertThrows(IllegalArgumentException::class.java) { AbilityValues.list(List(65) { AbilityValues.none() }) }
        assertThrows(IllegalArgumentException::class.java) { AbilityValues.map((0..64).associate { "k$it" to AbilityValues.none() }) }
        assertThrows(IllegalArgumentException::class.java) {
            var value = AbilityValues.none()
            repeat(10) { value = AbilityValues.list(listOf(value)) }
        }
        val large = (0 until 20).associate { "k$it" to AbilityValues.text("a".repeat(4096)) }
        assertThrows(IllegalArgumentException::class.java) { AbilityValues.encodeState(large) }
        assertThrows(IllegalArgumentException::class.java) { AbilityValues.decodeState(" ".repeat(65537)) }
    }

    @Test fun `incremental entry metrics exactly match full UTF8 JSON encoding`() {
        val state = linkedMapOf(
            "quotes\"and\\slashes" to AbilityValues.text("魔法\n\r\t\"\\\u0001"),
            "number" to AbilityValues.number(-0.0),
            "region" to AbilityRegions.sphere(AbilityValues.vector(0.0, 64.0, 0.0), 2.0),
            "list" to AbilityValues.list(listOf(AbilityValues.none(), AbilityValues.bool(true), AbilityValues.text("🍀"))),
            "map" to AbilityValues.map(mapOf("nested" to AbilityValues.number(1e9))),
        )
        for (count in 0..state.size) {
            val subset = state.entries.take(count).associate { it.toPair() }
            val metrics = subset.map { (key, value) -> AbilityValues.stateEntryMetrics(key, value) }
            val expected = 2 + metrics.sumOf { it.bytes } + maxOf(0, count-1)
            assertEquals(expected, AbilityValues.encodeState(subset).toByteArray(Charsets.UTF_8).size)
        }
    }

    @Test fun `sphere volume has vertical collision and shared bounded outline`() {
        val sphere = AbilityRegions.sphere(AbilityValues.vector(2.0, 64.0, 3.0), 2.0)
        assertTrue(AbilityRegions.contains(sphere, 2.0, 65.9, 3.0))
        assertFalse(AbilityRegions.contains(sphere, 2.0, 66.1, 3.0))
        assertFalse(AbilityRegions.contains(sphere, 3.9, 65.9, 3.0))
        assertEquals(AbilityRegionBounds(0.0, 62.0, 1.0, 4.0, 66.0, 5.0), AbilityRegions.bounds(sphere))
        val outline = AbilityRegions.outline(sphere, 32)
        assertEquals(32, outline.size)
        outline.forEach { assertEquals(64.0, it.y); assertEquals(4.0, (it.x-2)*(it.x-2) + (it.z-3)*(it.z-3), 1e-7) }
    }

    @Test fun `box union translation use identical regions for query and preview`() {
        val box = AbilityRegions.box(AbilityValues.vector(0.0, 0.0, 0.0), AbilityValues.vector(1.0, 2.0, 3.0))
        val translated = AbilityRegions.translate(box, AbilityValues.vector(5.0, 4.0, 3.0))
        val union = AbilityRegions.union(listOf(box, translated))
        assertTrue(AbilityRegions.contains(union, 0.0, 0.0, 0.0))
        assertTrue(AbilityRegions.contains(union, 5.0, 4.0, 3.0))
        assertFalse(AbilityRegions.contains(union, 2.5, 0.0, 0.0))
        assertEquals(AbilityRegionBounds(-1.0, -2.0, -3.0, 6.0, 6.0, 6.0), AbilityRegions.bounds(union))
        assertEquals(31, AbilityRegions.outline(union, 31).size)
        assertEquals(1, AbilityRegions.outline(union, 1).size)
    }

    @Test fun `region validation rejects missing providers invalid extents and oversized compounds`() {
        assertThrows(IllegalArgumentException::class.java) { AbilityRegions.validate(AbilityValues.map(emptyMap())) }
        assertThrows(IllegalArgumentException::class.java) { AbilityRegions.create("unknown", emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { AbilityRegions.sphere(AbilityValues.vector(0.0, 0.0, 0.0), -1.0) }
        assertThrows(IllegalArgumentException::class.java) { AbilityRegions.sphere(AbilityValues.vector(0.0, 0.0, 0.0), 33.0) }
        assertThrows(IllegalArgumentException::class.java) { AbilityRegions.box(AbilityValues.vector(0.0, 0.0, 0.0), AbilityValues.vector(-1.0, 1.0, 1.0)) }
        assertThrows(IllegalArgumentException::class.java) { AbilityRegions.union(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AbilityRegions.union(listOf(AbilityRegions.sphere(AbilityValues.vector(-40.0, 0.0, 0.0), 1.0), AbilityRegions.sphere(AbilityValues.vector(40.0, 0.0, 0.0), 1.0))) }
    }

    @Test fun `registered geometry and callable constructor extend language without adding opcodes`() {
        val provider = object : AbilityRegionProvider {
            private fun base(region: AbilityValue.MapValue) = AbilityRegions.sphere(region.values.getValue("center") as AbilityValue.VectorValue, 2.0)
            override fun validate(region: AbilityValue.MapValue) { require(region.values["center"] is AbilityValue.VectorValue) }
            override fun bounds(region: AbilityValue.MapValue) = AbilityRegions.bounds(base(region))
            override fun contains(region: AbilityValue.MapValue, x: Double, y: Double, z: Double): Boolean {
                val center = region.values.getValue("center") as AbilityValue.VectorValue
                val distance = (x-center.x)*(x-center.x) + (y-center.y)*(y-center.y) + (z-center.z)*(z-center.z)
                return distance in 1.0..4.0
            }
            override fun outline(region: AbilityValue.MapValue, maxPoints: Int) = AbilityRegions.outline(base(region), maxPoints)
        }
        AbilityRegions.register("test_shell", provider)
        assertThrows(IllegalArgumentException::class.java) { AbilityRegions.register("test_shell", provider) }
        val registry = AbilityCapabilities.standard().extend(AbilityCapabilitySpec("shape.test_shell", arguments = listOf(AbilityType.VECTOR), result = AbilityType.REGION), AbilityPureFunction { AbilityRegions.create("test_shell", mapOf("center" to it[0])) })
        val compiled = AbilityCompiler.compile(AbilityProgramDefinition("test_shell", "Extension", "on start { state.region = shape.test_shell(vec(0, 0, 0)); }"), registry)
        val machine = AbilityMachine(compiled, AbilityHost { _, _ -> fail("Geometry extension reached world host") }, emptyMap())
        assertNull(machine.tick(0).error)
        val region = machine.snapshotState().getValue("region")
        assertFalse(AbilityRegions.contains(region, 0.0, 0.0, 0.0))
        assertTrue(AbilityRegions.contains(region, 1.5, 0.0, 0.0))
        assertEquals(12, AbilityRegions.outline(region, 12).size)
        assertEquals(region, AbilityValues.decodeState(AbilityValues.encodeState(mapOf("region" to region)))["region"])
    }
}
