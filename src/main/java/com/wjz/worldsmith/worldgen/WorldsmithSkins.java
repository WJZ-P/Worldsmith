package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.BiomeSkinSet;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import com.wjz.worldsmith.core.validation.BiomeSkinValidator;
import com.wjz.worldsmith.core.validation.Diagnostic;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Loads the biome skin set the compiler dresses the skeletons with.
 *
 * <p>Stage one reads a checked-in fixture instead of calling a language model.
 * Proving that a skin set turns into a playable world is a separate problem from
 * proving that a model can write one, and mixing the two makes every failure
 * ambiguous. The model gets wired to this same entry point in stage two.
 */
public final class WorldsmithSkins {
	private static final String FIXTURE = "/fixtures/stage1_ashlands.json";

	private WorldsmithSkins() {
	}

	public static BiomeSkinSet load() {
		String json;
		try (InputStream stream = WorldsmithSkins.class.getResourceAsStream(FIXTURE)) {
			if (stream == null) {
				throw new IllegalStateException("Biome skin fixture '" + FIXTURE + "' is missing from the mod jar");
			}
			json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read biome skin fixture '" + FIXTURE + "'", e);
		}

		BiomeSkinSet skins = WorldsmithJson.INSTANCE.getFormat()
			.decodeFromString(BiomeSkinSet.Companion.serializer(), json);

		List<Diagnostic> diagnostics = BiomeSkinValidator.INSTANCE.validate(skins);
		if (!diagnostics.isEmpty()) {
			String detail = diagnostics.stream()
				.map(d -> d.getPath() + " " + d.getCode() + ": " + d.getMessage())
				.collect(Collectors.joining("\n  "));
			throw new IllegalStateException("Biome skin fixture is invalid:\n  " + detail);
		}
		return skins;
	}
}
