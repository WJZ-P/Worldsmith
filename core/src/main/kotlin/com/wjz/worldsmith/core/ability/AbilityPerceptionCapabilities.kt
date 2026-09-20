package com.wjz.worldsmith.core.ability

/** Perception and ownership primitives. Social, foraging and danger policies remain ordinary authored source. */
internal object AbilityPerceptionCapabilities {
    fun extend(base: AbilityCapabilityRegistry): AbilityCapabilityRegistry {
        var registry = base
        val n = AbilityType.NUMBER; val b = AbilityType.BOOL; val e = AbilityType.ENTITY
        val v = AbilityType.VECTOR; val a = AbilityType.ANY
        fun native(name: String, args: List<AbilityType>, result: AbilityType, doc: String, effect: Boolean = false) {
            registry = registry.extend(AbilityCapabilitySpec(name, arguments = args, result = result, effect = effect, description = doc))
        }
        native("control.claim", listOf(n,n), n,
            "control.claim(priority,ticks): self custom-creature MOVE+LOOK lease; priority integer0..100, ticks1..1200. Returns owned handle or0 when unavailable. Equal-priority first owner wins; higher priority preempts; same invocation replaces its old token. Shares8/instance and128/level resource limits. Ordinary priorities<80 yield to conversation; emergency>=80 closes the conversation before movement. Expired/preempted tokens never resume old paths.", true)
        native("control.held", listOf(n), b,
            "control.held(handle): true only while this invocation owns that live self-creature control token. Zero, expired, preempted and another invocation's numeric handle are false. Losing control does not terminate source or revoke independent effect/visual leases.")
        native("control.release", listOf(n), b,
            "control.release(handle): releases only this invocation's control lease; false for zero/absent/wrong-kind. Stops only its still-owned native Path and lets ordinary AI resume. This does not cancel the source or any newer owner's path.", true)
        native("entity.identity", listOf(e), a,
            "entity.identity(entity): resolved scoped entity metadata map {kind,logicalId,nativeId,bundleScope}, or null. kind is player/creature/living/other; logicalId is a local custom creature definition id and bundleScope its immutable world hash, otherwise both empty. Native registry id is always included. Does not grant control or reveal private player facts.")
        native("entity.environment", listOf(e), a,
            "entity.environment(entity): resolved scoped real map {dayTime,day,light,skyVisible,raining,inWater,onFire}, or null. dayTime is the overworld clock modulo24000; day is bounded nonnegative clock days; light0..15 at feet. raining means precipitation reaches those feet, not global weather alone. No chunk loading or native-goal ownership.")
        native("entity.home", listOf(e), a,
            "entity.home(entity): actual stored home feet VECTOR of a resolved custom creature, or null. It may lie beyond this invocation's action radius; returning a home is not permission to navigate/teleport there or load its chunk.")
        native("motion.status", listOf(e), a,
            "motion.status(self): current self-custom-creature native path map {navigating,done,pathReachable,target,distance,controlled}, or null. done is native navigation completion, not proof of arrival; pathReachable describes the planned path. target is a block-center vector or null and distance is current Euclidean distance or0. controlled means THIS invocation owns MOVE+LOOK. Pure observers do not acquire control.")
        native("world.sample", listOf(v), a,
            "world.sample(position): loaded scoped map {blockId,fluidId,collision,light,skyVisible,raining}, otherwise null. Position <=24 from invocation origin, inside world bounds/border, with neighboring collision reads loaded. blockId uses current logical alias when bound; collision means nonempty native collision shape, not guaranteed walkability. No block write, neighbor request or chunk load.")
        return registry
    }
}
