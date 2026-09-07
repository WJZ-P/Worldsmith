package com.wjz.worldsmith.core.draw;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Versioned palette/RLE data, not Java serialization. KEEP is omitted; AIR is a real palette entry. */
public final class DrawSnapshotCodec {
	public static final int VERSION = 1;
	public static final String SDK_VERSION = "draw-1";
	public static final int MAX_BYTES = 64 * 1024 * 1024;
	private static final int MAGIC = 0x57534452;
	private DrawSnapshotCodec() {}
	private record Run(long start, int length, int state) {}
	public static byte[] encode(DrawStructure drawing) throws IOException {
		if (drawing.voxels().size() > DrawLimits.DEFAULT.maxAuthoredCells()) throw new IOException("Drawing cell budget exceeded");
		var palette = new LinkedHashMap<DrawBlock, Integer>(); var runs = new ArrayList<Run>();
		Box bounds = drawing.bounds(); long previous = -2; int state = -1, length = 0; long first = 0;
		for (var voxel : drawing.voxels()) {
			int index = palette.computeIfAbsent(voxel.block(), k -> palette.size());
			if (palette.size() > 4096) throw new IOException("Drawing palette exceeds 4096 states");
			var p = voxel.position();
			long key = Math.addExact(Math.multiplyExact(Math.addExact(Math.multiplyExact((long)p.y()-bounds.min().y(), bounds.depth()), (long)p.z()-bounds.min().z()), bounds.width()), (long)p.x()-bounds.min().x());
			if (key == previous + 1 && index == state) length++;
			else { if (length > 0) runs.add(new Run(first, length, state)); first = key; length = 1; state = index; }
			previous = key;
		}
		if (length > 0) runs.add(new Run(first, length, state));
		if (drawing.anchors().size() > 4096) throw new IOException("Drawing anchor budget exceeded");
		var bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
			out.writeInt(MAGIC); out.writeInt(VERSION); point(out, bounds.min()); point(out, bounds.max());
			out.writeInt(palette.size());
			for (var block : palette.keySet()) {
				out.writeUTF(block.state().id()); out.writeInt(block.state().properties().size());
				for (var e : block.state().properties().entrySet()) { out.writeUTF(e.getKey()); out.writeUTF(e.getValue()); }
				out.writeByte(block.orientation().quarterTurns()); out.writeBoolean(block.orientation().mirrorX());
			}
			out.writeInt(drawing.voxels().size()); out.writeInt(runs.size());
			for (var run : runs) { out.writeLong(run.start); out.writeInt(run.length); out.writeInt(run.state); }
			out.writeInt(drawing.anchors().size());
			for (var e : drawing.anchors().entrySet()) { out.writeUTF(e.getKey()); point(out, e.getValue()); }
		}
		if (bytes.size() > MAX_BYTES) throw new IOException("Compressed drawing exceeds size budget");
		return bytes.toByteArray();
	}
	public static DrawStructure decode(byte[] bytes) throws IOException {
		if (bytes.length > MAX_BYTES) throw new IOException("Compressed drawing exceeds size budget");
		try (var in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
			if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported drawing format");
			var bounds = new Box(point(in), point(in)); long volume = bounds.volume();
			int count = bounded(in.readInt(), 0, 4096, "palette"); var palette = new ArrayList<DrawBlock>(count);
			for (int i = 0; i < count; i++) {
				String id = in.readUTF(); int n = bounded(in.readInt(), 0, 32, "properties"); var props = new TreeMap<String,String>();
				for (int p = 0; p < n; p++) if (props.putIfAbsent(in.readUTF(), in.readUTF()) != null) throw new IOException("Duplicate property");
				int turns = bounded(in.readUnsignedByte(), 0, 3, "rotation");
				palette.add(new DrawBlock(new BlockStateRef(id, props), new GridTransform(turns, in.readBoolean(), Vec3i.ZERO)));
			}
			int cells = bounded(in.readInt(), 0, DrawLimits.DEFAULT.maxAuthoredCells(), "cells");
			int runCount = bounded(in.readInt(), 0, cells, "runs"); var voxels = new ArrayList<DrawVoxel>(cells); long last = -1;
			for (int i = 0; i < runCount; i++) {
				long start = in.readLong(); int n = bounded(in.readInt(), 1, cells - voxels.size(), "run length");
				int index = bounded(in.readInt(), 0, palette.size()-1, "palette index");
				if (start <= last || start < 0 || start >= volume || n > volume - start) throw new IOException("Outside or overlapping drawing run");
				for (long k = start; k < start + n; k++) {
					int x = (int)(k % bounds.width()); long yz = k / bounds.width();
					voxels.add(new DrawVoxel(bounds.min().add(x, (int)(yz / bounds.depth()), (int)(yz % bounds.depth())), palette.get(index)));
				}
				last = start + n - 1;
			}
			if (voxels.size() != cells) throw new IOException("Drawing cell count mismatch");
			int anchors = bounded(in.readInt(), 0, 4096, "anchors"); var names = new TreeMap<String,Vec3i>();
			for (int i=0;i<anchors;i++) if (names.putIfAbsent(in.readUTF(), point(in)) != null) throw new IOException("Duplicate anchor");
			if (in.read() != -1) throw new IOException("Trailing drawing payload");
			return new DrawStructure(bounds, voxels, names);
		} catch (IllegalArgumentException | ArithmeticException e) { throw new IOException("Invalid drawing: " + e.getMessage(), e); }
	}
	public static DrawStructure read(Path file) throws IOException {
		if (Files.size(file) > MAX_BYTES) throw new IOException("Drawing file exceeds size budget");
		return decode(Files.readAllBytes(file));
	}
	public static String hash(byte[] bytes) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
	}
	private static int bounded(int n, int min, int max, String name) throws IOException {
		if (n < min || n > max) throw new IOException("Invalid " + name + ": " + n); return n;
	}
	private static void point(DataOutput out, Vec3i p) throws IOException { out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.z()); }
	private static Vec3i point(DataInput in) throws IOException { return new Vec3i(in.readInt(), in.readInt(), in.readInt()); }
}
