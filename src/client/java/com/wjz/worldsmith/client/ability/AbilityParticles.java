package com.wjz.worldsmith.client.ability;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.ability.AbilityFxEntity;
import com.wjz.worldsmith.ability.AbilityVisualData;
import com.wjz.worldsmith.ability.AbilityVisualRuntime;
import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteSet;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Native textured particles whose position/colour/lifetime are data and whose cleanup follows the owning visual. */
final class AbilityParticles implements AbilityFxEntity.ClientHooks {
    private static final int MAX_PARTICLES = 2048;
    private static final Map<UUID, List<Particle>> OWNED = new HashMap<>();
    private static final Map<String, TextureAtlasSprite> TEXTURES = new HashMap<>();
    private static final java.util.Set<UUID> DIAGNOSED = new java.util.HashSet<>();
    private static FabricSpriteSet sprites;
    private static List<TextureAtlasSprite> previousSprites = List.of();

    static void initialize() {
        ParticleProviderRegistry.getInstance().register(AbilityVisualRuntime.particleType(), spriteSet -> {
            sprites = spriteSet;
            return (options, level, x, y, z, dx, dy, dz, random) -> spriteSet.getSprites().isEmpty() ? null
                : new VisualParticle(level, spriteSet.first(), new Vec3(x, y, z), new Vec3(dx, dy, dz));
        });
        AbilityFxEntity.clientHooks(new AbilityParticles());
    }
    @Override public void tick(AbilityFxEntity entity) {
        var client = Minecraft.getInstance(); var data = entity.visual();
        if (client.level != entity.level() || data.kind() != AbilityVisualData.Kind.PARTICLES
            || !data.scope().equals(WorldContentClientRuntime.activeScope()) || data.expiresAt() <= entity.level().getGameTime()) { removed(entity); return; }
        refreshSprites();
        if (OWNED.containsKey(entity.getUUID())) return;
        var sprite = TEXTURES.get(data.texture());
        if (sprite == null) {
            if (DIAGNOSED.add(entity.getUUID())) Worldsmith.LOGGER.error("Ability particle texture was not published to the active atlas: {}", data.texture());
            return;
        }
        var particles = new ArrayList<Particle>(); OWNED.put(entity.getUUID(), particles);
        var density = client.options.particles().get();
        if (density == ParticleStatus.MINIMAL) return;
        int count = Math.min(data.count(), MAX_PARTICLES - activeCount());
        if (density == ParticleStatus.DECREASED && count > 0) count = Math.max(1, count / 2);
        if (com.wjz.worldsmith.config.WorldsmithConfig.get().getClient().getReducedEffects() && count > 0) count = Math.max(1, count / 4);
        var random = RandomSource.create(entity.getUUID().getMostSignificantBits() ^ entity.getUUID().getLeastSignificantBits());
        for (int i = 0; i < count; i++) {
            Vec3 offset = new Vec3((random.nextDouble() - .5) * .25, (random.nextDouble() - .5) * .25, (random.nextDouble() - .5) * .25);
            var particle = new VisualParticle((ClientLevel) entity.level(), sprite, entity, data, offset);
            particles.add(particle); client.particleEngine.add(particle);
        }
    }
    @Override public void removed(AbilityFxEntity entity) {
        var particles = OWNED.remove(entity.getUUID()); if (particles != null) particles.forEach(Particle::remove);
        DIAGNOSED.remove(entity.getUUID());
    }
    static void prune(Minecraft client) {
        refreshSprites();
        OWNED.values().forEach(particles -> particles.removeIf(particle -> !particle.isAlive()));
        // Entity removal handles empty emission records; keeping one until removal prevents re-emission.
    }
    static int activeCount() { return OWNED.values().stream().mapToInt(particles -> (int) particles.stream().filter(Particle::isAlive).count()).sum(); }
    static void clear() { OWNED.values().forEach(particles -> particles.forEach(Particle::remove)); OWNED.clear(); DIAGNOSED.clear(); }
    private static void refreshSprites() {
        if (sprites == null) return;
        var current = sprites.getSprites();
        boolean changed = current.size() != previousSprites.size();
        if (!changed) for (int i = 0; i < current.size(); i++) if (current.get(i) != previousSprites.get(i)) { changed = true; break; }
        if (!changed) return;
        clear(); previousSprites = List.copyOf(current); TEXTURES.clear();
        for (var sprite : current) {
            Identifier name = sprite.contents().name();
            if (name.getNamespace().equals("worldsmith") && name.getPath().startsWith("ability/")) {
                String hash = name.getPath().substring("ability/".length());
                if (hash.matches("[0-9a-f]{64}")) TEXTURES.put(hash, sprite);
            }
        }
    }

    private static final class VisualParticle extends SingleQuadParticle {
        private final AbilityFxEntity emitter;
        private final AbilityVisualData data;
        private Vec3 local;
        private final Vec3 velocity;
        private final float baseSize;
        VisualParticle(ClientLevel level, TextureAtlasSprite sprite, Vec3 position, Vec3 velocity) {
            super(level, position.x, position.y, position.z, sprite);
            emitter = null; data = null; local = position; this.velocity = velocity;
            hasPhysics = false; lifetime = 20; baseSize = .15F; quadSize = baseSize;
        }
        VisualParticle(ClientLevel level, TextureAtlasSprite sprite, AbilityFxEntity emitter, AbilityVisualData data, Vec3 offset) {
            super(level, emitter.getX(), emitter.getY(), emitter.getZ(), sprite);
            this.emitter = emitter; this.data = data; local = offset; velocity = data.velocity();
            hasPhysics = false; lifetime = (int)Math.max(1, Math.min(200, data.expiresAt() - level.getGameTime()));
            baseSize = data.size() * .18F; quadSize = baseSize;
            setColor(((data.rgb() >> 16) & 255) / 255F, ((data.rgb() >> 8) & 255) / 255F, (data.rgb() & 255) / 255F);
            updatePosition(); xo = x; yo = y; zo = z;
        }
        @Override public void tick() {
            xo = x; yo = y; zo = z;
            if (++age >= lifetime || emitter != null && (emitter.isRemoved() || data.expiresAt() <= level.getGameTime()
                || !data.scope().equals(WorldContentClientRuntime.activeScope()))) { remove(); return; }
            local = local.add(velocity); updatePosition();
            alpha = Math.min(1, Math.max(0, (lifetime - age) / 4F));
            if (!level.hasChunkAt(BlockPos.containing(x, y, z)) || data != null && data.castOrigin().distanceToSqr(x, y, z) > 32 * 32) remove();
        }
        private void updatePosition() {
            if (emitter == null) { setPos(local.x, local.y, local.z); return; }
            var render = emitter.renderState();
            var position = new Vector3f((float)local.x, (float)local.y, (float)local.z);
            if (render != null) {
                var transform = render.transformation().get(emitter.calculateInterpolationProgress(1));
                transform.getMatrix().transformPosition(position);
                var scale = transform.scale(); quadSize = baseSize * Math.max(scale.x(), Math.max(scale.y(), scale.z()));
            }
            setPos(emitter.getX() + position.x, emitter.getY() + position.y, emitter.getZ() + position.z);
        }
        @Override protected Layer getLayer() { return Layer.TRANSLUCENT; }
    }
}
