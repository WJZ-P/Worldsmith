package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.story.StoryCharacters;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;

/** Explicit invocation-owned MOVE+LOOK leases. Merely observing an actor never controls it. */
public final class AbilityActorControl {
    public static final int EMERGENCY_PRIORITY = 80;
    private static final Map<ServerLevel, Claims<CreatureEntity, Path>> LEVELS = new IdentityHashMap<>();
    private AbilityActorControl() {}

    public static boolean canClaim(CreatureEntity actor, UUID invocation, int priority) {
        checkPriority(priority); Objects.requireNonNull(invocation);
        if (!available(actor) || !StoryCharacters.allowControl(actor, priority)) return false;
        var level = (ServerLevel)actor.level();
        var claims = LEVELS.get(level);
        return claims == null || claims.canClaim(actor, invocation, priority, level.getGameTime());
    }

    /** The caller first reserves a normal Context lease; onRetired returns that same lease's budget. */
    public static boolean claim(CreatureEntity actor, UUID invocation, int handle, int priority, long until, Runnable onRetired) {
        checkPriority(priority); Objects.requireNonNull(invocation); Objects.requireNonNull(onRetired);
        if (handle <= 0) throw new IllegalArgumentException("Control handles must be positive");
        if (!canClaim(actor, invocation, priority)) return false;
        var level = (ServerLevel)actor.level(); long now = level.getGameTime();
        if (until <= now || until - now > 1200) throw new IllegalArgumentException("Control leases last 1..1200 ticks");
        var claims = LEVELS.computeIfAbsent(level, ignored -> new Claims<>());
        boolean accepted = claims.claim(actor, invocation, handle, priority, now, until, path -> {
            if (actor.getNavigation().getPath() == path) actor.getNavigation().stop();
        }, onRetired);
        if (!accepted) return false;
        try {
            StoryCharacters.interruptForControl(actor, priority);
            actor.yieldToAbilityEvents();
            return true;
        } catch (RuntimeException failure) {
            claims.release(actor, invocation, handle);
            throw failure;
        }
    }

    public static boolean held(CreatureEntity actor, UUID invocation, int handle) {
        var claim = current(actor);
        return claim != null && claim.matches(invocation, handle);
    }

    public static boolean owns(CreatureEntity actor, UUID invocation) {
        var claim = current(actor);
        return claim != null && claim.invocation.equals(invocation);
    }

    public static boolean hasControl(CreatureEntity actor) { return current(actor) != null; }

    /** A player can interrupt ordinary work, but a live emergency finishes before a new conversation opens. */
    public static boolean yieldToConversation(CreatureEntity actor) {
        var claim = current(actor);
        if (claim == null) return true;
        if (claim.priority >= EMERGENCY_PRIORITY) return false;
        return release(actor, claim.invocation, claim.handle);
    }

    public static boolean release(CreatureEntity actor, UUID invocation, int handle) {
        if (!(actor.level() instanceof ServerLevel level)) return false;
        var claims = LEVELS.get(level);
        return claims != null && claims.release(actor, invocation, handle);
    }

    /** Call only after native navigation accepted this exact Path; null clears this token's path. */
    public static boolean acceptPath(CreatureEntity actor, UUID invocation, Path path) {
        var claim = current(actor);
        if (claim == null || !claim.invocation.equals(invocation)) return false;
        claim.path = path;
        return true;
    }

    public static void revokeActor(CreatureEntity actor) {
        // Also covers a direct dimension transition whose actor object has already changed levels.
        for (var claims : List.copyOf(LEVELS.values())) claims.revoke(actor);
    }

    public static void clearLevel(ServerLevel level) {
        var claims = LEVELS.remove(level);
        if (claims != null) claims.clear();
    }

    private static Claim<Path> current(CreatureEntity actor) {
        if (!available(actor)) { revokeActor(actor); return null; }
        var level = (ServerLevel)actor.level();
        var claims = LEVELS.get(level);
        return claims == null ? null : claims.current(actor, level.getGameTime());
    }

    private static boolean available(CreatureEntity actor) {
        return actor != null && actor.level() instanceof ServerLevel && actor.isAlive() && !actor.isRemoved()
            && !actor.isNoAi() && actor.isWithinHome(actor.blockPosition());
    }

    private static void checkPriority(int priority) {
        if (priority < 0 || priority > 100) throw new IllegalArgumentException("Control priority must be 0..100");
    }

    static final class Claim<P> {
        final UUID invocation;
        final int handle, priority;
        final long until;
        final Consumer<P> stopPath;
        final Runnable onRetired;
        P path;
        Claim(UUID invocation, int handle, int priority, long until, Consumer<P> stopPath, Runnable onRetired) {
            this.invocation = invocation; this.handle = handle; this.priority = priority; this.until = until;
            this.stopPath = stopPath; this.onRetired = onRetired;
        }
        boolean matches(UUID invocation, int handle) { return this.invocation.equals(invocation) && this.handle == handle; }
    }

    /** Small deterministic arbitration kernel; native paths and world lifecycle are supplied by the adapter. */
    static final class Claims<K, P> {
        final Map<K, Claim<P>> owners = new IdentityHashMap<>();
        Claim<P> current(K actor, long now) {
            var claim = owners.get(actor);
            if (claim != null && now >= claim.until) { retire(actor, claim); return owners.get(actor); }
            return claim;
        }
        boolean canClaim(K actor, UUID invocation, int priority, long now) {
            var previous = current(actor, now);
            return previous == null || previous.invocation.equals(invocation) || priority > previous.priority;
        }
        boolean claim(K actor, UUID invocation, int handle, int priority, long now, long until, Consumer<P> stopPath, Runnable onRetired) {
            if (!canClaim(actor, invocation, priority, now)) return false;
            var previous = owners.get(actor);
            if (previous != null) retire(actor, previous);
            // A retirement callback can reenter. A newer published owner must not be overwritten.
            if (owners.containsKey(actor)) return false;
            owners.put(actor, new Claim<>(invocation, handle, priority, until, stopPath, onRetired));
            return true;
        }
        boolean release(K actor, UUID invocation, int handle) {
            var claim = owners.get(actor);
            return claim != null && claim.matches(invocation, handle) && retire(actor, claim);
        }
        void revoke(K actor) { var claim = owners.get(actor); if (claim != null) retire(actor, claim); }
        void clear() { for (K actor : List.copyOf(owners.keySet())) revoke(actor); }
        private boolean retire(K actor, Claim<P> claim) {
            if (owners.get(actor) != claim) return false;
            owners.remove(actor); // Root's releaseLease callback may call release again.
            try { if (claim.path != null) claim.stopPath.accept(claim.path); }
            finally { claim.onRetired.run(); }
            return true;
        }
    }
}
