package com.wjz.worldsmith.content.story;

import java.util.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure corridor geometry checks; native generated village/ruin tests independently exercise the real chunk adapter. */
class StoryRouteProbeTest {
    private static final Vec3 START=new Vec3(.5,1,.5),RUIN=new Vec3(12.5,1,.5);
    private static final class Corridor implements StoryRouteProbe.Grid {
        int blocked=-1,unloaded=Integer.MAX_VALUE;final Map<Integer,Double> heights=new HashMap<>();
        Corridor(){for(int x=0;x<=12;x++)heights.put(x,1.0);}
        @Override public StoryRouteProbe.Access availability(AABB bounds){return bounds.maxX>unloaded?StoryRouteProbe.Access.UNLOADED:StoryRouteProbe.Access.AVAILABLE;}
        @Override public List<Double> surfaces(int x,int z,double min,double max,double width){return z==0&&heights.containsKey(x)?List.of(heights.get(x)):List.of();}
        @Override public boolean clear(AABB body){return blocked<0||!body.intersects(new AABB(blocked,1,0,blocked+1,4,1));}
        @Override public boolean supported(Vec3 feet,double width){return (int)Math.floor(feet.z)==0&&Math.abs(heights.getOrDefault((int)Math.floor(feet.x),-100.0)-feet.y)<.001;}
    }
    private StoryRouteProbe.Report route(Corridor grid,int nodes){return StoryRouteProbe.search(grid,START,RUIN,"minecraft:overworld",.6,1.8,nodes,32);}
    @Test void aConcreteSupportedVillageToRuinCorridorProducesAPathWithActualEndpoints() {
        var report=route(new Corridor(),8192);
        assertEquals(StoryRouteProbe.Status.VERIFIED,report.status());assertEquals(START,report.path().getFirst());assertEquals(RUIN,report.path().getLast());
        assertTrue(report.visitedNodes()<=8192);assertFalse(report.touchedUnloaded());
        assertThrows(UnsupportedOperationException.class,() -> report.path().clear());
    }
    @Test void blockedAndUnloadedAreDifferentEvidenceAndNeitherMeansGloballyUnreachable() {
        var blocked=new Corridor();blocked.blocked=6;var noPath=route(blocked,8192);
        assertEquals(StoryRouteProbe.Status.NO_PATH_WITHIN_BUDGET,noPath.status());assertTrue(noPath.path().isEmpty());assertTrue(noPath.detail().contains("not proof of global"));
        var unloaded=new Corridor();unloaded.unloaded=8;var unknown=route(unloaded,8192);
        assertEquals(StoryRouteProbe.Status.UNLOADED,unknown.status());assertTrue(unknown.touchedUnloaded());assertTrue(unknown.path().isEmpty());
    }
    @Test void nodeAndRadiusLimitsRemainExplicitAndUnsupportedGapsAreNotFabricatedAsRoutes() {
        assertEquals(StoryRouteProbe.Status.NO_PATH_WITHIN_BUDGET,route(new Corridor(),2).status());
        var gap=new Corridor();gap.heights.remove(6);assertEquals(StoryRouteProbe.Status.NO_PATH_WITHIN_BUDGET,route(gap,8192).status());
        var high=new Corridor();high.heights.put(6,3.0);assertEquals(StoryRouteProbe.Status.NO_PATH_WITHIN_BUDGET,route(high,8192).status());
        assertThrows(IllegalArgumentException.class,() -> StoryRouteProbe.limits(8193,128));assertThrows(IllegalArgumentException.class,() -> StoryRouteProbe.limits(8192,129));
    }
    @Test void integerCollisionFacesPreflightTheSameNeighbourColumnsAsNativeCollisionIteration() {
        var bounds=StoryRouteProbe.collisionReadBounds(new AABB(17,1,0,18,2.8,1));
        assertEquals(15,bounds.minX);assertEquals(20,bounds.maxX);
        StoryRouteProbe.Grid edge=new StoryRouteProbe.Grid() {
            public StoryRouteProbe.Access availability(AABB b){return b.minX<16?StoryRouteProbe.Access.UNLOADED:StoryRouteProbe.Access.AVAILABLE;}
            public List<Double> surfaces(int x,int z,double min,double max,double width){return List.of(1.0);}
            public boolean clear(AABB b){return true;}
            public boolean supported(Vec3 feet,double width){return true;}
        };
        var report=StoryRouteProbe.search(edge,new Vec3(17.5,1,.5),new Vec3(18.5,1,.5),"minecraft:overworld",1,1.8,128,16);
        assertEquals(StoryRouteProbe.Status.UNLOADED,report.status(),"The neighbouring chunk touched only by the native large-shape cursor is still required evidence");
        assertTrue(report.model().contains("No jumps"));
    }
    @Test void automaticSteppingDoesNotCertifyAOneBlockJumpOrIgnoreTheActualStepAttribute() {
        var halfStep=new Corridor();halfStep.heights.put(6,1.5);
        assertEquals(StoryRouteProbe.Status.VERIFIED,route(halfStep,8192).status());
        var jump=new Corridor();jump.heights.put(6,2.0);
        assertEquals(StoryRouteProbe.Status.NO_PATH_WITHIN_BUDGET,route(jump,8192).status());
        var noStep=StoryRouteProbe.search(halfStep,START,RUIN,"minecraft:overworld",.6,1.8,8192,32,0.0);
        assertEquals(StoryRouteProbe.Status.NO_PATH_WITHIN_BUDGET,noStep.status());
    }
}
