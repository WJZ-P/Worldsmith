package com.wjz.worldsmith.content.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.core.content.CustomItemValidation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Immutable world-content identity. Identical logical names in different bundles remain different items. */
public record WorldItemIdentity(String bundleHash, String itemId) {
    private static final Codec<String> HASH = Codec.STRING.validate(value -> value.matches("[0-9a-f]{64}")
        ? DataResult.success(value) : DataResult.error(() -> "Expected a lowercase SHA-256 world bundle id"));
    private static final Codec<String> ID = Codec.STRING.validate(value -> CustomItemValidation.validId(value)
        ? DataResult.success(value) : DataResult.error(() -> "Expected a local item id of 1 to 64 characters"));
    public static final Codec<WorldItemIdentity> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        HASH.fieldOf("bundle").forGetter(WorldItemIdentity::bundleHash),
        ID.fieldOf("item").forGetter(WorldItemIdentity::itemId)
    ).apply(instance, WorldItemIdentity::new));
    public static final StreamCodec<ByteBuf, WorldItemIdentity> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(64), WorldItemIdentity::bundleHash,
        ByteBufCodecs.stringUtf8(64), WorldItemIdentity::itemId,
        WorldItemIdentity::new);

    public WorldItemIdentity {
        if (bundleHash == null || !bundleHash.matches("[0-9a-f]{64}")
            || itemId == null || !CustomItemValidation.validId(itemId))
            throw new IllegalArgumentException("Invalid world item identity");
    }

    public String logicalId() { return CustomItemRuntime.LOGICAL_PREFIX + itemId; }
}
