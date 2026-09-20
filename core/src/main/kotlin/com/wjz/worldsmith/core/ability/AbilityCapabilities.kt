package com.wjz.worldsmith.core.ability

import kotlinx.serialization.Serializable
import java.util.Collections
import kotlin.math.*

@Serializable
enum class AbilityType { ANY, NUMBER, BOOL, TEXT, VECTOR, ENTITY, LIST, REGION }

@Serializable
data class AbilityCapabilitySpec @JvmOverloads constructor(
    val name: String,
    val version: Int = 1,
    val arguments: List<AbilityType>,
    val result: AbilityType = AbilityType.ANY,
    val effect: Boolean = false,
    val description: String = "",
)

/** An extension is trusted host code; packs only name a registered, versioned signature. */
fun interface AbilityPureFunction { fun call(arguments: List<AbilityValue>): AbilityValue }

class AbilityCapabilityRegistry internal constructor(
    specifications: List<AbilityCapabilitySpec>,
    private val pure: Map<String, AbilityPureFunction> = emptyMap(),
) {
    val specs: List<AbilityCapabilitySpec> = Collections.unmodifiableList(specifications.map { spec ->
        require(spec.name.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*")) && spec.name.length <= 128) { "Invalid capability name." }
        require(spec.version >= 1 && spec.arguments.size <= 16 && spec.description.length <= 2048) { "Invalid capability signature '${spec.name}'." }
        spec.copy(arguments = Collections.unmodifiableList(ArrayList(spec.arguments)))
    })
    private val byName = specs.associateBy { it.name }.also { require(it.size == specs.size) { "Duplicate capability name." } }

    fun lookup(name: String): AbilityCapabilitySpec? = byName[name]
    fun isPure(name: String): Boolean = name in pure

    fun extend(spec: AbilityCapabilitySpec): AbilityCapabilityRegistry {
        require(spec.name !in byName) { "Capability '${spec.name}' is already registered." }
        return AbilityCapabilityRegistry(specs + spec, pure)
    }

    fun extend(spec: AbilityCapabilitySpec, implementation: AbilityPureFunction): AbilityCapabilityRegistry {
        require(!spec.effect) { "A pure extension must not be marked effectful." }
        require(spec.name !in byName) { "Capability '${spec.name}' is already registered." }
        return AbilityCapabilityRegistry(specs + spec, pure + (spec.name to implementation))
    }

    internal fun invoke(name: String, arguments: List<AbilityValue>, host: AbilityHost): AbilityValue {
        val spec = requireNotNull(lookup(name)) { "Unknown capability '$name'." }
        require(arguments.size == spec.arguments.size) { "Capability '$name' expects ${spec.arguments.size} arguments." }
        arguments.forEachIndexed { i, value -> checkType(value, spec.arguments[i], "Argument ${i + 1} of '$name'") }
        val immutableArguments = Collections.unmodifiableList(ArrayList(arguments))
        val value = pure[name]?.call(immutableArguments) ?: host.call(name, immutableArguments)
        AbilityValues.validate(value)
        checkType(value, spec.result, "Result of '$name'")
        return value
    }

    internal fun checkType(value: AbilityValue, type: AbilityType, label: String) {
        val valid = when (type) {
            AbilityType.ANY -> true
            AbilityType.NUMBER -> value is AbilityValue.NumberValue
            AbilityType.BOOL -> value is AbilityValue.BoolValue
            AbilityType.TEXT -> value is AbilityValue.TextValue
            AbilityType.VECTOR -> value is AbilityValue.VectorValue
            AbilityType.ENTITY -> value is AbilityValue.EntityValue
            AbilityType.LIST -> value is AbilityValue.ListValue
            AbilityType.REGION -> value is AbilityValue.MapValue
        }
        require(valid) { "$label expects $type." }
        if (type == AbilityType.REGION) AbilityRegions.validate(value)
    }
}

object AbilityCapabilities {
    private val defaults: AbilityCapabilityRegistry by lazy {
        val specs = mutableListOf<AbilityCapabilitySpec>()
        val implementations = linkedMapOf<String, AbilityPureFunction>()
        fun pure(name: String, args: List<AbilityType>, result: AbilityType, description: String, fn: (List<AbilityValue>) -> AbilityValue) {
            specs += AbilityCapabilitySpec(name, arguments = args, result = result, description = description)
            implementations[name] = AbilityPureFunction(fn)
        }
        fun native(name: String, args: List<AbilityType>, result: AbilityType, description: String, effect: Boolean = false) {
            specs += AbilityCapabilitySpec(name, arguments = args, result = result, effect = effect, description = description)
        }
        val n = AbilityType.NUMBER; val b = AbilityType.BOOL; val t = AbilityType.TEXT
        val v = AbilityType.VECTOR; val e = AbilityType.ENTITY; val l = AbilityType.LIST
        val r = AbilityType.REGION; val a = AbilityType.ANY
        fun num(x: AbilityValue) = AbilityValues.numberOf(x)
        fun vec(x: AbilityValue) = AbilityValues.vectorOf(x)
        fun number(x: Double) = AbilityValues.number(x)
        pure("vec", listOf(n, n, n), v,
            "vec(x, y, z): Returns a vector in that coordinate order. Every component must be finite with magnitude <=1e9; this constructs a value, not a world position check.") { AbilityValues.vector(num(it[0]), num(it[1]), num(it[2])) }
        pure("vector.add", listOf(v, v), v,
            "vector.add(left, right): Returns component-wise left + right without changing either vector. Finite output components must stay within +/-1e9.") { val x = vec(it[0]); val y = vec(it[1]); AbilityValues.vector(x.x + y.x, x.y + y.y, x.z + y.z) }
        pure("vector.sub", listOf(v, v), v,
            "vector.sub(left, right): Returns component-wise left - right without changing either vector; argument order matters. Finite output components must stay within +/-1e9.") { val x = vec(it[0]); val y = vec(it[1]); AbilityValues.vector(x.x - y.x, x.y - y.y, x.z - y.z) }
        pure("vector.scale", listOf(v, n), v,
            "vector.scale(vector, factor): Multiplies every component by factor and returns a new vector. Negative and zero factors are allowed; output components must stay within +/-1e9.") { val x = vec(it[0]); val s = num(it[1]); AbilityValues.vector(x.x * s, x.y * s, x.z * s) }
        pure("vector.distance", listOf(v, v), n,
            "vector.distance(from, to): Returns nonnegative 3D Euclidean distance, not squared distance. The finite numeric result must be <=1e9.") { val x = vec(it[0]); val y = vec(it[1]); number(sqrt((x.x-y.x).pow(2) + (x.y-y.y).pow(2) + (x.z-y.z).pow(2))) }
        pure("vector.x", listOf(v), n,
            "vector.x(vector): Returns its X component as a number; does not resolve an entity or query the world.") { number(vec(it[0]).x) }
        pure("vector.y", listOf(v), n,
            "vector.y(vector): Returns its Y component as a number; does not resolve an entity or query the world.") { number(vec(it[0]).y) }
        pure("vector.z", listOf(v), n,
            "vector.z(vector): Returns its Z component as a number; does not resolve an entity or query the world.") { number(vec(it[0]).z) }
        pure("math.min", listOf(n, n), n,
            "math.min(left, right): Returns the smaller of two finite numbers.") { number(min(num(it[0]), num(it[1]))) }
        pure("math.max", listOf(n, n), n,
            "math.max(left, right): Returns the larger of two finite numbers.") { number(max(num(it[0]), num(it[1]))) }
        pure("math.abs", listOf(n), n,
            "math.abs(value): Returns its nonnegative absolute value.") { number(abs(num(it[0]))) }
        pure("math.floor", listOf(n), n,
            "math.floor(value): Rounds toward negative infinity, so floor(-1.2) is -2; returns a number.") { number(floor(num(it[0]))) }
        pure("math.sin", listOf(n), n,
            "math.sin(radians): Returns sine in [-1, 1]. The angle is in radians, not degrees.") { number(sin(num(it[0]))) }
        pure("math.cos", listOf(n), n,
            "math.cos(radians): Returns cosine in [-1, 1]. The angle is in radians, not degrees.") { number(cos(num(it[0]))) }
        pure("math.clamp", listOf(n, n, n), n,
            "math.clamp(value, minimum, maximum): Returns value clamped to the inclusive interval. minimum > maximum is an error, not an automatic argument swap.") { require(num(it[1]) <= num(it[2])) { "Clamp minimum exceeds maximum." }; number(num(it[0]).coerceIn(num(it[1]), num(it[2]))) }
        pure("list.append", listOf(l, a), l,
            "list.append(values, value): Returns a new list with value at the end; the original is unchanged. Assign the result to retain it. At most 64 entries, plus the shared value depth/node budget.") { AbilityValues.list(AbilityValues.listOf(it[0]) + it[1]) }
        pure("list.get", listOf(l, n), a,
            "list.get(values, index): Uses a zero-based integer index in 0..1e9. Returns the element (ANY, possibly null), or null past the end; negative/fractional indices are errors.") { val entries = AbilityValues.listOf(it[0]); val index = AbilityValues.integerOf(it[1], 0, 1000000000); entries.getOrNull(index) ?: AbilityValues.none() }
        pure("list.size", listOf(l), n,
            "list.size(values): Returns the number of entries, including null entries, as a number in 0..64.") { number(AbilityValues.listOf(it[0]).size.toDouble()) }
        pure("shape.sphere", listOf(v, n), r,
            "shape.sphere(center, radius): Creates an inclusive 3D spherical REGION; radius is 0..32 in Core. Its cue outline is a horizontal circle. Native query/cue use additionally limits extent to 16 and center to 24 from origin.") { AbilityRegions.sphere(vec(it[0]), num(it[1])) }
        pure("shape.box", listOf(v, v), r,
            "shape.box(center, halfExtents): Creates an axis-aligned REGION; halfExtents is a vector with each component 0..32, not full dimensions. Native query/cue use limits each half extent to 16 and center to 24 from origin.") { AbilityRegions.box(vec(it[0]), vec(it[1])) }
        pure("shape.union", listOf(l), r,
            "shape.union(regions): Creates the geometric union of 1..16 REGION values, not a list of separate attacks. Combined Core bounds span at most 64 per axis; shared value-depth limits and stricter native region bounds still apply.") { AbilityRegions.union(AbilityValues.listOf(it[0])) }
        pure("shape.translate", listOf(r, v), r,
            "shape.translate(region, offset): Returns a translated REGION; offset is a vector displacement, not a replacement center. Leaves the source unchanged and preserves shape/size; translated bounds must be finite and satisfy native scope on use.") { AbilityRegions.translate(it[0], vec(it[1])) }
        native("entity.position", listOf(e), a,
            "entity.position(entity): Returns its current position VECTOR or null (ANY result) if the handle is unresolved, removed, unloaded or farther than 32 from invocation origin. A still-present non-living entity is also valid.")
        native("entity.alive", listOf(a), b,
            "entity.alive(value): Accepts ANY, including null. True only for a resolved, alive LivingEntity within 32 of origin in loaded space; false for missing handles, non-entities and projectiles.")
        native("entity.health_ratio", listOf(e), n,
            "entity.health_ratio(entity): Returns current health/maxHealth clamped to 0..1. Missing, dead, non-living, unloaded or out-of-scope handles return 0, not null.")
        native("entity.target", listOf(e), a,
            "entity.target(entity): Returns the resolved Mob's current target ENTITY handle, or null for no target, players/non-Mobs or an unresolved source. This is explicit live targeting; later calls still revalidate the returned handle.")
        native("entity.kind", listOf(e), t,
            "entity.kind(entity): Returns player, creature (custom Worldsmith host), living (other LivingEntity), or other. Unresolved/out-of-scope handles also return other; use entity.alive/position for availability.")
        native("world.entities", listOf(r), l,
            "world.entities(region): Returns at most 32 alive living handles whose positions lie in the REGION, excluding self and creative/spectator players; nearest to origin first, UUID tie-break. No implicit LOS/team filter. Native bounds: half extent <=16, center <=24 from origin, loaded and inside border.")
        native("world.visible", listOf(e, e), b,
            "world.visible(from, to): True for a clear collider ray between two resolved living entities' eyes; fluids are ignored. Missing/out-of-scope entities or an unloaded ray return false. Does not apply damage or perform a team check.")
        native("world.block", listOf(v), t,
            "world.block(position): Reads one block at the floored vector position and returns its logical worldsmith:content/<id> alias when bound, otherwise its native ID. Point must be loaded, within world bounds/border and <=24 from origin; invalid scope is an error.")
        native("world.aim", listOf(n), v,
            "world.aim(range): Returns the first block-collider hit point from self's eyes/look direction, or the endpoint if unobstructed; never an entity handle or null. range is 0.1..16, fluids ignored; an unloaded ray is an error.")
        native("combat.damage", listOf(e, n), b,
            "combat.damage(entity, amount): Attempts native damage attributed to self; amount 0..100, target resolved within 32 of origin. Returns the native hit result, false when unavailable/protected. Allies, creative/spectator and PvP protections apply; native armor/invulnerability remain. No implicit LOS.", true)
        native("combat.heal", listOf(e, n), b,
            "combat.heal(entity, amount): Heals a resolved alive target within 32 of origin by 0..100, capped by native max health. True only if health increased; false when absent/dead/already full. Healing has no extra PvP/team or LOS gate.", true)
        native("status.apply", listOf(e, t, n, n), b,
            "status.apply(entity, effectId, ticks, amplifier): Applies a registered native status; printable ID <=128 chars, integer ticks 1..1200 and amplifier 0..4. Returns native acceptance, false if unavailable/protected; unknown IDs are errors. Self works with PvP disabled; allies/other-player PvP and creative/spectator protections apply. No implicit LOS.", true)
        native("motion.stop", listOf(e), b,
            "motion.stop(entity): Self-only; custom creatures require this invocation's live control.claim token, while self players need no NPC token. Other/unresolved hosts return false. Stops creature navigation and zeroes horizontal velocity while preserving Y velocity. Only the explicit control token owns movement; queries and invocation lifetime do not. Retired token cleanup never stops a newer owner's Path.", true)
        native("motion.face", listOf(e, v), b,
            "motion.face(entity, position): Self player or self custom creature; the creature requires this invocation's live control.claim token. Turns body/head yaw toward a checked point and updates creature look control. Point must be loaded/in bounds and <=24 from origin. Returns false for another/unresolved/unsupported host; no forced rotation of other actors and no implicit token acquisition.", true)
        native("motion.navigate", listOf(e, v, n), b,
            "motion.navigate(entity, position, speed): Self custom-creature only with this invocation's live control.claim token; other hosts return false. speed is a 0.1..2 multiplier; point <=24 from origin and padded route loaded. Null/finished paths return false before moveTo. True means accepted, not arrival. The accepted Path is owned by the token; old/preempted cleanup cannot stop a newer Path, and no prior route is replayed.", true)
        native("motion.blink", listOf(n), b,
            "motion.blink(distance): Self player-only, distance 0.1..8. Returns false while mounted/sleeping or with no loaded, supported, dry, collision-free destination and swept path. Uses clipped look direction, never a through-wall fallback; success teleports and resets fall distance.", true)
        native("motion.push", listOf(e, v), b,
            "motion.push(entity, impulse): Adds a vector impulse with magnitude <=2; it does not set or cap final velocity. Resolved living target must be within 32 of origin. Self works with PvP disabled; protected other players/allies and creative/spectator targets return false. No implicit LOS.", true)
        native("fx.telegraph", listOf(r, n, t), n,
            "fx.telegraph(region, ticks, color): Returns an owned numeric cue handle; integer ticks 1..200, color amber/red/blue/white/green. Native region half extent <=16, center <=24 from origin, loaded/in border. No damage. Lease keeps idle source alive; shared cue/projectile caps 8 per instance and 128 per level; expiry/cancel clears it.", true)
        native("fx.clear", listOf(n), b,
            "fx.clear(handle): Removes this invocation's telegraph or numeric owned lease (block restore/effect/guard/animation). True if found, false otherwise; does not clear entity/projectile handles, pose or caption channels. Deferred block restoration still waits for a loaded, unobstructed compare-and-set match.", true)
        native("fx.sound", listOf(t, v, n, n), b,
            "fx.sound(soundId, position, volume, pitch): Broadcasts one registered native sound in the HOSTILE category; printable ID <=128 chars, volume 0..2, pitch 0.5..2. Point must be loaded/in bounds and <=24 from origin. Unknown IDs are errors; true means dispatched, not heard. No lifetime lease.", true)
        native("fx.message", listOf(t), b,
            "fx.message(text): Sends printable plain text <=256 chars to self when self is a ServerPlayer, otherwise to players within 16 of self. Returns true even with no recipients; no lifetime lease or markup interpretation.", true)
        native("fx.pose", listOf(t, n), b,
            "fx.pose(pose, ticks): Custom CreatureEntity self only: idle/walk/chase/windup/strike/recovery, integer ticks 1..200. Presentation only, not damage. Across invocations on self, latest unexpired write wins; expiry/cancel reveals an older live lease, and only the last pose lease ending resets idle. Lease keeps idle source alive. Returns true; other hosts ignore pose and create no pose lease.", true)
        native("fx.caption", listOf(t, n), b,
            "fx.caption(text, ticks): Replaces this invocation's printable caption <=128 chars for integer ticks 1..1200. Across invocations on self, latest unexpired write wins; expiry/cancel reveals an older live lease if any. Returns true; its lease keeps idle source alive. Displayed as a Boss-bar suffix for a custom Boss self, not a player overlay.", true)
        native("projectile.emit", listOf(v, v, n, n, t), e,
            "projectile.emit(position, velocity, gravity, ticks, tag): Returns an owned projectile ENTITY. Point loaded/in bounds and <=24 from origin; speed magnitude 0.01..2, gravity 0..0.2, integer lifetime 1..200, printable tag <=64 chars. No automatic damage; projectile_hit supplies event_entity (possibly null), event_position/event_tag. Lease and 8-instance/128-level shared resource caps apply. World-time expiry retires ownership even when physics pauses in a non-ticking chunk; late hits are ignored.", true)
        native("signal.emit", listOf(t, a), b,
            "signal.emit(tag, data): Queues on signal for this same invocation with event_tag and event_data (ANY, including null). Printable tag <=64 chars. True when queued; false for absent handler, stopped machine or full bounded queue. Not a cross-world broadcast.", true)
        AbilityPerceptionCapabilities.extend(AbilityGameplayCapabilities.extend(AbilityCapabilityRegistry(specs, implementations)))
    }

    @JvmStatic fun standard(): AbilityCapabilityRegistry = defaults
}
