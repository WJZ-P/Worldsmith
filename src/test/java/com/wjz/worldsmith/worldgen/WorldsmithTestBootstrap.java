package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;

/** Reproduces Fabric's registry-registration window for plain JVM tests. */
public final class WorldsmithTestBootstrap {
	private static boolean bootstrapped;
	private static boolean customBlocksBootstrapped;
	private static boolean customItemsBootstrapped;

	private WorldsmithTestBootstrap() {
	}

	/** Reopens only the native block/item registration window for direct HostBlock state tests. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static synchronized void bootStrapCustomBlocks() {
		bootStrap();
		if (customBlocksBootstrapped) return;
		try {
			Field frozen = MappedRegistry.class.getDeclaredField("frozen"); frozen.setAccessible(true);
			Field intrusive = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders"); intrusive.setAccessible(true);
			MappedRegistry<?> blocks = (MappedRegistry<?>)BuiltInRegistries.BLOCK, items = (MappedRegistry<?>)BuiltInRegistries.ITEM;
			Object oldBlocks = intrusive.get(blocks), oldItems = intrusive.get(items);
			frozen.setBoolean(blocks, false); frozen.setBoolean(items, false);
			intrusive.set(blocks, new java.util.IdentityHashMap<>()); intrusive.set(items, new java.util.IdentityHashMap<>());
			try {
				com.wjz.worldsmith.content.WorldsmithCustomBlocks.initialize();
				Method tags = Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class); tags.setAccessible(true);
				for (var block : com.wjz.worldsmith.content.WorldsmithCustomBlocks.hosts().values()) {
					tags.invoke(block.builtInRegistryHolder(), java.util.List.of());
					tags.invoke(block.asItem().builtInRegistryHolder(), java.util.List.of());
				}
			} finally {
				frozen.setBoolean(blocks, true); frozen.setBoolean(items, true);
				intrusive.set(blocks, oldBlocks); intrusive.set(items, oldItems);
			}
			customBlocksBootstrapped = true;
		} catch (ReflectiveOperationException failure) { throw new IllegalStateException("Could not register native custom block hosts in test bootstrap", failure); }
	}

	/** Registers the shared world-item host, its component and recipe serializers, as Fabric init would. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static synchronized void bootStrapCustomItems() {
		bootStrap();
		if (customItemsBootstrapped) return;
		try {
			Field frozen = MappedRegistry.class.getDeclaredField("frozen"); frozen.setAccessible(true);
			Field intrusive = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders"); intrusive.setAccessible(true);
			MappedRegistry<?> items = (MappedRegistry<?>)BuiltInRegistries.ITEM, entities = (MappedRegistry<?>)BuiltInRegistries.ENTITY_TYPE;
			List<MappedRegistry<?>> opened = List.of(items, entities, (MappedRegistry<?>)BuiltInRegistries.DATA_COMPONENT_TYPE, (MappedRegistry<?>)BuiltInRegistries.RECIPE_SERIALIZER);
			Object oldItems = intrusive.get(items), oldEntities = intrusive.get(entities);
			for (var registry : opened) frozen.setBoolean(registry, false);
			intrusive.set(items, new java.util.IdentityHashMap<>()); intrusive.set(entities, new java.util.IdentityHashMap<>());
			try {
				com.wjz.worldsmith.content.item.CustomItemRuntime.register();
				Method tags = Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class); tags.setAccessible(true);
				Method bindValue = Holder.Reference.class.getDeclaredMethod("bindValue", Object.class); bindValue.setAccessible(true);
				tags.invoke(com.wjz.worldsmith.content.item.CustomItemRuntime.host().builtInRegistryHolder(), List.of());
				tags.invoke(com.wjz.worldsmith.content.item.ItemAbilityProjectile.type().builtInRegistryHolder(), List.of());
				// Stand-alone holders of an already-frozen registry are not bound by register itself.
				Map<ResourceKey<?>, Object> values = Map.of(
					ResourceKey.create(Registries.DATA_COMPONENT_TYPE, Worldsmith.id("item_identity")), com.wjz.worldsmith.content.item.CustomItemRuntime.identityComponent(),
					ResourceKey.create(Registries.RECIPE_SERIALIZER, Worldsmith.id("world_shaped")), com.wjz.worldsmith.content.item.WorldItemRecipes.SHAPED,
					ResourceKey.create(Registries.RECIPE_SERIALIZER, Worldsmith.id("world_shapeless")), com.wjz.worldsmith.content.item.WorldItemRecipes.SHAPELESS,
					ResourceKey.create(Registries.RECIPE_SERIALIZER, Worldsmith.id("world_smelting")), com.wjz.worldsmith.content.item.WorldItemRecipes.SMELTING,
					ResourceKey.create(Registries.RECIPE_SERIALIZER, Worldsmith.id("world_blasting")), com.wjz.worldsmith.content.item.WorldItemRecipes.BLASTING,
					ResourceKey.create(Registries.RECIPE_SERIALIZER, Worldsmith.id("world_smoking")), com.wjz.worldsmith.content.item.WorldItemRecipes.SMOKING,
					ResourceKey.create(Registries.RECIPE_SERIALIZER, Worldsmith.id("world_campfire_cooking")), com.wjz.worldsmith.content.item.WorldItemRecipes.CAMPFIRE);
				for (var entry : values.entrySet()) {
					var registry = entry.getKey().isFor(Registries.DATA_COMPONENT_TYPE) ? BuiltInRegistries.DATA_COMPONENT_TYPE : BuiltInRegistries.RECIPE_SERIALIZER;
					Holder.Reference<?> holder = (Holder.Reference<?>) ((net.minecraft.core.Registry) registry).get((ResourceKey) entry.getKey()).orElseThrow();
					bindValue.invoke(holder, entry.getValue());
					tags.invoke(holder, List.of());
				}
			} finally {
				for (var registry : opened) frozen.setBoolean(registry, true);
				intrusive.set(items, oldItems); intrusive.set(entities, oldEntities);
			}
			// The host's prototype components are baked like every other item's.
			BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(net.minecraft.data.registries.VanillaRegistries.createLookup())
				.forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
			customItemsBootstrapped = true;
		} catch (ReflectiveOperationException failure) { throw new IllegalStateException("Could not register the world item host in test bootstrap", failure); }
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	static synchronized void bootStrap() {
		if (bootstrapped) {
			return;
		}
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		MappedRegistry<?> materialConditions = (MappedRegistry<?>)BuiltInRegistries.MATERIAL_CONDITION;
		MappedRegistry<?> densityFunctions = (MappedRegistry<?>)BuiltInRegistries.DENSITY_FUNCTION_TYPE;
		MappedRegistry<?> trunkPlacers = (MappedRegistry<?>)BuiltInRegistries.TRUNK_PLACER_TYPE;
		MappedRegistry<?> foliagePlacers = (MappedRegistry<?>)BuiltInRegistries.FOLIAGE_PLACER_TYPE;
		MappedRegistry<?> structureTypes = (MappedRegistry<?>)BuiltInRegistries.STRUCTURE_TYPE;
		MappedRegistry<?> structurePieces = (MappedRegistry<?>)BuiltInRegistries.STRUCTURE_PIECE;
		MappedRegistry<?> processors = (MappedRegistry<?>)BuiltInRegistries.STRUCTURE_PROCESSOR;
		MappedRegistry<?> structurePlacements = (MappedRegistry<?>)BuiltInRegistries.STRUCTURE_PLACEMENT;
		MappedRegistry<?> placementModifiers = (MappedRegistry<?>)BuiltInRegistries.PLACEMENT_MODIFIER_TYPE;
		try {
			Field frozen = MappedRegistry.class.getDeclaredField("frozen");
			frozen.setAccessible(true);
			frozen.setBoolean(materialConditions, false);
			frozen.setBoolean(densityFunctions, false);
			frozen.setBoolean(trunkPlacers, false);
			frozen.setBoolean(foliagePlacers, false);
			frozen.setBoolean(placementModifiers, false);
			frozen.setBoolean(structureTypes, false);
			frozen.setBoolean(structurePieces, false);
			frozen.setBoolean(structurePlacements, false);
			frozen.setBoolean(processors, false);
			WorldsmithWorldgen.initialize();
			Method bindValue = Holder.Reference.class.getDeclaredMethod("bindValue", Object.class);
			bindValue.setAccessible(true);
			bind(materialConditions, Registries.MATERIAL_CONDITION, "hydrology",
				WorldsmithHydrologyConditionSource.CODEC, bindValue);
			bind(materialConditions, Registries.MATERIAL_CONDITION, "anchor",
				WorldsmithAnchorConditionSource.CODEC, bindValue);
			bind(densityFunctions, Registries.DENSITY_FUNCTION_TYPE, "anchor_point",
				WorldsmithAnchorFields.Point.CODEC.codec(), bindValue);
			bind(densityFunctions, Registries.DENSITY_FUNCTION_TYPE, "anchor_grid",
				WorldsmithAnchorFields.Grid.CODEC.codec(), bindValue);
			bind(densityFunctions, Registries.DENSITY_FUNCTION_TYPE, "anchor_line",
				WorldsmithAnchorFields.Line.CODEC.codec(), bindValue);
			bind(trunkPlacers, Registries.TRUNK_PLACER_TYPE, "shaped_trunk",
				WorldsmithTreePlacerTypes.trunk(), bindValue);
			bind(foliagePlacers, Registries.FOLIAGE_PLACER_TYPE, "shaped_foliage",
				WorldsmithTreePlacerTypes.foliage(), bindValue);
			bind(placementModifiers, Registries.PLACEMENT_MODIFIER_TYPE, "height_range_filter",
				WorldsmithPlacementModifierTypes.heightRangeFilter(), bindValue);
			bind(placementModifiers, Registries.PLACEMENT_MODIFIER_TYPE, "structure_avoidance",
				WorldsmithPlacementModifierTypes.structureAvoidance(), bindValue);
			bind(structureTypes, Registries.STRUCTURE_TYPE, "template", WorldsmithStructureTypes.template(), bindValue);
			bind(structurePieces, Registries.STRUCTURE_PIECE, "template_piece", WorldsmithStructureTypes.piece(), bindValue);
			bind(structurePieces, Registries.STRUCTURE_PIECE, "road_piece", WorldsmithStructureTypes.roadPiece(), bindValue);
			bind(processors, Registries.STRUCTURE_PROCESSOR, "instance_material", WorldsmithInstanceProcessor.CODEC, bindValue);
			bind(structurePlacements, Registries.STRUCTURE_PLACEMENT, "anchor", WorldsmithStructureTypes.anchorPlacement(), bindValue);
			// Fabric performs the ordinary final freeze after mod registration.
			// The plain test registry was already frozen once, so restore the flag
			// directly instead of rebuilding its already-bound tag set.
			frozen.setBoolean(materialConditions, true);
			frozen.setBoolean(densityFunctions, true);
			frozen.setBoolean(trunkPlacers, true);
			frozen.setBoolean(foliagePlacers, true);
			frozen.setBoolean(placementModifiers, true);
			frozen.setBoolean(structureTypes, true);
			frozen.setBoolean(structurePieces, true);
			frozen.setBoolean(structurePlacements, true);
			frozen.setBoolean(processors, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("Could not open the test registries", failure);
		}
		// 26.2 binds item prototypes after registry bootstrap. Run the native
		// initializers, rather than faking stack sizes with empty component maps.
		BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(net.minecraft.data.registries.VanillaRegistries.createLookup())
			.forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
		bootstrapped = true;
	}

	/**
	 * A registry frozen once will not re-bind its holders, so the value has to be
	 * pushed into the existing reference directly.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void bind(
		MappedRegistry<?> registry,
		ResourceKey<? extends net.minecraft.core.Registry<?>> registryKey,
		String path,
		Object value,
		Method bindValue
	) throws ReflectiveOperationException {
		ResourceKey<?> key = ResourceKey.create((ResourceKey) registryKey, Worldsmith.id(path));
		Holder.Reference<?> holder = (Holder.Reference<?>) registry.get((ResourceKey) key).orElseThrow();
		bindValue.invoke(holder, value);
	}
}
