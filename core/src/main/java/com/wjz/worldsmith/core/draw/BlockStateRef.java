package com.wjz.worldsmith.core.draw;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Symbolic state. The Minecraft exporter, not Core, validates block/property existence. */
public record BlockStateRef(String id, Map<String, String> properties) {
	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
	private static final Pattern WORD = Pattern.compile("[a-z0-9_]+(?:[.-][a-z0-9_]+)*");
	public static final BlockStateRef AIR = of("minecraft:air");
	public BlockStateRef {
		Objects.requireNonNull(id); Objects.requireNonNull(properties);
		if (!id.contains(":")) id = "minecraft:" + id;
		if (id.length() > 256 || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid block identifier: " + id);
		if (properties.size() > 32) throw new IllegalArgumentException("Too many block properties");
		var copy = new TreeMap<String, String>();
		properties.forEach((k, v) -> {
			if (k == null || v == null || k.length() > 64 || v.length() > 64 || !WORD.matcher(k).matches() || !WORD.matcher(v).matches())
				throw new IllegalArgumentException("Invalid block property");
			copy.put(k, v);
		});
		properties = Collections.unmodifiableMap(copy);
	}
	public static BlockStateRef of(String id) { return new BlockStateRef(id, Map.of()); }
	/** Accepts vanilla-style block[state=value,...] text without resolving a registry. */
	public static BlockStateRef parse(String text) {
		Objects.requireNonNull(text); text = text.trim(); int open = text.indexOf('[');
		if (open < 0) return of(text);
		if (!text.endsWith("]") || text.indexOf('[', open + 1) >= 0) throw new IllegalArgumentException("Malformed block-state text");
		var values = new TreeMap<String, String>(); String body = text.substring(open + 1, text.length() - 1);
		if (!body.isBlank()) for (String entry : body.split(",", -1)) {
			String[] pair = entry.split("=", -1);
			if (pair.length != 2 || values.putIfAbsent(pair[0].trim(), pair[1].trim()) != null)
				throw new IllegalArgumentException("Malformed or duplicate block property");
		}
		return new BlockStateRef(text.substring(0, open).trim(), values);
	}
	public BlockStateRef with(String key, String value) {
		var copy = new TreeMap<>(properties); copy.put(key, value); return new BlockStateRef(id, copy);
	}
	public boolean isAir() { return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air"); }
	public String asString() {
		return properties.isEmpty() ? id : id + "[" + String.join(",", properties.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()) + "]";
	}
}
