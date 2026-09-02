package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.MaterialSelector;
import com.wjz.worldsmith.core.model.WeightedMaterial;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider;

/**
 * Resolves the core module's semantic material selectors against the live block
 * registry.
 *
 * <p>This is the only place a generated block identifier is checked, which is
 * deliberate: the core module never sees a registry, so a hallucinated block id
 * cannot be caught until here. Unresolved selectors fall back to a caller
 * supplied block and are reported rather than thrown, so one bad id does not
 * take down a whole world.
 *
 * <p>Stage one resolves {@code preferredIds} only. Tag-based selection needs a
 * registry lookup that is not available while bootstrapping dynamic registries,
 * so {@code requiredTags} is recorded as unresolved for now.
 */
public final class MaterialResolver {
	private final List<String> problems = new ArrayList<>();

	public BlockState resolve(MaterialSelector selector, Block fallback) {
		if (!selector.getWeighted().isEmpty()) {
			// A weighted selector has no ids of its own. Treat its first entry as
			// the representative state for callers that genuinely need one rather
			// than reporting a false fallback against the empty outer selector.
			return this.resolve(selector.getWeighted().getFirst().getMaterial(), fallback);
		}
		for (String id : selector.getPreferredIds()) {
			Identifier parsed = Identifier.tryParse(id);
			if (parsed == null) {
				this.problems.add("'" + id + "' is not a valid identifier (role " + selector.getSemanticRole() + ")");
				continue;
			}
			Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(parsed);
			if (block.isPresent()) {
				return block.get().defaultBlockState();
			}
			this.problems.add("'" + id + "' is not a registered block (role " + selector.getSemanticRole() + ")");
		}

		if (!selector.getRequiredTags().isEmpty()) {
			this.problems.add("tag-only selector for role " + selector.getSemanticRole() + " is not supported in stage one");
		} else if (selector.getPreferredIds().isEmpty()) {
			this.problems.add("selector for role " + selector.getSemanticRole() + " lists no preferred ids");
		}

		return fallback.defaultBlockState();
	}

	/**
	 * The same selector as a provider, so a role can hold several blocks.
	 *
	 * <p>Minecraft has no palette object: a feature configuration names its own
	 * material fields and each takes a {@link BlockStateProvider}, which is where
	 * "one role, several blocks" actually lives. A plain selector becomes a
	 * simple provider and a weighted one becomes a weighted provider, so a patch
	 * of meadow flora can be mostly grass with a scattering of flowers instead of
	 * a field of one plant.
	 */
	public BlockStateProvider resolveProvider(MaterialSelector selector, Block fallback) {
		return this.resolveMaterial(selector, fallback).provider();
	}

	/**
	 * Resolves both representations a compiler may need from one material.
	 *
	 * <p>A tree needs the provider to place its blocks and every resolved state
	 * to verify that all weighted trunk alternatives are usable wood. Returning
	 * both from one pass avoids duplicate fallback reports and prevents a valid
	 * weighted selector from being checked as though its empty outer shell were
	 * a material of its own.
	 */
	public ResolvedMaterial resolveMaterial(MaterialSelector selector, Block fallback) {
		if (selector.getWeighted().isEmpty()) {
			BlockState state = this.resolve(selector, fallback);
			return new ResolvedMaterial(BlockStateProvider.simple(state), List.of(state));
		}

		WeightedList.Builder<BlockState> entries = WeightedList.builder();
		List<BlockState> states = new ArrayList<>();
		for (WeightedMaterial entry : selector.getWeighted()) {
			BlockState state = this.resolve(entry.getMaterial(), fallback);
			entries.add(state, entry.getWeight());
			states.add(state);
		}
		return new ResolvedMaterial(new WeightedStateProvider(entries.build()), List.copyOf(states));
	}

	public record ResolvedMaterial(BlockStateProvider provider, List<BlockState> states) {
		public BlockState representativeState() {
			return this.states.getFirst();
		}
	}

	/** Human-readable descriptions of every selector that had to fall back. */
	public List<String> problems() {
		return List.copyOf(this.problems);
	}

	/**
	 * Logs every fallback under a scope name. Unresolved materials are a warning
	 * rather than a failure so that one bad identifier degrades a single block
	 * instead of aborting world generation.
	 */
	public void report(String scope) {
		for (String problem : this.problems) {
			Worldsmith.LOGGER.warn("Material fallback in {}: {}", scope, problem);
		}
	}
}
