package com.wjz.worldsmith.worldgen;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Small local A* routes. All decisions use noise columns and are persisted before chunk writes. */
public final class WorldsmithRoadPlanner {
    public static final int MAX_BLOCKS=8192,MAX_NODES=2048;
    public record PlacedPart(WorldsmithStructurePlan.Part part,BlockPos position,Rotation rotation,List<BoundingBox> foundations,List<BoundingBox> cuts) {
        public BlockPos transform(BlockPos local){return local.rotate(rotation).offset(position);}
        public BoundingBox bounds(){return BoundingBox.fromCorners(position,transform(new BlockPos(part.size().getX()-1,part.size().getY()-1,part.size().getZ()-1)));}
    }
    private record Surface(int support,int cut,boolean bridge,double cost) {}
    private record Step(BlockPos pos,int bridgeRun,Direction incoming) {}
    private record Node(Step step,double cost,double score) {}
    private static final List<Direction> DIRECTIONS=List.of(Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST);
    private WorldsmithRoadPlanner() {}

    public static Optional<Map<BlockPos,BlockState>> plan(WorldsmithStructurePlan source,List<PlacedPart> parts,WorldsmithRoadSettings rules,
        BlockPos origin,WorldsmithTerrainProbe.Sampler sampler,int minY,int maxY) {
        List<BoundingBox> buildings=parts.stream().map(PlacedPart::bounds).toList();
        var bounds=new BoundingBox(origin.getX()-rules.radius(),minY,origin.getZ()-rules.radius(),origin.getX()+rules.radius(),maxY,origin.getZ()+rules.radius());
        Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();Map<Long,Integer> deckHeights=new HashMap<>();
        for(var link:source.links()) {
            if(!link.passage())continue;
            var a=parts.get(link.from());var b=parts.get(link.to());
            var exitA=a.transform(link.exitFrom());var exitB=b.transform(link.exitTo());
            Direction faceA=a.rotation().rotate(link.facingFrom()),faceB=b.rotation().rotate(link.facingTo());
            // Keep authored door/floor cells intact; the route begins one cell outside each facade.
            var start=exitA.relative(faceA).below();var end=exitB.relative(faceB).below();
            var route=find(start,end,bounds,buildings,rules,sampler,minY,maxY,deckHeights);
            if(route.isEmpty())return Optional.empty();
            int span=0;var path=route.get();
            for(int i=0;i<path.size();i++) {
                var point=path.get(i);var surface=surface(point,rules,sampler,minY,maxY);
                if(surface==null)return Optional.empty();
                span=surface.bridge?span+1:0;if(span>rules.maxSpan())return Optional.empty();
                Direction uphill=null;int rising=0;
                for(int j:new int[]{i-1,i+1})if(j>=0&&j<path.size()&&path.get(j).getY()>point.getY()) {
                    var next=path.get(j);rising++;
                    uphill=direction(next.getX()-point.getX(),next.getZ()-point.getZ());
                }
                int half=rules.width()/2;
                var neighbour=i+1<path.size()?path.get(i+1):path.get(Math.max(0,i-1));
                Direction side=direction(neighbour.getX()-point.getX(),neighbour.getZ()-point.getZ()).getClockWise();
                for(int w=-half;w<=half;w++) {
                    int dx=side.getStepX()*w,dz=side.getStepZ()*w;
                    var p=point.offset(dx,0,dz);
                    if(occupied(p,buildings))continue; // narrow aprons, never carve an authored facade
                    if(!bounds.isInside(p)||p.getY()+3>maxY)return Optional.empty();
                    var s=surface(p,rules,sampler,minY,maxY);if(s==null)return Optional.empty();
                    Integer existing=deckHeights.putIfAbsent(WorldsmithStructurePlan.columnKey(p),p.getY());
                    if(existing!=null&&existing!=p.getY())return Optional.empty();
                    BlockState material=s.bridge?rules.bridge().orElseThrow():rules.material();
                    if(rising==1&&rules.stairs().isPresent())material=rules.stairs().get().setValue(StairBlock.FACING,uphill);
                    blocks.put(p,material);
                    for(int y=1;y<=3;y++)blocks.put(p.above(y),Blocks.AIR.defaultBlockState());
                    int depth=p.getY()-s.support;
                    // Normal paths fill shallow gaps; bridges use sparse bounded posts.
                    if(depth>0&&depth<=12&&(!s.bridge || dx==0&&dz==0&&i%4==0)) {
                        for(int y=s.support;y<p.getY();y++)blocks.put(new BlockPos(p.getX(),y,p.getZ()),s.bridge?rules.bridge().orElseThrow():rules.material());
                    }
                    if(blocks.size()>MAX_BLOCKS)return Optional.empty();
                }
            }
        }
        return Optional.of(Collections.unmodifiableMap(blocks));
    }

    private static Optional<List<BlockPos>> find(BlockPos start,BlockPos end,BoundingBox bounds,List<BoundingBox> buildings,
        WorldsmithRoadSettings rules,WorldsmithTerrainProbe.Sampler sampler,int minY,int maxY,Map<Long,Integer> deckHeights) {
        var initialSurface=surface(start,rules,sampler,minY,maxY);
        if(!bounds.isInside(start)||!bounds.isInside(end)||occupied(start,buildings)||occupied(end,buildings)||initialSurface==null||surface(end,rules,sampler,minY,maxY)==null
            ||conflicts(start,deckHeights)||conflicts(end,deckHeights))return Optional.empty();
        int low=Math.max(minY+1,Math.min(start.getY(),end.getY())-4),high=Math.min(maxY-3,Math.max(start.getY(),end.getY())+4);
        var queue=new PriorityQueue<Node>(Comparator.comparingDouble(Node::score).thenComparingInt(n->n.step.pos.getX()).thenComparingInt(n->n.step.pos.getY()).thenComparingInt(n->n.step.pos.getZ())
            .thenComparingInt(n->n.step.bridgeRun).thenComparingInt(n->n.step.incoming==null?-1:n.step.incoming.ordinal()));
        Map<Step,Double> costs=new HashMap<>();Map<Step,Step> parents=new HashMap<>();
        var first=new Step(start,initialSurface.bridge?1:0,null);
        costs.put(first,0.0);queue.add(new Node(first,0,estimate(start,end)));int visited=0;
        while(!queue.isEmpty()&&visited++<MAX_NODES) {
            var current=queue.remove();var step=current.step;
            if(current.cost>costs.getOrDefault(step,Double.POSITIVE_INFINITY))continue;
            if(step.pos.equals(end)) {
                List<BlockPos> path=new ArrayList<>();Step p=step;
                while(p!=null){path.add(p.pos);if(path.size()>128)return Optional.empty();p=parents.get(p);}
                Collections.reverse(path);return Optional.of(path);
            }
            for(var direction:DIRECTIONS)for(int dy:new int[]{0,1,-1}) {
                var next=step.pos.relative(direction).above(dy);
                if(next.getY()<low||next.getY()>high||!bounds.isInside(next)||occupied(next,buildings)||conflicts(next,deckHeights))continue;
                var s=surface(next,rules,sampler,minY,maxY);if(s==null)continue;
                int bridgeRun=s.bridge?step.bridgeRun+1:0;if(bridgeRun>rules.maxSpan())continue;
                var nextStep=new Step(next,bridgeRun,direction);
                double turn=step.incoming!=null&&step.incoming!=direction?0.6:0;
                double cost=current.cost+1+Math.abs(dy)*1.5+s.cost+turn;
                if(cost>=costs.getOrDefault(nextStep,Double.POSITIVE_INFINITY))continue;
                costs.put(nextStep,cost);parents.put(nextStep,step);queue.add(new Node(nextStep,cost,cost+estimate(next,end)));
            }
        }
        return Optional.empty();
    }
    private static boolean conflicts(BlockPos p,Map<Long,Integer> deckHeights) {
        Integer height=deckHeights.get(WorldsmithStructurePlan.columnKey(p));return height!=null&&height!=p.getY();
    }
    private static double estimate(BlockPos a,BlockPos b){return Math.max(Math.abs(a.getX()-b.getX())+Math.abs(a.getZ()-b.getZ()),Math.abs(a.getY()-b.getY()));}
    private static boolean occupied(BlockPos p,List<BoundingBox> buildings){return buildings.stream().anyMatch(b->p.getX()>=b.minX()&&p.getX()<=b.maxX()&&p.getZ()>=b.minZ()&&p.getZ()<=b.maxZ());}
    private static Direction direction(int dx,int dz){return dx>0?Direction.EAST:dx<0?Direction.WEST:dz>0?Direction.SOUTH:Direction.NORTH;}

    private static Surface surface(BlockPos p,WorldsmithRoadSettings rules,WorldsmithTerrainProbe.Sampler sampler,int minY,int maxY) {
        if(p.getY()<minY+1||p.getY()+3>maxY)return null;
        var column=sampler.sample(p.getX(),p.getZ());Surface best=null;
        for(var span:column.spans()) {
            if(p.getY()+3>=span.ceilingY())continue;
            if(!span.solidFloor()&&!span.waterFloor()) {
                // Void between sky islands has no floor at all. Allow a bounded
                // suspended deck only inside the measured air interval; no posts.
                if(span.floorY()==minY&&p.getY()>=span.floorY()&&rules.bridge().isPresent()&&rules.maxSpan()>0) {
                    var candidate=new Surface(minY-13,0,true,8);
                    if(best==null||candidate.cost<best.cost)best=candidate;
                }
                continue;
            }
            if(span.waterFloor()&&p.getY()<span.floorY())continue;
            int support=span.waterFloor()?span.supportY():span.floorY();
            int cut=Math.max(0,support-1-p.getY());if(cut>rules.maxCut())continue;
            int gap=p.getY()-support;
            boolean bridge=span.waterFloor()||gap>2;
            if(bridge&&(rules.bridge().isEmpty()||rules.maxSpan()==0))continue;
            double cost=cut*3+Math.max(0,gap)*0.15+(bridge?4:0);
            var candidate=new Surface(support,cut,bridge,cost);
            if(best==null||cost<best.cost)best=candidate;
        }
        return best;
    }
}
