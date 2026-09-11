package com.wjz.worldsmith.authoring;

import com.wjz.worldsmith.core.draw.*;
import java.util.*;

/** One logical building. Helpers write geometry and its semantics from the same coordinates. */
public final class AuthoringContext {
    private final DrawContext input;
    private DrawCanvas canvas;
    private Vec3i origin;
    private final List<Map<String,Object>> rooms=new ArrayList<>(),passages=new ArrayList<>(),lights=new ArrayList<>(),ports=new ArrayList<>(),interactions=new ArrayList<>();
    private final List<Map<String,Object>> entrances=new ArrayList<>(),destinations=new ArrayList<>(),supports=new ArrayList<>(),protectedAreas=new ArrayList<>(),clearance=new ArrayList<>();
    private final Map<String,Object> palette=new TreeMap<>();
    private final Map<String,Box> components=new TreeMap<>();
    private final Set<String> names=new HashSet<>();
    public AuthoringContext(DrawContext input){this.input=Objects.requireNonNull(input);}
    public long seed(){return input.seed();}
    public Map<String,String> parameters(){return input.parameters();}
    public SplittableRandom random(String stream){return new SplittableRandom(seed()^((long)stream.hashCode()*0x9E3779B97F4A7C15L));}
    public DrawCanvas canvas(Box bounds){if(canvas!=null)throw new IllegalStateException("One canvas per logical building");canvas=input.canvas(bounds);origin=bounds.min();return canvas;}
    public DrawCanvas canvas(){return Objects.requireNonNull(canvas,"Create the canvas first");}
    public AuthoringContext origin(Vec3i at){origin=at;return this;}
    public AuthoringContext material(String name,BlockStateRef state){palette.put(name,Map.of("block",state.id(),"properties",state.properties()));return this;}
    public AuthoringContext room(String id,Box interior,BlockStateRef floor){return occupied(id,interior,floor,false);}
    public AuthoringContext indoorPassage(String id,Box interior,BlockStateRef floor){return occupied(id,interior,floor,true);}
    private AuthoringContext occupied(String id,Box interior,BlockStateRef floor,boolean passage){
        named(id);canvas().pen("air").fill(interior);
        canvas().pen(Brush.solid(floor)).fill(Box.of(interior.min().x(),interior.min().y()-1,interior.min().z(),interior.max().x(),interior.min().y()-1,interior.max().z()));
        // Occupancy describes this storey's floor, not furniture tops elsewhere
        // in the cleared volume. Upper storeys get their own explicit room.
        (passage?passages:rooms).add(AuthoredStructure.box(Box.of(interior.min().x(),interior.min().y(),interior.min().z(),interior.max().x(),interior.min().y(),interior.max().z())));
        destinations.add(AuthoredStructure.point(new Vec3i((interior.min().x()+interior.max().x())/2,interior.min().y(),(interior.min().z()+interior.max().z())/2)));
        components.put(id,interior);return this;
    }
    /** Connect the physical entrance to the canvas edge with an explicit floor and AIR corridor. */
    public AuthoringContext entrance(String id,Vec3i feet,String facing,BlockStateRef floor,int headroom){
        named(id);if(headroom<2||headroom>4)throw new IllegalArgumentException("Headroom is 2..4");
        var b=canvas().bounds();var edge=switch(facing){case "NORTH"->new Vec3i(feet.x(),feet.y(),b.min().z());case "SOUTH"->new Vec3i(feet.x(),feet.y(),b.max().z());case "EAST"->new Vec3i(b.max().x(),feet.y(),feet.z());case "WEST"->new Vec3i(b.min().x(),feet.y(),feet.z());default->throw new IllegalArgumentException("Horizontal facing required");};
        Box corridor=Box.of(Math.min(feet.x(),edge.x()),feet.y(),Math.min(feet.z(),edge.z()),Math.max(feet.x(),edge.x()),feet.y()+headroom-1,Math.max(feet.z(),edge.z()));
        canvas().pen("air").fill(corridor);canvas().pen(Brush.solid(floor)).fill(Box.of(corridor.min().x(),feet.y()-1,corridor.min().z(),corridor.max().x(),feet.y()-1,corridor.max().z()));
        ports.add(Map.of("id",id,"at",AuthoredStructure.point(edge),"facing",facing,"type","walk","passage",true));entrances.add(AuthoredStructure.point(feet));clearance.add(AuthoredStructure.box(corridor));return this;
    }
    public AuthoringContext lightFixture(String id,Vec3i at,BlockStateRef state,int level){named(id);canvas().pen(Brush.solid(state)).set(at.x(),at.y(),at.z());lights.add(Map.of("at",AuthoredStructure.point(at),"level",level));return this;}
    public AuthoringContext support(Vec3i at){supports.add(AuthoredStructure.point(at));return this;}
    public AuthoringContext protect(Box region){protectedAreas.add(AuthoredStructure.box(region));return this;}
    public AuthoringContext component(String id,Box region){named(id);components.put(id,region);return this;}
    public AuthoringContext container(Vec3i at,BlockStateRef state,List<Item> items){canvas().pen(Brush.solid(state)).set(at.x(),at.y(),at.z());interactions.add(Map.of("kind","container","at",AuthoredStructure.point(at),"items",items.stream().map(i->Map.of("slot",i.slot(),"item",i.item(),"count",i.count())).toList()));return this;}
    public record Item(int slot,String item,int count){}
    /** Place a typed, repeatable Boss encounter; no entity NBT or executable commands are accepted. */
    public AuthoringContext bossSpawner(Vec3i at,String creatureId){return bossSpawner(at,creatureId,2400,16,4);}
    public AuthoringContext bossSpawner(Vec3i at,String creatureId,int respawnTicks,int requiredPlayerRange,int spawnRange){
        if(creatureId==null || !creatureId.matches("[a-z0-9][a-z0-9_./-]{0,95}") || Arrays.stream(creatureId.split("/",-1)).anyMatch(p->p.equals(".")||p.equals("..")))throw new IllegalArgumentException("A normalized logical Boss creature id is required");
        if(respawnTicks<200||respawnTicks>30000||requiredPlayerRange<8||requiredPlayerRange>32||spawnRange<1||spawnRange>8)throw new IllegalArgumentException("Boss spawner limits: respawnTicks 200..30000, player range 8..32, spawn range 1..8");
        canvas().pen(Brush.solid(BlockStateRef.parse("minecraft:spawner"))).set(at.x(),at.y(),at.z());
        interactions.add(Map.of("kind","boss_spawner","at",AuthoredStructure.point(at),"creatureId",creatureId,"respawnTicks",respawnTicks,"requiredPlayerRange",requiredPlayerRange,"spawnRange",spawnRange));return this;
    }
    /** Paste a reusable component, prefix identifiers and transform every semantic marker with it. */
    public AuthoringContext instance(String id,AuthoredStructure child,GridTransform transform){
        named(id);canvas().pen("air").paste(child.drawing(),transform,true);
        for(var entry:child.components().entrySet())components.put(id+"/"+entry.getKey(),transform.apply(entry.getValue()));
        components.put(id,transform.apply(child.drawing().bounds()));
        var m=child.semantics();append(rooms,m,"rooms",transform,id);append(passages,m,"indoorPassages",transform,id);append(lights,m,"sources",transform,id);append(ports,m,"ports",transform,id);
        append(entrances,m,"entrances",transform,id);append(destinations,m,"destinations",transform,id);append(supports,m,"supports",transform,id);append(protectedAreas,m,"protectedAreas",transform,id);append(clearance,m,"keepClear",transform,id);append(interactions,m,"interactions",transform,id);
        if(m.get("palette") instanceof Map<?,?> p)p.forEach((k,v)->{Object previous=palette.putIfAbsent((String)k,v);if(previous!=null&&!previous.equals(v))throw new IllegalArgumentException("Conflicting component material "+k);});
        return this;
    }
    public AuthoredStructure snapshot(){
        var m=new TreeMap<String,Object>();m.put("origin",AuthoredStructure.point(origin));m.put("rooms",rooms);m.put("indoorPassages",passages);m.put("sources",lights);m.put("ports",ports);m.put("entrances",entrances);m.put("destinations",destinations);m.put("supports",supports);m.put("protectedAreas",protectedAreas);m.put("keepClear",clearance);m.put("interactions",interactions);m.put("palette",palette);
        return new AuthoredStructure(canvas().snapshot(),m,components);
    }
    private void named(String id){if(!id.matches("[a-zA-Z0-9_./-]{1,96}")||!names.add(id))throw new IllegalArgumentException("Invalid/duplicate authoring id "+id);}
    @SuppressWarnings("unchecked") private static void append(List<Map<String,Object>> out,Map<String,Object> m,String key,GridTransform t,String prefix){for(Object v:(List<?>)m.getOrDefault(key,List.of()))out.add((Map<String,Object>)transform(v,t,prefix));}
    private static Object transform(Object v,GridTransform t,String prefix){
        if(v instanceof Map<?,?> m){
            if(m.keySet().equals(Set.of("x","y","z")))return AuthoredStructure.point(t.apply(new Vec3i(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue())));
            if(m.containsKey("from")&&m.containsKey("to")){var a=point((Map<?,?>)m.get("from"));var b=point((Map<?,?>)m.get("to"));return AuthoredStructure.box(t.apply(new Box(a,b)));}
            var result=new TreeMap<String,Object>();m.forEach((k,value)->result.put((String)k,transform(value,t,prefix)));
            if(m.containsKey("id"))result.put("id",prefix+"_"+m.get("id"));
            if(m.get("facing") instanceof String facing){String[] f={"NORTH","EAST","SOUTH","WEST"};int n=Arrays.asList(f).indexOf(facing);if(n>=0){if(t.mirrorX())n=Math.floorMod(-n,4);result.put("facing",f[(n+t.quarterTurns())%4]);}}
            return result;
        }
        if(v instanceof List<?> l)return l.stream().map(e->transform(e,t,prefix)).toList();return v;
    }
    private static Vec3i point(Map<?,?> p){return new Vec3i(((Number)p.get("x")).intValue(),((Number)p.get("y")).intValue(),((Number)p.get("z")).intValue());}
}
