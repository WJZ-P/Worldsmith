package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.draw.DrawBlock;
import com.wjz.worldsmith.core.draw.DrawStructure;
import com.wjz.worldsmith.core.draw.Vec3i;
import com.wjz.worldsmith.core.structure.BuildMaterial;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Native Java structure-NBT exporter for the version-independent Core drawing SDK. */
public final class WorldsmithDrawExporter {
	private WorldsmithDrawExporter() {}

	/** Requires Minecraft bootstrap/registries, but no running world, Python, commands or chunk access. */
	public static CompoundTag encode(DrawStructure drawing) {
		var bounds = drawing.bounds(); var root = new CompoundTag();
		root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
		root.put("size", ints(bounds.width(), bounds.height(), bounds.depth()));
		root.put("entities", new ListTag());
		var palette = new ListTag(); var blocks = new ListTag();
		Map<DrawBlock, BlockState> resolved = new HashMap<>();
        Map<BlockState, Integer> indices = new LinkedHashMap<>();
        var nativeCells = new HashMap<net.minecraft.core.BlockPos,BlockState>();
		for (var voxel : drawing.voxels()) {
			BlockState state = resolved.computeIfAbsent(voxel.block(), block -> {
				var source = block.state(); var orientation = block.orientation();
				var original = WorldsmithStructureTemplates.resolve(new BuildMaterial(source.id(), source.properties()));
				// Preserve native semantics for stairs, rails, doors, signs and modded block states.
				return original.mirror(orientation.mirrorX() ? Mirror.FRONT_BACK : Mirror.NONE)
					.rotate(Rotation.values()[orientation.quarterTurns()]);
			});
            int index = indices.computeIfAbsent(state, key -> { int next = indices.size(); palette.add(NbtUtils.writeBlockState(key)); return next; });
            var tag = new CompoundTag(); var p = voxel.position();
            nativeCells.put(new net.minecraft.core.BlockPos(p.x(),p.y(),p.z()),state);
			tag.put("pos", ints(Math.subtractExact(p.x(), bounds.min().x()), Math.subtractExact(p.y(), bounds.min().y()), Math.subtractExact(p.z(), bounds.min().z())));
			tag.putInt("state", index); blocks.add(tag);
		}
        root.put("palette", palette); root.put("blocks", blocks);
        WorldsmithNativeGeometryChecks.validate(nativeCells);
		// Read through the actual target version's StructureTemplate before publishing.
		var template = new StructureTemplate(); template.load(BuiltInRegistries.BLOCK, root);
		return template.save(new CompoundTag());
	}

	/** Coordinates are normalized to the drawing's minimum corner. The NBT file is gzip-compressed and replaced atomically where supported. */
	public static ExportResult write(DrawStructure drawing, Path destination) throws IOException {
		CompoundTag encoded = encode(drawing);
		Path target = destination.toAbsolutePath().normalize();
		Files.createDirectories(target.getParent());
		Path temporary = Files.createTempFile(target.getParent(), ".worldsmith-draw-", ".nbt.tmp");
		try {
			NbtIo.writeCompressed(encoded, temporary);
			try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
			catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
		} finally { Files.deleteIfExists(temporary); }
		var b = drawing.bounds();
		return new ExportResult(target, new Vec3i(b.width(), b.height(), b.depth()), drawing.voxels().size(), drawing.nonAirCells());
	}
	public record ExportResult(Path file, Vec3i size, int authoredCells, long nonAirCells) {}
	private static ListTag ints(int... values) {
		var list = new ListTag(); for (int value : values) list.add(IntTag.valueOf(value)); return list;
	}
}
