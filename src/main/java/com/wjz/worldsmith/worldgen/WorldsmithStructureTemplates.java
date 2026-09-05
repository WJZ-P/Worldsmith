package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.structure.BuildMaterial;
import com.wjz.worldsmith.core.structure.CompiledStructure;
import com.wjz.worldsmith.core.structure.StructureGeometryCompiler;
import com.wjz.worldsmith.core.structure.StructureVoxel;
import com.wjz.worldsmith.core.structure.StructureInteraction;
import com.google.gson.GsonBuilder;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.storage.loot.LootTable;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import com.google.common.hash.Hashing;
import net.minecraft.data.CachedOutput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** The only boundary turning symbolic structure cells into target-version block states/NBT. */
public final class WorldsmithStructureTemplates {
    private WorldsmithStructureTemplates() {}

    public static BlockState resolve(BuildMaterial material) {
        Identifier id = Identifier.tryParse(material.getBlock());
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null) throw new IllegalArgumentException("Unknown structure block '" + material.getBlock() + "'");
        if (block == Blocks.STRUCTURE_BLOCK || block == Blocks.JIGSAW || block == Blocks.STRUCTURE_VOID) {
            throw new IllegalArgumentException("Use construction/KEEP operations rather than template control block " + material.getBlock());
        }
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> entry : material.getProperties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException(material.getBlock() + " has no property '" + entry.getKey() + "'");
            state = setProperty(state, property, entry.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> property, String value) {
        T parsed = property.getValue(value).orElseThrow(() -> new IllegalArgumentException(
            "Invalid " + property.getName() + "=" + value + " for " + state.getBlock()));
        return state.setValue(property, parsed);
    }

    public static CompoundTag encode(CompiledStructure geometry) {
        return encode(geometry,null,null);
    }

    public static CompoundTag encode(CompiledStructure geometry,HolderLookup.Provider registries,CompiledPack pack) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.put("size", ints(geometry.getSize().getX(), geometry.getSize().getY(), geometry.getSize().getZ()));
        root.put("entities", new ListTag());
        ListTag palette = new ListTag();
        ListTag blocks = new ListTag();
        Map<BlockState, Integer> ids = new LinkedHashMap<>();
        Map<BuildMaterial, BlockState> states = new LinkedHashMap<>();
        Map<com.wjz.worldsmith.core.structure.BuildPos,Integer> interactions=new LinkedHashMap<>();
        for(int i=0;i<geometry.getInteractions().size();i++)interactions.put(geometry.getInteractions().get(i).getAt(),i);
        for (StructureVoxel voxel : geometry.getVoxels()) {
            BlockState state = states.computeIfAbsent(voxel.getMaterial(), WorldsmithStructureTemplates::resolve)
                .rotate(Rotation.values()[voxel.getQuarterTurns()]);
            int index = ids.computeIfAbsent(state, key -> { int next = ids.size(); palette.add(NbtUtils.writeBlockState(key)); return next; });
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(voxel.getPosition().getX(), voxel.getPosition().getY(), voxel.getPosition().getZ()));
            block.putInt("state", index);
            Integer interaction=interactions.get(voxel.getPosition());
            if(interaction!=null) {
                if(registries==null)throw new IllegalArgumentException("Block entity content requires registry-aware export");
                var pos=voxel.getPosition();var payload=geometry.getInteractions().get(interaction);
                block.put("nbt",WorldsmithStructureInteractions.encode(payload,state,new net.minecraft.core.BlockPos(pos.getX(),pos.getY(),pos.getZ()),registries,
                    pack==null?null:pack.structureLootId(geometry.getId(),interaction)));
            }
            blocks.add(block);
        }
        root.put("palette", palette);
        root.put("blocks", blocks);
        // Exercise the same reader Minecraft uses before a resource is published.
        StructureTemplate template = new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK, root);
        return template.save(new CompoundTag());
    }

    public static int write(CompiledPack pack, HolderLookup.Provider registries, Path root, CachedOutput cache) throws IOException {
        int count = 0;
        for(var entry:pack.structures().getTemplates().entrySet())for(int variant=0;variant<entry.getValue().size();variant++) {
            var geometry=entry.getValue().get(variant);
            Identifier id=pack.structureTemplateId(entry.getKey(),variant);
            Path target = root.resolve("data").resolve(id.getNamespace()).resolve("structure").resolve(id.getPath() + ".nbt");
            Files.createDirectories(target.getParent());
            var tag=encode(geometry,registries,pack);
            if(cache==null) {
                NbtIo.writeCompressed(tag, target);
            } else {
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                NbtIo.writeCompressed(tag,buffer);
                byte[] bytes=buffer.toByteArray();
                cache.writeIfNeeded(target,bytes,Hashing.sha1().hashBytes(bytes));
            }
            count++;
        }
        for(var blueprint:pack.structures().getBlueprints().values())for(int i=0;i<blueprint.getInteractions().size();i++) {
            if(!(blueprint.getInteractions().get(i) instanceof StructureInteraction.Container container)||container.getLoot()==null)continue;
            var table=WorldsmithStructureInteractions.loot(container.getLoot());
            var ops=registries.createSerializationContext(JsonOps.INSTANCE);
            var json=LootTable.DIRECT_CODEC.encodeStart(ops,table).getOrThrow();
            LootTable.DIRECT_CODEC.parse(ops,json).getOrThrow();
            Identifier id=pack.structureLootId(blueprint.getId(),i);
            Path path=root.resolve("data").resolve(id.getNamespace()).resolve("loot_table").resolve(id.getPath()+".json");
            byte[] bytes=new GsonBuilder().setPrettyPrinting().create().toJson(json).getBytes(StandardCharsets.UTF_8);
            if(cache==null){Files.createDirectories(path.getParent());Files.write(path,bytes);}else cache.writeIfNeeded(path,bytes,Hashing.sha1().hashBytes(bytes));
            count++;
        }
        return count;
    }

    private static ListTag ints(int... values) {
        ListTag list = new ListTag();
        for (int value : values) list.add(IntTag.valueOf(value));
        return list;
    }
}
