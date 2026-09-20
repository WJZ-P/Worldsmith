package com.wjz.worldsmith.core.ability

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Collections
import kotlin.math.abs

@Serializable
sealed class AbilityValue {
    @Serializable @SerialName("number") data class NumberValue(val value: Double) : AbilityValue() {
        init { AbilityValues.checkNumber(value) }
    }
    @Serializable @SerialName("bool") data class BoolValue(val value: Boolean) : AbilityValue()
    @Serializable @SerialName("text") data class TextValue(val value: String) : AbilityValue() {
        init { require(value.length <= 4096) { "Ability text exceeds 4096 characters." } }
    }
    @Serializable @SerialName("vector") data class VectorValue(val x: Double, val y: Double, val z: Double) : AbilityValue() {
        init { AbilityValues.checkNumber(x); AbilityValues.checkNumber(y); AbilityValues.checkNumber(z) }
    }
    @Serializable @SerialName("entity") data class EntityValue(val id: String) : AbilityValue() {
        init { require(id.isNotBlank() && id.length <= 128) { "Entity handle must contain 1..128 characters." } }
    }
    @Serializable(with = AbilityListSerializer::class) @SerialName("list")
    class ListValue(values: List<AbilityValue>) : AbilityValue() {
        val values: List<AbilityValue> = Collections.unmodifiableList(ArrayList(values))
        init { AbilityValues.validate(this) }
        override fun equals(other: Any?): Boolean = other is ListValue && values == other.values
        override fun hashCode(): Int = values.hashCode()
        override fun toString(): String = values.toString()
    }
    @Serializable(with = AbilityMapSerializer::class) @SerialName("map")
    class MapValue(values: Map<String, AbilityValue>) : AbilityValue() {
        val values: Map<String, AbilityValue> = Collections.unmodifiableMap(LinkedHashMap(values))
        init { AbilityValues.validate(this) }
        override fun equals(other: Any?): Boolean = other is MapValue && values == other.values
        override fun hashCode(): Int = values.hashCode()
        override fun toString(): String = values.toString()
    }
    @Serializable @SerialName("null") data object NullValue : AbilityValue()
}

/** Constructors copy collections; every value is immutable, finite, and bounded. */
object AbilityValues {
    private val stateSerializer = MapSerializer(String.serializer(), AbilityValue.serializer())
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }

    @JvmStatic fun number(value: Double): AbilityValue.NumberValue = AbilityValue.NumberValue(value)
    @JvmStatic fun bool(value: Boolean): AbilityValue.BoolValue = AbilityValue.BoolValue(value)
    @JvmStatic fun text(value: String): AbilityValue.TextValue = AbilityValue.TextValue(value)
    @JvmStatic fun vector(x: Double, y: Double, z: Double): AbilityValue.VectorValue = AbilityValue.VectorValue(x, y, z)
    @JvmStatic fun entity(id: String): AbilityValue.EntityValue = AbilityValue.EntityValue(id)
    @JvmStatic fun list(values: List<AbilityValue>): AbilityValue.ListValue = AbilityValue.ListValue(values)
    @JvmStatic fun map(values: Map<String, AbilityValue>): AbilityValue.MapValue = AbilityValue.MapValue(values)
    @JvmStatic fun none(): AbilityValue = AbilityValue.NullValue

    internal fun checkNumber(value: Double) {
        require(value.isFinite() && abs(value) <= 1e9) { "Ability number must be finite with magnitude at most 1e9." }
    }

    @JvmStatic fun validate(value: AbilityValue) {
        countNodes(value)
    }

    private fun countNodes(value: AbilityValue): Int {
        var nodes = 0
        fun visit(current: AbilityValue, depth: Int) {
            require(depth <= 8) { "Ability value nesting exceeds 8." }
            require(++nodes <= 2048) { "Ability value exceeds 2048 nodes." }
            when (current) {
                is AbilityValue.ListValue -> {
                    require(current.values.size <= 64) { "Ability list exceeds 64 entries." }
                    current.values.forEach { visit(it, depth + 1) }
                }
                is AbilityValue.MapValue -> {
                    require(current.values.size <= 64) { "Ability map exceeds 64 entries." }
                    current.values.forEach { (key, entry) ->
                        require(key.length in 1..128) { "Ability map key must contain 1..128 characters." }
                        visit(entry, depth + 1)
                    }
                }
                else -> Unit
            }
        }
        visit(value, 0)
        return nodes
    }

    @JvmStatic fun encodeState(state: Map<String, AbilityValue>): String {
        AbilityValue.MapValue(state)
        return encodeStateBytes(state).toString(Charsets.UTF_8)
    }

    /** Stop serialization AT the byte limit instead of first allocating an oversized JSON string. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun encodeStateBytes(state: Map<String, AbilityValue>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(value: Int) {
                require(bytes.size() < 65536) { "Ability state exceeds 64 KiB." }
                bytes.write(value)
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                require(length <= 65536 - bytes.size()) { "Ability state exceeds 64 KiB." }
                bytes.write(buffer, offset, length)
            }
        }
        json.encodeToStream(stateSerializer, state.toSortedMap(), bounded)
        return bytes.toByteArray()
    }

    internal fun stateEntryMetrics(key: String, value: AbilityValue): AbilityStateEntryMetrics {
        val singleton = mapOf(key to value)
        // Wrapping the entry enforces the extra state-map depth, exactly as encodeState does.
        val wrapper = AbilityValue.MapValue(singleton)
        return AbilityStateEntryMetrics(countNodes(wrapper) - 1, encodeStateBytes(singleton).size - 2)
    }

    @JvmStatic fun decodeState(source: String): Map<String, AbilityValue> {
        require(source.length <= 65536) { "Ability state exceeds 64 KiB." }
        require(source.toByteArray(Charsets.UTF_8).size <= 65536) { "Ability state exceeds 64 KiB." }
        // Reject malicious JSON nesting before the serializer recursively allocates values.
        var depth = 0
        var inString = false
        var escaped = false
        for (character in source) {
            if (inString) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
            } else when (character) {
                '"' -> inString = true
                '{', '[' -> { depth++; require(depth <= 32) { "Ability state JSON nesting exceeds 32." } }
                '}', ']' -> depth--
            }
        }
        val decoded = json.decodeFromString(stateSerializer, source)
        encodeState(decoded)
        return Collections.unmodifiableMap(LinkedHashMap(decoded))
    }

    internal fun stateCopy(state: Map<String, AbilityValue>): Map<String, AbilityValue> {
        encodeState(state)
        return Collections.unmodifiableMap(LinkedHashMap(state))
    }

    internal fun numberOf(value: AbilityValue): Double = (value as? AbilityValue.NumberValue)?.value
        ?: throw IllegalArgumentException("Expected NUMBER.")
    internal fun boolOf(value: AbilityValue): Boolean = (value as? AbilityValue.BoolValue)?.value
        ?: throw IllegalArgumentException("Expected BOOL.")
    internal fun vectorOf(value: AbilityValue): AbilityValue.VectorValue = value as? AbilityValue.VectorValue
        ?: throw IllegalArgumentException("Expected VECTOR.")
    internal fun listOf(value: AbilityValue): List<AbilityValue> = (value as? AbilityValue.ListValue)?.values
        ?: throw IllegalArgumentException("Expected LIST.")
    internal fun integerOf(value: AbilityValue, min: Int = 0, max: Int = 12000): Int {
        val number = numberOf(value)
        require(number >= min && number <= max && number == kotlin.math.floor(number)) { "Expected integer in $min..$max." }
        return number.toInt()
    }
}

/** bytes counts a serialized key:value pair, excluding the singleton object's two braces. */
internal data class AbilityStateEntryMetrics(val nodes: Int, val bytes: Int)

object AbilityListSerializer : KSerializer<AbilityValue.ListValue> {
    private val list by lazy { ListSerializer(AbilityValue.serializer()) }
    override val descriptor = buildClassSerialDescriptor("list") { element("values", AbilityCollectionDescriptor(false)) }
    override fun serialize(encoder: Encoder, value: AbilityValue.ListValue) = encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor, 0, list, value.values)
    }
    override fun deserialize(decoder: Decoder): AbilityValue.ListValue = decoder.decodeStructure(descriptor) {
        var values: List<AbilityValue>? = null
        while (true) when (val index = decodeElementIndex(descriptor)) {
            -1 -> break
            0 -> values = decodeSerializableElement(descriptor, 0, list)
            else -> throw IllegalArgumentException("Unknown list field $index.")
        }
        AbilityValue.ListValue(requireNotNull(values) { "Missing list values." })
    }
}

object AbilityMapSerializer : KSerializer<AbilityValue.MapValue> {
    private val map by lazy { MapSerializer(String.serializer(), AbilityValue.serializer()) }
    override val descriptor = buildClassSerialDescriptor("map") { element("values", AbilityCollectionDescriptor(true)) }
    override fun serialize(encoder: Encoder, value: AbilityValue.MapValue) = encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor, 0, map, value.values)
    }
    override fun deserialize(decoder: Decoder): AbilityValue.MapValue = decoder.decodeStructure(descriptor) {
        var values: Map<String, AbilityValue>? = null
        while (true) when (val index = decodeElementIndex(descriptor)) {
            -1 -> break
            0 -> values = decodeSerializableElement(descriptor, 0, map)
            else -> throw IllegalArgumentException("Unknown map field $index.")
        }
        AbilityValue.MapValue(requireNotNull(values) { "Missing map values." })
    }
}

/** Defers the recursive sealed value descriptor until AFTER its serializer is initialized. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class, kotlinx.serialization.SealedSerializationApi::class)
private class AbilityCollectionDescriptor(private val map: Boolean) : SerialDescriptor {
    override val serialName: String = if (map) "kotlin.collections.LinkedHashMap" else "kotlin.collections.ArrayList"
    override val kind: SerialKind = if (map) StructureKind.MAP else StructureKind.LIST
    override val elementsCount: Int = if (map) 2 else 1
    override fun getElementName(index: Int): String = index.toString()
    override fun getElementIndex(name: String): Int = name.toIntOrNull() ?: -3
    override fun getElementDescriptor(index: Int): SerialDescriptor = if (map && index % 2 == 0) String.serializer().descriptor else AbilityValue.serializer().descriptor
    override fun getElementAnnotations(index: Int): List<Annotation> = emptyList()
    override fun isElementOptional(index: Int): Boolean = false
}
