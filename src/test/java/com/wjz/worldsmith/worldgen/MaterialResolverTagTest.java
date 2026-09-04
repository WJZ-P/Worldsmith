package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.MaterialSelector;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Exercises tag intersection, deterministic choice, and fallback diagnostics. */
@Execution(ExecutionMode.SAME_THREAD)
final class MaterialResolverTagTest {
	private static Method bindTags;
	private final Map<Holder.Reference<Block>, List<TagKey<Block>>> originalTags = new IdentityHashMap<>();

	@BeforeAll
	static void bootstrap() throws ReflectiveOperationException {
		WorldsmithTestBootstrap.bootStrap();
		bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
		bindTags.setAccessible(true);
	}

	@AfterEach
	void restoreEveryMutatedHolder() throws ReflectiveOperationException {
		for (var entry : this.originalTags.entrySet()) {
			bindTags.invoke(entry.getKey(), entry.getValue());
			assertEquals(Set.copyOf(entry.getValue()), entry.getKey().tags().collect(java.util.stream.Collectors.toSet()),
				"temporary block tags leaked out of a test");
		}
		this.originalTags.clear();
	}

	@Test
	void tagOnlySelectionIsStableByNamespacedBlockId() throws ReflectiveOperationException {
		TagKey<Block> tag = tag("test/deterministic_logs");
		this.addTags(Blocks.BIRCH_LOG, tag);
		this.addTags(Blocks.ACACIA_LOG, tag);
		MaterialResolver resolver = new MaterialResolver();

		Block selected = resolver.resolve(selector("logs", List.of(), List.of(tag)), Blocks.STONE).getBlock();

		assertEquals(Blocks.ACACIA_LOG, selected, "lexically first id makes tag-only selection reproducible");
		assertEquals(List.of(), resolver.problems());
	}

	@Test
	void everyRequiredTagIsAnIntersectionAndPreferredIdsRemainFilters() throws ReflectiveOperationException {
		TagKey<Block> pale = tag("test/pale_logs");
		TagKey<Block> cool = tag("test/cool_logs");
		this.addTags(Blocks.ACACIA_LOG, pale);
		this.addTags(Blocks.BIRCH_LOG, pale, cool);
		MaterialResolver resolver = new MaterialResolver();

		Block selected = resolver.resolve(
			selector("pale_wood", List.of("minecraft:oak_log"), List.of(pale, cool)),
			Blocks.STONE
		).getBlock();

		assertEquals(Blocks.BIRCH_LOG, selected);
		assertTrue(
			resolver.problems().stream().anyMatch(problem -> problem.contains("oak_log") && problem.contains("does not satisfy")),
			resolver.problems().toString()
		);
	}

	@Test
	void unknownOrEmptyTagsProduceAnExplicitFallbackDiagnostic() {
		TagKey<Block> absent = tag("test/no_members");
		MaterialResolver resolver = new MaterialResolver();

		Block selected = resolver.resolve(selector("missing", List.of(), List.of(absent)), Blocks.STONE).getBlock();

		assertEquals(Blocks.STONE, selected);
		assertTrue(
			resolver.problems().stream().anyMatch(problem ->
				problem.contains("no registered block satisfies required tags") && problem.contains("test/no_members")
			),
			resolver.problems().toString()
		);
	}

	@SafeVarargs
	private void addTags(Block block, TagKey<Block>... additions) throws ReflectiveOperationException {
		Holder.Reference<Block> holder = block.builtInRegistryHolder();
		List<TagKey<Block>> original = this.originalTags.computeIfAbsent(holder, ignored -> holder.tags().toList());
		List<TagKey<Block>> combined = new ArrayList<>(original);
		for (TagKey<Block> addition : additions) {
			if (!combined.contains(addition)) {
				combined.add(addition);
			}
		}
		bindTags.invoke(holder, combined);
	}

	private static MaterialSelector selector(String role, List<String> ids, List<TagKey<Block>> tags) {
		return new MaterialSelector(role, ids, tags.stream().map(key -> key.location().toString()).toList(), List.of());
	}

	private static TagKey<Block> tag(String path) {
		return TagKey.create(Registries.BLOCK, Worldsmith.id(path));
	}
}
