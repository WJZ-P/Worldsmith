package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.MaterialSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

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
