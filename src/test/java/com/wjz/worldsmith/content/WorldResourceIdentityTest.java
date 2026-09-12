package com.wjz.worldsmith.content;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WorldResourceIdentityTest {
    private static GeneratedWorldResourcePack pack(String scope,int value) {
        return new GeneratedWorldResourcePack(scope,Map.of("assets/worldsmith/models/test.json",
            ("{\"value\":"+value+"}").getBytes(StandardCharsets.UTF_8)));
    }

    @Test void enteringTheJustPreparedWorldReusesOnlyVerifiedIdenticalResources() {
        var prepared=pack("oathfire",1);var entering=pack("oathfire",1);
        assertNotSame(prepared,entering);
        assertTrue(WorldResourceIdentity.matchesLoaded(prepared,entering,true,prepared.sentinelBytes()));
        assertTrue(WorldResourceIdentity.matchesLoaded(entering,prepared,true,entering.sentinelBytes()),"Identical rollback still advances ownership without reloading");
    }

    @Test void changedBundleScopeOrContentRequiresReload() {
        var oathfire=pack("oathfire",1);var jiuzhou=pack("jiuzhou",1);var changed=pack("oathfire",2);
        assertFalse(WorldResourceIdentity.matchesLoaded(oathfire,jiuzhou,true,jiuzhou.sentinelBytes()));
        assertFalse(WorldResourceIdentity.matchesLoaded(oathfire,changed,true,changed.sentinelBytes()));
    }

    @Test void missingRemovedOrCorruptLoadedSentinelNeverCountsAsVerified() {
        var current=pack("oathfire",1);var same=pack("oathfire",1);
        assertFalse(WorldResourceIdentity.matchesLoaded(current,same,false,current.sentinelBytes()));
        assertFalse(WorldResourceIdentity.matchesLoaded(current,same,true,null));
        assertFalse(WorldResourceIdentity.matchesLoaded(current,same,true,new byte[0]));
        assertFalse(WorldResourceIdentity.matchesLoaded(current,same,true,pack("jiuzhou",1).sentinelBytes()));
        var damaged=current.sentinelBytes();damaged[0]^=1;
        assertFalse(WorldResourceIdentity.matchesLoaded(current,same,true,damaged));
    }

    @Test void initialActivationAndClearAreNotResourceReuse() {
        var target=pack("oathfire",1);
        assertFalse(WorldResourceIdentity.matchesLoaded(null,target,true,target.sentinelBytes()));
        assertFalse(WorldResourceIdentity.matchesLoaded(target,null,true,target.sentinelBytes()));
    }
}
