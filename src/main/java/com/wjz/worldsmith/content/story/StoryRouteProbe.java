package com.wjz.worldsmith.content.story;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Read-only local walking evidence. No navigation command, chunk request, teleport or block write is issued. */
public final class StoryRouteProbe {
    public static final int MAX_NODES=8192,MAX_RADIUS=128;
    private static final double EPS=0.00001,MAX_DROP=1.0,MAX_AUTO_STEP=0.6;
    public static final String MODEL="Walk-only loaded dry supported block geometry, current player body, cardinal travel; ascent <=min(actual player.maxUpStep(),0.6), drop <=1 block. No jumps, swimming, ladders, portals, gap-crossing, mobs, scripted hazards or full movement physics are certified.";
    public enum Status { VERIFIED, UNLOADED, NO_PATH_WITHIN_BUDGET }
    public record Report(Status status,Vec3 start,BlockPos target,String dimension,List<Vec3> path,int visitedNodes,int maxNodes,int radius,boolean touchedUnloaded,String model,String detail) {
        public Report {Objects.requireNonNull(status);Objects.requireNonNull(start);target=target.immutable();path=List.copyOf(path);Objects.requireNonNull(dimension);Objects.requireNonNull(model);Objects.requireNonNull(detail);}
    }
    private StoryRouteProbe() {}
    public static Report probe(ServerPlayer player,StorySavedData.Place place){return probe(player,place,MAX_NODES,MAX_RADIUS);}
    public static Report probe(ServerPlayer player,StorySavedData.Place place,int maxNodes,int radius) {
        Objects.requireNonNull(player);Objects.requireNonNull(place);limits(maxNodes,radius);
        var level=(ServerLevel)player.level();WorldStoryRuntime.requireThread(level);
        Vec3 start=player.position();String dimension=level.dimension().identifier().toString();
        var bound=WorldStoryRuntime.WORLDS.get(level);
        if(bound==null||!place.equals(bound.store().state().places().get(place.instance()))||!place.dimension().equals(dimension))
            return report(Status.NO_PATH_WITHIN_BUDGET,start,place.position(),dimension,List.of(),0,maxNodes,radius,false,"Target is not an actual registered place in this dimension; no route was inferred from a declaration.");
        double width=player.getBbWidth(),height=player.getBbHeight(),ascent=player.maxUpStep();
        if(!Double.isFinite(width)||!Double.isFinite(height)||!Double.isFinite(ascent)||width<=0||width>2||height<=0||height>3||ascent<0)
            return report(Status.NO_PATH_WITHIN_BUDGET,start,place.position(),dimension,List.of(),0,maxNodes,radius,false,"Current player body exceeds the bounded walking model (width <=2, height <=3).");
        return search(new NativeGrid(level),start,Vec3.atBottomCenterOf(place.position()),dimension,width,height,maxNodes,radius,Math.min(ascent,MAX_AUTO_STEP));
    }
    static void limits(int nodes,int radius){if(nodes<1||nodes>MAX_NODES||radius<1||radius>MAX_RADIUS)throw new IllegalArgumentException("Route probe permits 1..8192 nodes and radius 1..128");}
    enum Access { AVAILABLE, BLOCKED, UNLOADED }
    /** The pure search can be tested with a geometry fixture; production always uses NativeGrid and registered places. */
    interface Grid {
        Access availability(AABB bounds);
        List<Double> surfaces(int x,int z,double minY,double maxY,double width);
        boolean clear(AABB body);
        boolean supported(Vec3 feet,double width);
    }
    private record Node(int x,int z,double y) {}
    private record Pending(Node node,double cost,double score,long order) {}
    private static final class Search {
        final Grid grid;final double width,height,ascent;boolean unloaded;
        Search(Grid grid,double width,double height,double ascent){this.grid=grid;this.width=width;this.height=height;this.ascent=ascent;}
        boolean available(AABB bounds) {var access=grid.availability(bounds);if(access==Access.UNLOADED)unloaded=true;return access==Access.AVAILABLE;}
        boolean clear(AABB bounds){return available(collisionReadBounds(bounds))&&grid.clear(bounds);}
        boolean standable(Vec3 feet){return clear(body(feet,width,height))&&grid.supported(feet,width);}
        boolean transition(Vec3 from,Vec3 to) {
            if(to.y-from.y>ascent+EPS||from.y-to.y>MAX_DROP+EPS)return false;
            double high=Math.max(from.y,to.y);
            Vec3 raised=new Vec3(from.x,high,from.z),across=new Vec3(to.x,high,to.z);
            // A conservative vertical-then-horizontal step/drop sweep; no diagonal corner cutting.
            return clear(union(body(from,width,height),body(raised,width,height)))
                &&clear(union(body(raised,width,height),body(across,width,height)))
                &&clear(union(body(across,width,height),body(to,width,height)));
        }
    }
    static Report search(Grid grid,Vec3 start,Vec3 goal,String dimension,double width,double height,int maxNodes,int radius) {
        return search(grid,start,goal,dimension,width,height,maxNodes,radius,MAX_AUTO_STEP);
    }
    static Report search(Grid grid,Vec3 start,Vec3 goal,String dimension,double width,double height,int maxNodes,int radius,double ascent) {
        limits(maxNodes,radius);if(!Double.isFinite(ascent)||ascent<0||ascent>MAX_AUTO_STEP)throw new IllegalArgumentException("Unsupported automatic step height");
        var search=new Search(grid,width,height,ascent);var target=BlockPos.containing(goal);
        if(!finite(start)||!finite(goal)||start.distanceToSqr(goal)>radius*(double)radius)
            return report(Status.NO_PATH_WITHIN_BUDGET,start,target,dimension,List.of(),0,maxNodes,radius,false,"Actual target lies outside this local probe radius.");
        if(!search.standable(start)||!search.standable(goal))
            return report(search.unloaded?Status.UNLOADED:Status.NO_PATH_WITHIN_BUDGET,start,target,dimension,List.of(),0,maxNodes,radius,search.unloaded,"The actual start or marker feet were not certified as loaded, supported, dry and collision-free.");
        var first=node(start);Map<Node,Double> costs=new HashMap<>();Map<Node,Node> previous=new HashMap<>();Map<Node,Vec3> points=new HashMap<>();Set<Node> closed=new HashSet<>();
        var queue=new PriorityQueue<Pending>(Comparator.comparingDouble(Pending::score).thenComparingLong(Pending::order));long order=0;int visited=0;
        costs.put(first,0.0);points.put(first,start);queue.add(new Pending(first,0,estimate(start,goal),order++));
        int[][] directions={{1,0},{0,1},{-1,0},{0,-1}};
        while(!queue.isEmpty()&&visited<maxNodes) {
            var pending=queue.remove();var current=pending.node;
            if(pending.cost>costs.getOrDefault(current,Double.POSITIVE_INFINITY)||!closed.add(current))continue;
            visited++;var from=points.get(current);
            if(current.x==(int)Math.floor(goal.x)&&current.z==(int)Math.floor(goal.z)&&Math.abs(from.y-goal.y)<=EPS&&search.transition(from,goal)) {
                var path=new ArrayList<Vec3>();for(Node at=current;at!=null;at=previous.get(at))path.add(points.get(at));Collections.reverse(path);
                if(path.getLast().distanceToSqr(goal)>EPS*EPS)path.add(goal);
                return report(Status.VERIFIED,start,target,dimension,path,visited,maxNodes,radius,search.unloaded,"A concrete walking path was certified from the player's actual feet to this saved marker at inspection time, under the stated local model.");
            }
            for(var d:directions) {
                int x=current.x+d[0],z=current.z+d[1];double dx=x+.5-start.x,dz=z+.5-start.z;
                if(dx*dx+dz*dz>radius*(double)radius)continue;
                var query=collisionReadBounds(new AABB(x+.5-width/2,from.y-MAX_DROP-EPS-2,z+.5-width/2,x+.5+width/2,from.y+ascent+EPS+height,z+.5+width/2));
                if(!search.available(query))continue;
                var surfaces=new TreeSet<>(grid.surfaces(x,z,from.y-MAX_DROP-EPS,from.y+ascent+EPS,width));
                for(double y:surfaces) {
                    var point=new Vec3(x+.5,y,z+.5);
                    if(!finite(point)||y-from.y>ascent+EPS||from.y-y>MAX_DROP+EPS||point.distanceToSqr(start)>radius*(double)radius)continue;
                    var next=node(point);if(closed.contains(next)||!search.standable(point)||!search.transition(from,point))continue;
                    double cost=pending.cost+1+Math.abs(y-from.y)*.25;
                    if(cost>=costs.getOrDefault(next,Double.POSITIVE_INFINITY))continue;
                    // The budget bounds all distinct allocated nodes, not just popped/visited nodes.
                    if(!costs.containsKey(next)&&costs.size()>=maxNodes)continue;
                    costs.put(next,cost);previous.put(next,current);points.put(next,point);queue.add(new Pending(next,cost,cost+estimate(point,goal),order++));
                }
            }
        }
        return report(search.unloaded?Status.UNLOADED:Status.NO_PATH_WITHIN_BUDGET,start,target,dimension,List.of(),visited,maxNodes,radius,search.unloaded,
            search.unloaded?"Search met unloaded space; no path was certified and no chunk was requested.":"No walking path was certified within the radius/node budget and stated model; this is not proof of global inaccessibility.");
    }
    private static Node node(Vec3 point){return new Node((int)Math.floor(point.x),(int)Math.floor(point.z),point.y);}
    private static double estimate(Vec3 a,Vec3 b){return Math.abs(a.x-b.x)+Math.abs(a.z-b.z)+Math.abs(a.y-b.y)*.25;}
    private static boolean finite(Vec3 v){return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
    /** Exact 26.2 BlockCollisions inclusive cursor, represented as an exclusive-upper voxel volume. */
    static AABB collisionReadBounds(AABB box){return new AABB(Math.floor(box.minX-1e-7)-1,Math.floor(box.minY-1e-7)-1,Math.floor(box.minZ-1e-7)-1,
        Math.floor(box.maxX+1e-7)+2,Math.floor(box.maxY+1e-7)+2,Math.floor(box.maxZ+1e-7)+2);}
    private static AABB body(Vec3 feet,double width,double height){return new AABB(feet.x-width/2,feet.y+EPS,feet.z-width/2,feet.x+width/2,feet.y+height,feet.z+width/2);}
    private static AABB union(AABB a,AABB b){return new AABB(Math.min(a.minX,b.minX),Math.min(a.minY,b.minY),Math.min(a.minZ,b.minZ),Math.max(a.maxX,b.maxX),Math.max(a.maxY,b.maxY),Math.max(a.maxZ,b.maxZ));}
    private static Report report(Status status,Vec3 start,BlockPos target,String dimension,List<Vec3> path,int visited,int nodes,int radius,boolean unloaded,String detail){return new Report(status,start,target,dimension,path,visited,nodes,radius,unloaded,MODEL,detail);}

    private static final class NativeGrid implements Grid {
        private final ServerLevel level;
        NativeGrid(ServerLevel level){this.level=level;}
        @Override public Access availability(AABB box) {
            if(!level.getWorldBorder().isWithinBounds(box))return Access.BLOCKED;
            int minX=((int)Math.floor(box.minX))>>4,maxX=((int)Math.floor(box.maxX-EPS))>>4,minZ=((int)Math.floor(box.minZ))>>4,maxZ=((int)Math.floor(box.maxZ-EPS))>>4;
            for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)if(level.getChunkSource().getChunkNow(x,z)==null)return Access.UNLOADED;
            return Access.AVAILABLE;
        }
        private BlockState state(BlockPos at) {
            var chunk=level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
            if(chunk==null)throw new IllegalStateException("A chunk became unavailable during a server-thread read-only route inspection");
            return chunk.getBlockState(at);
        }
        @Override public List<Double> surfaces(int x,int z,double minY,double maxY,double width) {
            var result=new ArrayList<Double>();double cx=x+.5,cz=z+.5;
            int minX=(int)Math.floor(cx-width/2)-1,maxX=(int)Math.floor(cx+width/2)+1,minZ=(int)Math.floor(cz-width/2)-1,maxZ=(int)Math.floor(cz+width/2)+1;
            for(int bx=minX;bx<=maxX;bx++)for(int bz=minZ;bz<=maxZ;bz++)for(int by=Math.max(level.getMinY(),(int)Math.floor(minY)-2);by<=Math.min(level.getMaxY()-1,(int)Math.floor(maxY));by++) {
                var pos=new BlockPos(bx,by,bz);var block=state(pos);if(danger(block))continue;
                for(var shape:block.getCollisionShape(level,pos).toAabbs()) {
                    double y=by+shape.maxY;
                    if(y>=minY&&y<=maxY&&bx+shape.maxX>cx-width/2+EPS&&bx+shape.minX<cx+width/2-EPS&&bz+shape.maxZ>cz-width/2+EPS&&bz+shape.minZ<cz+width/2-EPS)result.add(y);
                }
            }
            return result;
        }
        @Override public boolean clear(AABB body) {
            if(body.minY<level.getMinY()||body.maxY>level.getMaxY())return false;
            for(var shape:level.getBlockCollisions(null,body))if(!shape.isEmpty())return false;
            return !hazard(body);
        }
        @Override public boolean supported(Vec3 feet,double width) {
            var sole=new AABB(feet.x-width/2+EPS,feet.y-.05,feet.z-width/2+EPS,feet.x+width/2-EPS,feet.y+EPS,feet.z+width/2-EPS);
            if(hazard(sole))return false;
            for(var shape:level.getBlockCollisions(null,sole))if(!shape.isEmpty())return true;
            return false;
        }
        private boolean hazard(AABB box) {
            for(int x=(int)Math.floor(box.minX);x<=(int)Math.floor(box.maxX-EPS);x++)for(int z=(int)Math.floor(box.minZ);z<=(int)Math.floor(box.maxZ-EPS);z++)for(int y=Math.max(level.getMinY(),(int)Math.floor(box.minY));y<=Math.min(level.getMaxY()-1,(int)Math.floor(box.maxY-EPS));y++)
                if(danger(state(new BlockPos(x,y,z))))return true;
            return false;
        }
        private static boolean danger(BlockState s) {
            return !s.getFluidState().isEmpty()||s.is(Blocks.FIRE)||s.is(Blocks.SOUL_FIRE)||s.is(Blocks.MAGMA_BLOCK)||s.is(Blocks.CACTUS)||s.is(Blocks.POWDER_SNOW)
                ||s.is(Blocks.SWEET_BERRY_BUSH)||s.is(Blocks.WITHER_ROSE)||s.is(Blocks.POINTED_DRIPSTONE)||s.is(Blocks.NETHER_PORTAL)||s.is(Blocks.END_PORTAL)||s.is(Blocks.END_GATEWAY)
                ||(s.is(Blocks.CAMPFIRE)||s.is(Blocks.SOUL_CAMPFIRE))&&s.getValue(BlockStateProperties.LIT);
        }
    }
}
