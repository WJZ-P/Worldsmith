package com.wjz.worldsmith.worldgen;

import com.google.gson.*;
import com.wjz.worldsmith.core.draw.BlockStateRef;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import com.wjz.worldsmith.core.structure.*;
import com.wjz.worldsmith.core.validation.*;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldsmithCustomBlocks;
import com.wjz.worldsmith.core.content.CustomBlockBindings;
import com.wjz.worldsmith.core.content.CustomBlockLibrary;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/** Read-only live registry/preflight adapter. No compiler, chunks, or NBT export. */
public final class WorldsmithAuthoringNativeHost implements StructureNativeHost {
    private final WorldBlockBindings.Resolver customBlocks;
    public WorldsmithAuthoringNativeHost() { this(null); }
    public WorldsmithAuthoringNativeHost(WorldBlockBindings.Resolver customBlocks) { this.customBlocks=customBlocks; }
    @Override public StructureNativeHost forContent(String scope, CustomBlockLibrary blocks) {
        return new WorldsmithAuthoringNativeHost(WorldBlockBindings.resolver(CustomBlockBindings.plan(scope,blocks)));
    }
    @Override public String getIdentity(){return SharedConstants.getCurrentVersion().dataVersion().version()+":"+System.identityHashCode(BuiltInRegistries.BLOCK)+":authoring-check-2:"+
        (customBlocks==null?"native":com.wjz.worldsmith.content.GeneratedBlockResources.sha256(CustomBlockBindings.encode(customBlocks.snapshot()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    @Override public kotlinx.serialization.json.JsonObject query(List<String> ids,String search,int limit){
        if(limit<1||limit>64||ids.size()>64||search.length()>128)throw new IllegalArgumentException("Query supports at most 64 entries and a 128-character search");
        var vocabulary=java.util.stream.Stream.concat(BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString).filter(id->!WorldsmithCustomBlocks.isReservedNativeId(id)),
            customBlocks==null?java.util.stream.Stream.<String>empty():customBlocks.nativeIds().keySet().stream());
        var candidates=ids.isEmpty()?vocabulary.filter(s->s.contains(search)).sorted().toList():ids;
        var entries=new JsonArray();
        for(String requested:candidates.stream().limit(limit).toList()) {
            var item=new JsonObject();item.addProperty("requested",requested);
            try {
                var source=BlockStateRef.parse(requested);var base=resolve(new BuildMaterial(source.id(),Map.of()));
                item.addProperty("id",source.id());item.addProperty("defaultState",base.toString());
                var properties=new JsonObject();boolean logical=source.id().startsWith("worldsmith:content/");
                if(!logical)for(var property:base.getBlock().getStateDefinition().getProperties())properties.add(property.getName(),values(property));item.add("properties",properties);
                item.addProperty("immutableDefinition",logical);
                var state=resolve(new BuildMaterial(source.id(),source.properties()));item.addProperty("state",state.toString());item.addProperty("lightEmission",state.getLightEmission());
            } catch(IllegalArgumentException e){item.addProperty("error",e.getMessage());}
            entries.add(item);
        }
        var result=new JsonObject();result.addProperty("dataVersion",SharedConstants.getCurrentVersion().dataVersion().version());result.addProperty("matched",candidates.size());result.addProperty("more",candidates.size()>limit);result.add("entries",entries);
        return (kotlinx.serialization.json.JsonObject)WorldsmithJson.INSTANCE.getFormat().parseToJsonElement(result.toString());
    }
    private static <T extends Comparable<T>> JsonArray values(Property<T> property){var a=new JsonArray();property.getPossibleValues().forEach(v->a.add(property.getName(v)));return a;}
    @Override public List<Diagnostic> inspect(CompiledStructure geometry){
        var problems=new ArrayList<Diagnostic>();var cells=new HashMap<BlockPos,BlockState>();var resolved=new HashMap<BuildMaterial,BlockState>();var invalid=new HashSet<BuildMaterial>();
        for(var voxel:geometry.getVoxels()) {
            var material=voxel.getMaterial();var p=voxel.getPosition();if(invalid.contains(material))continue;
            try {
                var state=resolved.computeIfAbsent(material,this::resolve).mirror(voxel.getMirrorX()?Mirror.FRONT_BACK:Mirror.NONE).rotate(Rotation.values()[voxel.getQuarterTurns()]);
                cells.put(new BlockPos(p.getX(),p.getY(),p.getZ()),state);
            } catch(IllegalArgumentException e){invalid.add(material);problems.add(problem(geometry,"NATIVE_BLOCK_STATE",p,e.getMessage(),"a registered block and legal property values",material.toString(),"Use worldsmith_query_block_states before changing the source"));}
        }
        if(invalid.isEmpty())for(var entry:cells.entrySet()) {
            var p=entry.getKey();var s=entry.getValue();BlockPos neighbour=null;BlockState expected=null;
            if(s.getBlock() instanceof DoorBlock){boolean lower=s.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER;neighbour=lower?p.above():p.below();expected=s.setValue(DoorBlock.HALF,lower?DoubleBlockHalf.UPPER:DoubleBlockHalf.LOWER);}
            else if(s.getBlock() instanceof BedBlock){boolean foot=s.getValue(BedBlock.PART)==BedPart.FOOT;neighbour=p.relative(foot?s.getValue(BedBlock.FACING):s.getValue(BedBlock.FACING).getOpposite());expected=s.setValue(BedBlock.PART,foot?BedPart.HEAD:BedPart.FOOT);}
            else if(s.getBlock() instanceof DoublePlantBlock){boolean lower=s.getValue(DoublePlantBlock.HALF)==DoubleBlockHalf.LOWER;neighbour=lower?p.above():p.below();expected=s.setValue(DoublePlantBlock.HALF,lower?DoubleBlockHalf.UPPER:DoubleBlockHalf.LOWER);}
            if(expected!=null&&!expected.equals(cells.get(neighbour)))problems.add(problem(geometry,"NATIVE_PAIRED_BLOCK",pos(p),"Paired block is missing or inconsistent",expected+" at "+neighbour,String.valueOf(cells.get(neighbour)),"Draw both halves using the same orientation before storage tiling"));
        }
        if(geometry.getLighting()!=null)for(var source:geometry.getLighting().getSources()) {
            var p=source.getAt();var state=cells.get(new BlockPos(p.getX(),p.getY(),p.getZ()));
            if(state!=null&&state.getLightEmission()<source.getLevel())problems.add(problem(geometry,"NATIVE_LIGHT_EMISSION",p,"Declared source is dimmer in the actual block state",">="+source.getLevel(),Integer.toString(state.getLightEmission()),"Choose a real emitter or repair its lit/powered properties"));
        }
        return List.copyOf(problems);
    }
    private static BuildPos pos(BlockPos p){return new BuildPos(p.getX(),p.getY(),p.getZ());}
    private BlockState resolve(BuildMaterial material){return WorldsmithStructureTemplates.resolve(material,customBlocks);}
    private static Diagnostic problem(CompiledStructure g,String code,BuildPos at,String message,String expected,String actual,String hint){return new Diagnostic("blocks",code,DiagnosticSeverity.ERROR,message,"native",g.getId(),null,at,null,expected,actual,hint,Map.of());}
}
