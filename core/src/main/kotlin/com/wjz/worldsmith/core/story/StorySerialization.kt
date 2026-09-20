package com.wjz.worldsmith.core.story

import com.wjz.worldsmith.core.ability.AbilityValue
import com.wjz.worldsmith.core.ability.AbilityValues
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Primitive story facts never deserialize arbitrary nested ability maps/entities first. */
object StoryPrimitiveSerializer : KSerializer<AbilityValue> {
    override val descriptor=buildClassSerialDescriptor("worldsmith.story.Primitive")
    override fun deserialize(decoder: Decoder): AbilityValue = decode((decoder as? JsonDecoder ?: throw SerializationException("Story values require JSON")).decodeJsonElement())
    override fun serialize(encoder: Encoder,value: AbilityValue) = (encoder as? JsonEncoder ?: throw SerializationException("Story values require JSON")).encodeJsonElement(encode(value))
    internal fun decode(raw: JsonElement): AbilityValue {
        val value=raw as? JsonObject ?: throw SerializationException("Story primitive must be a typed JSON object")
        if(value.keys!=setOf("kind","value"))throw SerializationException("Story primitive uses exactly kind and value")
        val primitive=value["value"] as? JsonPrimitive ?: throw SerializationException("Story values must be primitive")
        return when(value["kind"]?.jsonPrimitive?.content) {
            "bool" -> if(!primitive.isString) AbilityValues.bool(primitive.booleanOrNull ?: throw SerializationException("Expected boolean fact")) else throw SerializationException("Expected boolean fact")
            "number" -> if(!primitive.isString) AbilityValues.number(primitive.doubleOrNull ?: throw SerializationException("Expected numeric fact")) else throw SerializationException("Expected numeric fact")
            "text" -> if(primitive.isString) AbilityValues.text(primitive.content) else throw SerializationException("Expected text fact")
            else -> throw SerializationException("Only bool, number and text story values are supported")
        }
    }
    internal fun encode(value: AbilityValue): JsonObject = buildJsonObject { when(value) {
        is AbilityValue.BoolValue -> {put("kind","bool");put("value",value.value)}
        is AbilityValue.NumberValue -> {put("kind","number");put("value",value.value)}
        is AbilityValue.TextValue -> {put("kind","text");put("value",value.value)}
        else -> throw SerializationException("Story values must be primitive")
    } }
}

/** Enforce depth/node limits BEFORE recursive typed construction, not only after an attacker-controlled decode. */
object StoryConditionSerializer : KSerializer<StoryCondition> {
    override val descriptor=buildClassSerialDescriptor("worldsmith.story.Condition")
    override fun deserialize(decoder: Decoder): StoryCondition {
        val json=decoder as? JsonDecoder ?: throw SerializationException("Story conditions require JSON")
        val raw=json.decodeJsonElement();var nodes=0
        fun read(value: JsonElement,depth: Int): StoryCondition {
            if(depth>StoryConditions.MAX_DEPTH||++nodes>StoryConditions.MAX_NODES)throw SerializationException("Story condition exceeds 64 nodes or depth 8")
            val obj=value as? JsonObject ?: throw SerializationException("Condition must be an object")
            val kind=(obj["kind"] as? JsonPrimitive)?.contentOrNull ?: throw SerializationException("Condition kind is required")
            val allowed=when(kind){"always"->setOf("kind");"all","any"->setOf("kind","conditions");"not"->setOf("kind","condition");"compare"->setOf("kind","fact","comparison","value");else->throw SerializationException("Unknown condition kind")}
            if(obj.keys!=allowed)throw SerializationException("Condition fields must match its declared kind")
            fun children(): List<StoryCondition> {
                val list=obj["conditions"] as? JsonArray ?: throw SerializationException("Expected condition list")
                if(list.size>StoryConditions.MAX_NODES)throw SerializationException("Too many condition children")
                return list.map { read(it,depth+1) }
            }
            return when(kind) {
                "always"->StoryCondition.Always
                "all"->StoryCondition.All(children())
                "any"->StoryCondition.Any(children())
                "not"->StoryCondition.Not(read(obj.getValue("condition"),depth+1))
                else->StoryCondition.Compare(json.json.decodeFromJsonElement(StoryFactRef.serializer(),obj.getValue("fact")),
                    json.json.decodeFromJsonElement(StoryComparison.serializer(),obj.getValue("comparison")),StoryPrimitiveSerializer.decode(obj.getValue("value")))
            }
        }
        return read(raw,0)
    }
    override fun serialize(encoder: Encoder,value: StoryCondition) {
        if(StoryConditions.validate(value).isNotEmpty())throw SerializationException("Invalid story condition")
        val json=encoder as? JsonEncoder ?: throw SerializationException("Story conditions require JSON")
        fun write(c: StoryCondition): JsonObject=buildJsonObject { when(c) {
            StoryCondition.Always->{put("kind","always")}
            is StoryCondition.All->{put("kind","all");put("conditions",JsonArray(c.conditions.map(::write)))}
            is StoryCondition.Any->{put("kind","any");put("conditions",JsonArray(c.conditions.map(::write)))}
            is StoryCondition.Not->{put("kind","not");put("condition",write(c.condition))}
            is StoryCondition.Compare->{put("kind","compare");put("fact",json.json.encodeToJsonElement(StoryFactRef.serializer(),c.fact));put("comparison",json.json.encodeToJsonElement(StoryComparison.serializer(),c.comparison));put("value",StoryPrimitiveSerializer.encode(c.value))}
        } }
        json.encodeJsonElement(write(value))
    }
}
