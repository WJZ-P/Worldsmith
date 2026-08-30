package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader;
import com.wjz.worldsmith.core.validation.Diagnostic;
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator;
import java.util.List;
import java.util.stream.Collectors;

/** Loads portable Worldsmith packs for the Minecraft-facing compiler. */
public final class WorldsmithPacks {
	private static final String BUILTIN_PACK = "worldsmith/packs/ashlands";
	private static final WorldsmithPack BUILTIN = loadBuiltin();
	private static final CompiledPack BUILTIN_COMPILED = CompiledPack.of(BUILTIN);

	private WorldsmithPacks() {
	}

	public static WorldsmithPack builtin() {
		return BUILTIN;
	}

	/** The built-in pack compiled once, which is what datagen writes out. */
	public static CompiledPack builtinCompiled() {
		return BUILTIN_COMPILED;
	}

	private static WorldsmithPack loadBuiltin() {
		WorldsmithPack pack = WorldsmithPackLoader.loadClasspath(BUILTIN_PACK);
		List<Diagnostic> diagnostics = WorldsmithPackValidator.INSTANCE.validate(pack);
		if (!diagnostics.isEmpty()) {
			String detail = diagnostics.stream()
				.map(d -> d.getPath() + " " + d.getCode() + ": " + d.getMessage())
				.collect(Collectors.joining("\n  "));
			throw new IllegalStateException("Built-in Worldsmith pack is invalid:\n  " + detail);
		}
		return pack;
	}
}
