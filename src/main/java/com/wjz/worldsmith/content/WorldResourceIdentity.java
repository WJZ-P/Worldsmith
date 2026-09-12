package com.wjz.worldsmith.content;

import java.util.Arrays;

/** Reuse is based on both authored bytes and the sentinel actually loaded by Minecraft. */
public final class WorldResourceIdentity {
    private WorldResourceIdentity() {}

    public static boolean matchesLoaded(GeneratedWorldResourcePack current, GeneratedWorldResourcePack target,
                                        boolean selected, byte[] loadedSentinel) {
        return current != null && target != null && selected && loadedSentinel != null
            && current.scope().equals(target.scope()) && current.contentHash().equals(target.contentHash())
            && Arrays.equals(target.sentinelBytes(), loadedSentinel);
    }
}
