package com.wjz.worldsmith.core.ability

/** Open engine primitives, not a catalogue of named attacks. Native implementations enforce world bounds. */
internal object AbilityGameplayCapabilities {
    fun extend(base: AbilityCapabilityRegistry): AbilityCapabilityRegistry {
        var registry = base
        val a = AbilityType.ANY; val n = AbilityType.NUMBER; val t = AbilityType.TEXT
        val b = AbilityType.BOOL; val e = AbilityType.ENTITY; val v = AbilityType.VECTOR; val l = AbilityType.LIST
        fun pure(name: String, args: List<AbilityType>, result: AbilityType, doc: String, fn: (List<AbilityValue>) -> AbilityValue) {
            registry = registry.extend(AbilityCapabilitySpec(name, arguments = args, result = result, description = doc), AbilityPureFunction(fn))
        }
        fun native(name: String, args: List<AbilityType>, result: AbilityType, doc: String, effect: Boolean = true) {
            registry = registry.extend(AbilityCapabilitySpec(name, arguments = args, result = result, effect = effect, description = doc))
        }
        fun map(value: AbilityValue) = (value as? AbilityValue.MapValue)?.values ?: error("Expected a map value.")
        fun key(value: AbilityValue) = (value as AbilityValue.TextValue).value
        pure("map.of", listOf(l), a, "map.of(pairs): immutable map from [[key,value],...]; duplicate keys rejected; standard depth/size/value limits apply.") { args ->
            val result = linkedMapOf<String, AbilityValue>()
            (args[0] as AbilityValue.ListValue).values.forEach { pair ->
                val entries = (pair as? AbilityValue.ListValue)?.values ?: error("Map entries must be pairs.")
                require(entries.size == 2 && entries[0] is AbilityValue.TextValue) { "Map entries must be [text,value]." }
                require(result.put(key(entries[0]), entries[1]) == null) { "Duplicate map key." }
            }
            AbilityValues.map(result)
        }
        pure("map.get", listOf(a,t), a, "map.get(map,key): value or null for an absent key; errors for non-map input.") { map(it[0])[key(it[1])] ?: AbilityValues.none() }
        pure("map.put", listOf(a,t,a), a, "map.put(map,key,value): returns a validated new immutable map; input is unchanged.") { AbilityValues.map(map(it[0]) + (key(it[1]) to it[2])) }
        pure("map.has", listOf(a,t), b, "map.has(map,key): true even if the present value is null.") { AbilityValues.bool(map(it[0]).containsKey(key(it[1]))) }
        pure("map.keys", listOf(a), l, "map.keys(map): deterministic insertion-order text keys.") { AbilityValues.list(map(it[0]).keys.map(AbilityValues::text)) }
        native("world.set_block", listOf(v,t,a,n), n, "world.set_block(position,blockId,properties,ticks): bounded loaded edit, no block entities/protected blocks; native properties are text; logical HORIZONTAL blocks allow only cardinal facing, fixed logical materials allow none. Light/profile/orientation mode are immutable definition data. ticks0 permanent (16 writes/invocation),1..1200 temporary; returns owned restore handle (0 permanent). Temporary restoration is compare-and-set and preserves differing player edits.")
        native("world.restore", listOf(n), b, "world.restore(handle): restore only this invocation's temporary block lease; false for absent handle, never overwrites a differing newer block state.")
        native("entity.spawn", listOf(t,v,n), e, "entity.spawn(creatureId,position,ticks): owned custom creature, 1..1200 ticks, native host/dimensions/collision validation, shared resource budget. Inherits scene; cleanup discards without death drops. Saved/unloaded orphans retire when loaded; no program continuation reload.")
        native("entity.despawn", listOf(e), b, "entity.despawn(entity): discards only an entity owned by this invocation; false otherwise.")
        native("inventory.give", listOf(e,t,n), b, "inventory.give(player,itemReference,count): scoped player, 1..64 items; full inventory preflight, no dropped remainder; protected other players rejected.")
        native("inventory.count", listOf(t), n, "inventory.count(itemReference): self player inventory count using full logical identity; non-player returns zero.", false)
        native("inventory.take", listOf(t,n), b, "inventory.take(itemReference,count): self player only, 1..64; atomic preflight, no partial failed take.")
        native("program.start", listOf(t,a), a, "program.start(programId,args): queues child on self with same target/origin/scene; returns invocation UUID text or null. Depth<=4, normal actor/world budgets and cooldowns apply; no synchronous execution. Parent waits for children; cancellation cascades.")
        native("program.cancel", listOf(t), b, "program.cancel(invocationUuid): only owned descendants (not unrelated same-actor instances); false for invalid or unavailable UUID.")
        native("program.self", emptyList(), t, "program.self(): invocation UUID, not a persistent resume token.", false)
        native("signal.send", listOf(t,t,a), b, "signal.send(invocationUuid,tag,data): bounded queued signal to a related invocation or same named scene within 32 blocks, same dimension and pack. Never reentrant execution.")
        native("scene.join", listOf(t,v), b, "scene.join(name,anchor): name 1..64 local-id chars, loaded anchor <=24 from origin. Group identity is name plus exact block anchor within this dimension/pack. Children and owned summons inherit membership.")
        native("scene.anchor", emptyList(), v, "scene.anchor(): current encounter anchor.", false)
        native("scene.emit", listOf(t,a), n, "scene.emit(tag,data): queued signal for live same-scene invocations within 32 blocks; returns accepted recipient count.")
        native("shared.actor_get", listOf(t), a, "shared.actor_get(key): persistent actor-shared value or null; distinct from program-local state.", false)
        native("shared.actor_set", listOf(t,a), b, "shared.actor_set(key,value): immutable bounded actor state, 16 KiB aggregate and 64 keys; null deletes. Values containing transient ENTITY handles are rejected.")
        native("shared.scene_get", listOf(t), a, "shared.scene_get(key): persistent encounter-shared value or null, pack/dimension/scene scoped.", false)
        native("shared.scene_set", listOf(t,a), b, "shared.scene_set(key,value): persistent scene state; null deletes. 16 KiB/scene, 2048 scenes and 2 MiB/dimension, 64 keys/scene; no transient handles.")
        native("resource.define", listOf(t,n,n,n), b, "resource.define(id,max,initial,regenPerTick): self persistent pool, <=32 pools, max 0..1e6, initial 0..max, regen 0..1000. Existing equal max/regen definition is idempotent and does not reset balance; incompatible definition errors.")
        native("resource.get", listOf(t), n, "resource.get(id): self balance after lazy world-time regeneration, capped at maximum; unknown pool errors.", false)
        native("resource.give", listOf(t,n), n, "resource.give(id,amount): nonnegative finite amount <=1e6, returns actual credited amount after clamping.")
        native("resource.consume", listOf(t,n), b, "resource.consume(id,amount): self pool; nonnegative <=1e6, false leaves all balances unchanged if insufficient.")
        native("resource.pay", listOf(a), b, "resource.pay(costsMap): 1..32 id->nonnegative cost entries, atomic all-pools preflight; unknown pools error, insufficient balance returns false without any charge.")
        native("effect.apply", listOf(e,t,n,n,a), n, "effect.apply(target,tag,stacks,ticks,modifiers): owned lease, stacks1..16/ticks1..1200. Keys movement_speed,attack_damage,armor,knockback_resistance are bounded additive native modifiers; incoming/outgoing are multipliers. Total stack count and native ranges enforced. Other targets obey PvP/allied/creative protection.")
        native("effect.stacks", listOf(e,t), n, "effect.stacks(target,tag): sum of live owned tagged effect stacks in this dimension; expiry based on world time.", false)
        native("effect.remove", listOf(n), b, "effect.remove(handle): releases only this invocation's effect lease.")
        native("combat.guard", listOf(a,n), n, "combat.guard(rule,ticks): self owned damage guard before health loss, ticks1..1200. Optional multiplier0..1, absorb0..100, pool text (one resource per absorbed damage), sourceTag registry damage-type tag, frontDot -1..1, maxHits1..128, tag text. Queues damage_guarded with actual absorbed amount; no synchronous script waiting.")
        native("entity.velocity", listOf(e), a, "entity.velocity(entity): velocity vector or null for unresolved/out-of-scope entity.", false)
        native("entity.forward", listOf(e), a, "entity.forward(entity): normalized look vector or null for unresolved/out-of-scope entity.", false)
        native("world.raycast", listOf(v,v), a, "world.raycast(start,end): loaded segment <=32 blocks, scoped points; map kind/position/normal/normalExact/entity/blockId; block normal exact, entity normal approximate; nearest native collision.", false)
        native("fx.particles", listOf(t,v,v,n,n,n,n), e, "fx.particles(textureAsset,position,velocity,count,ticks,scale,rgb): owned bounded custom-texture emitter; validated pack PNG, no external resource URL; shared 8/128 resource limits.")
        native("fx.path", listOf(l,n,n,n), e, "fx.path(points,width,rgb,ticks): owned interpolated polyline visual; bounded loaded vectors, no damage.")
        native("fx.item", listOf(t,v,v,v,n), e, "fx.item(itemId,position,rotationDegrees,scale,ticks): owned native item display, no inventory cost or pickup.")
        native("fx.transform", listOf(e,v,v,v,n), b, "fx.transform(entity,position,rotationDegrees,scale,interpolationTicks): only this invocation's owned display/effect; false for other actors.")
        native("fx.remove", listOf(e), b, "fx.remove(entity): retires only this invocation's owned visual.")
        native("animation.play", listOf(l,n,n), n, "animation.play(tracks,ticks,blendTicks): leased creature bone deltas after base pose; tracks [[bone,[[tick,translation,rotationDegrees,scale],...]],...]. Validated finite bounded clip; latest live clip wins with fallback, synced tracking clients.")
        native("animation.stop", listOf(n), b, "animation.stop(handle): releases this invocation's clip lease only.")
        native("projectile.velocity", listOf(e,v), b, "projectile.velocity(projectile,velocity): sets owned projectile velocity, magnitude .01..2; never changes another invocation's object.")
        native("projectile.steer", listOf(e,v,n), b, "projectile.steer(projectile,desiredVelocity,maxTurnRadians): bounded owned steering per call; native physics/collision still authoritative.")
        native("projectile.retire", listOf(e), b, "projectile.retire(projectile): retires only an owned projectile; late callbacks ignored.")
        native("story.get", listOf(t,t), a, "story.get(factId, subject): reads a declared primitive fact in this immutable story world. Empty subject uses current character/discovered place; WORLD and PLAYER omit subject. Unknown, ambiguous or unavailable context is an error; no access to a different player's private facts.", false)
        native("story.set", listOf(t,t,a), b, "story.set(factId, subject, value): commits one declared typed fact through the story authority and queues dialogue/quest/knowledge refresh. Subject rules match story.get; type/range/capacity checked before change. Returns true after commit; not a second private script-state copy.")
        native("story.add", listOf(t,t,n), b, "story.add(factId, subject, amount): atomically adds a finite number to a declared numeric fact, respecting its bounds. Empty subject resolves current context; no client-supplied player UUID. Returns true after commit.")
        return registry
    }
}
