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
    private final List<Map<String,Object>> hangingFixtures=new ArrayList<>();
    private String lightingIntent;
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
    /** Deliberate building-wide atmosphere; ordinary rooms still default to authored READABLE fixtures. */
    public AuthoringContext intentionallyDark(String reason){
        if(reason==null||reason.isBlank()||reason.length()>512||reason.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("A printable dark-atmosphere design reason of 1..512 characters is required");
        lightingIntent=reason;return this;
    }
    /** Place a lantern from a real authored roof/beam anchor, not a guessed ceiling height. */
    public AuthoringContext hangingLightFixture(String id,Vec3i at,Vec3i anchor){
        return hangingLightFixture(id,at,anchor,BlockStateRef.parse("minecraft:lantern[hanging=true]"),BlockStateRef.parse("minecraft:iron_chain[axis=y]"),15);
    }
    public AuthoringContext hangingLightFixture(String id,Vec3i at,Vec3i anchor,BlockStateRef light,BlockStateRef chain,int level){
        Objects.requireNonNull(at);Objects.requireNonNull(anchor);Objects.requireNonNull(light);Objects.requireNonNull(chain);
        if(!canvas().bounds().contains(at)||!canvas().bounds().contains(anchor)||at.x()!=anchor.x()||at.z()!=anchor.z()||anchor.y()<=at.y())throw new IllegalArgumentException("A hanging fixture needs an in-bounds anchor directly above the lantern");
        if(!Set.of("minecraft:lantern","minecraft:soul_lantern").contains(light.id())||!"true".equals(light.properties().get("hanging"))||level<1||level>15)throw new IllegalArgumentException("Use a hanging lantern state and level 1..15; native export verifies actual emission");
        if((!chain.id().endsWith("_chain")&&!chain.id().equals("minecraft:chain"))||!"y".equals(chain.properties().get("axis")))throw new IllegalArgumentException("Use a vertical chain state with axis=y");
        requireFixtureAnchor(anchor);
        for(int y=at.y();y<anchor.y();y++)if(canvas().get(new Vec3i(at.x(),y,at.z())).map(b->!b.state().isAir()).orElse(false))throw new IllegalArgumentException("Hanging fixture would overwrite geometry at "+new Vec3i(at.x(),y,at.z()));
        named(id);
        if(anchor.y()>at.y()+1)canvas().pen(Brush.solid(chain)).fill(Box.of(at.x(),at.y()+1,at.z(),at.x(),anchor.y()-1,at.z()));
        canvas().pen(Brush.solid(light)).set(at.x(),at.y(),at.z());lights.add(Map.of("at",AuthoredStructure.point(at),"level",level));
        hangingFixtures.add(Map.of("id",id,"at",AuthoredStructure.point(at),"anchor",AuthoredStructure.point(anchor),"light",light.id(),"chain",chain.id()));return this;
    }
    private void requireFixtureAnchor(Vec3i anchor){
        var state=canvas().get(anchor).map(DrawBlock::state).orElseThrow(()->new IllegalArgumentException("Hanging fixture anchor is KEEP at "+anchor));
        if(state.isAir()||Set.of("minecraft:water","minecraft:lava","minecraft:bubble_column","minecraft:powder_snow","minecraft:lantern","minecraft:soul_lantern","minecraft:chain").contains(state.id())||state.id().endsWith("_chain"))throw new IllegalArgumentException("Hanging fixture needs a real roof/beam block at "+anchor);
    }
    private void validateHangingFixtures(){
        for(var fixture:hangingFixtures){
            var at=point((Map<?,?>)fixture.get("at"));var anchor=point((Map<?,?>)fixture.get("anchor"));requireFixtureAnchor(anchor);
            var light=canvas().get(at).map(DrawBlock::state).orElse(null);
            if(light==null||!light.id().equals(fixture.get("light"))||!"true".equals(light.properties().get("hanging")))throw new IllegalArgumentException("Hanging fixture was overwritten at "+at);
            for(int y=at.y()+1;y<anchor.y();y++){
                var chain=canvas().get(new Vec3i(at.x(),y,at.z())).map(DrawBlock::state).orElse(null);
                if(chain==null||!chain.id().equals(fixture.get("chain"))||!"y".equals(chain.properties().get("axis")))throw new IllegalArgumentException("Hanging fixture has a broken vertical chain at "+new Vec3i(at.x(),y,at.z()));
            }
        }
    }
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
        if(child.semantics().containsKey("lightingIntent")&&lightingIntent==null)throw new IllegalArgumentException("A deliberately dark component requires explicit parent intentionallyDark intent; keep mixed atmospheres in separate logical buildings");
        named(id);canvas().pen("air").paste(child.drawing(),transform,true);
        for(var entry:child.components().entrySet())components.put(id+"/"+entry.getKey(),transform.apply(entry.getValue()));
        components.put(id,transform.apply(child.drawing().bounds()));
        var m=child.semantics();append(rooms,m,"rooms",transform,id);append(passages,m,"indoorPassages",transform,id);append(lights,m,"sources",transform,id);append(ports,m,"ports",transform,id);append(hangingFixtures,m,"hangingFixtures",transform,id);
        append(entrances,m,"entrances",transform,id);append(destinations,m,"destinations",transform,id);append(supports,m,"supports",transform,id);append(protectedAreas,m,"protectedAreas",transform,id);append(clearance,m,"keepClear",transform,id);append(interactions,m,"interactions",transform,id);
        if(m.get("palette") instanceof Map<?,?> p)p.forEach((k,v)->{Object previous=palette.putIfAbsent((String)k,v);if(previous!=null&&!previous.equals(v))throw new IllegalArgumentException("Conflicting component material "+k);});
        return this;
    }
    public AuthoredStructure snapshot(){
        validateHangingFixtures();
        var m=new TreeMap<String,Object>();m.put("origin",AuthoredStructure.point(origin));m.put("rooms",rooms);m.put("indoorPassages",passages);m.put("sources",lights);m.put("ports",ports);m.put("entrances",entrances);m.put("destinations",destinations);m.put("supports",supports);m.put("protectedAreas",protectedAreas);m.put("keepClear",clearance);m.put("interactions",interactions);m.put("palette",palette);
        if(lightingIntent!=null)m.put("lightingIntent",lightingIntent);
        if(!hangingFixtures.isEmpty())m.put("hangingFixtures",hangingFixtures);
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
