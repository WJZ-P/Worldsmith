package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.structure.BuildMaterial;
import com.wjz.worldsmith.core.structure.CompiledStructure;
import com.wjz.worldsmith.core.structure.StructureGeometryCompiler;
import com.wjz.worldsmith.core.structure.StructureVoxel;
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
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.put("size", ints(geometry.getSize().getX(), geometry.getSize().getY(), geometry.getSize().getZ()));
        root.put("entities", new ListTag());
        ListTag palette = new ListTag();
        ListTag blocks = new ListTag();
        Map<BlockState, Integer> ids = new LinkedHashMap<>();
        Map<BuildMaterial, BlockState> states = new LinkedHashMap<>();
        for (StructureVoxel voxel : geometry.getVoxels()) {
            BlockState state = states.computeIfAbsent(voxel.getMaterial(), WorldsmithStructureTemplates::resolve)
                .rotate(Rotation.values()[voxel.getQuarterTurns()]);
            int index = ids.computeIfAbsent(state, key -> { int next = ids.size(); palette.add(NbtUtils.writeBlockState(key)); return next; });
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(voxel.getPosition().getX(), voxel.getPosition().getY(), voxel.getPosition().getZ()));
            block.putInt("state", index);
            blocks.add(block);
        }
        root.put("palette", palette);
        root.put("blocks", blocks);
        // Exercise the same reader Minecraft uses before a resource is published.
        StructureTemplate template = new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK, root);
        return template.save(new CompoundTag());
    }

    public static int write(CompiledPack pack, Path root) throws IOException {
        return write(pack, root, null);
    }

    public static int write(CompiledPack pack, Path root, CachedOutput cache) throws IOException {
        int count = 0;
        for (var blueprint : pack.pack().getStructures().getStructures().stream()
            .map(s -> s.getBlueprint()).distinct().toList()) {
            Identifier id = pack.structureTemplateId(blueprint.getId());
            Path target = root.resolve("data").resolve(id.getNamespace()).resolve("structure").resolve(id.getPath() + ".nbt");
            Files.createDirectories(target.getParent());
            var tag=encode(StructureGeometryCompiler.compile(blueprint));
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
        return count;
    }

    private static ListTag ints(int... values) {
        ListTag list = new ListTag();
        for (int value : values) list.add(IntTag.valueOf(value));
        return list;
    }
}
