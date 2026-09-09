package com.wjz.worldsmith.content.creative;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** An identity reference, never client-provided entity NBT, behavior code, or a replacement definition. */
public record CreatureSpawnToken(String bundleHash, String species) {
    private static final Codec<String> BUNDLE_CODEC = Codec.STRING.validate(value -> value.matches("[0-9a-f]{64}")
        ? DataResult.success(value) : DataResult.error(() -> "Expected an immutable lowercase SHA-256 bundle id"));
    private static final Codec<String> SPECIES_CODEC = Codec.STRING.validate(value -> validSpecies(value)
        ? DataResult.success(value) : DataResult.error(() -> "Expected a normalized local creature id of at most 96 characters"));
    public static final Codec<CreatureSpawnToken> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        BUNDLE_CODEC.fieldOf("bundle").forGetter(CreatureSpawnToken::bundleHash),
        SPECIES_CODEC.fieldOf("species").forGetter(CreatureSpawnToken::species)
    ).apply(instance, CreatureSpawnToken::new));
    public static final StreamCodec<ByteBuf, CreatureSpawnToken> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(64), CreatureSpawnToken::bundleHash,
        ByteBufCodecs.stringUtf8(96), CreatureSpawnToken::species,
        CreatureSpawnToken::new);

    public CreatureSpawnToken {
        if (bundleHash == null || !bundleHash.matches("[0-9a-f]{64}")
            || !validSpecies(species))
            throw new IllegalArgumentException("Invalid world-bound creature spawn token");
    }

    static boolean validSpecies(String id) {
        return id != null && id.matches("[a-z0-9][a-z0-9_./-]{0,95}")
            && java.util.Arrays.stream(id.split("/", -1)).noneMatch(part -> part.equals(".") || part.equals(".."));
    }
}
