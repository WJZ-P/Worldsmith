package com.wjz.worldsmith.content.item;

import java.util.ArrayList;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ItemBlinkSweepTest {
    private static final AABB PLAYER=new AABB(-0.3,64,-0.3,0.3,65.8,0.3);

    @Test void eyeHeightSlitDoesNotAllowTheBodyThroughTheWall() {
        // Eye y=65.62 sees through y=65..66, but the player's legs still meet this wall.
        var lowerWall=new AABB(2,64,-1,2.25,65,1);
        var upperWall=new AABB(2,66,-1,2.25,67,1);
        assertFalse(ItemActions.sweptBodyIsClear(PLAYER,new Vec3(6,0,0),
            box -> !box.intersects(lowerWall) && !box.intersects(upperWall)));
    }

    @Test void probesAreBoundedAndIncludeTheActualDestination() {
        var probes=new ArrayList<AABB>();var displacement=new Vec3(5.7,-0.6,0.5);
        assertTrue(ItemActions.sweptBodyIsClear(PLAYER,displacement,box->{probes.add(box);return true;}));
        AABB previous=PLAYER;
        for(AABB next:probes) {
            assertTrue(next.getCenter().distanceTo(previous.getCenter())<=0.250001);
            previous=next;
        }
        assertEquals(PLAYER.move(displacement),probes.getLast());
        assertTrue(probes.size()<=33);
    }

    @Test void blockedOrUnloadedIntermediateProbeStopsBeforeTheDestination() {
        var probes=new ArrayList<AABB>();
        assertFalse(ItemActions.sweptBodyIsClear(PLAYER,new Vec3(6,0,0),box->{probes.add(box);return box.maxX<2;}));
        assertTrue(probes.getLast().maxX<3);
    }

    @Test void invalidOrOverrangeDisplacementDoesNotQueryTheWorld() {
        for(Vec3 displacement:new Vec3[]{new Vec3(Double.NaN,0,0),new Vec3(20,0,0)})
            assertFalse(ItemActions.sweptBodyIsClear(PLAYER,displacement,box->{fail("Invalid movement must not inspect world blocks");return true;}));
    }
}
