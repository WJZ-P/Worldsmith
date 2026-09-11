package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.structure.StructureInteraction;
import com.wjz.worldsmith.core.structure.StructureLoot;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldRewardItems;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.creature.EncounterBossCreatureEntity;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.InclusiveRange;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.functions.SetComponentsFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;

/** Real MC block entities and codecs, with literal text and a small typed content vocabulary. */
public final class WorldsmithStructureInteractions {
    private WorldsmithStructureInteractions() {}

    public static CompoundTag encode(StructureInteraction spec, BlockState state, BlockPos pos,
        HolderLookup.Provider registries, Identifier inlineLoot) {
        return encode(spec,state,pos,registries,inlineLoot,null);
    }

    public static CompoundTag encode(StructureInteraction spec, BlockState state, BlockPos pos,
        HolderLookup.Provider registries, Identifier inlineLoot, WorldBlockBindings.Resolver customBlocks) {
        return encode(spec,state,pos,registries,inlineLoot,customBlocks,null);
    }

    public static CompoundTag encode(StructureInteraction spec, BlockState state, BlockPos pos,
        HolderLookup.Provider registries, Identifier inlineLoot, WorldBlockBindings.Resolver customBlocks, CustomItemRuntime.Snapshot customItems) {
        return encode(spec,state,pos,registries,inlineLoot,customBlocks,customItems,null);
    }

    public static CompoundTag encode(StructureInteraction spec, BlockState state, BlockPos pos,
        HolderLookup.Provider registries, Identifier inlineLoot, WorldBlockBindings.Resolver customBlocks,
        CustomItemRuntime.Snapshot customItems, CreatureRuntime.Snapshot creatures) {
        if(!(state.getBlock() instanceof EntityBlock factory))throw new IllegalArgumentException("Interaction target is not a block entity: "+state);
        BlockEntity entity=factory.newBlockEntity(pos,state);
        if(entity==null)throw new IllegalArgumentException("Block entity factory returned no entity");
        ProblemReporter.Collector reporter=new ProblemReporter.Collector();
        var output=TagValueOutput.createWithContext(reporter,registries);
        Map<Integer,ItemStack> expectedItems=new LinkedHashMap<>();
        if(spec instanceof StructureInteraction.BossSpawner encounter) {
            if(!state.is(Blocks.SPAWNER) || !(entity instanceof SpawnerBlockEntity spawner))throw new IllegalArgumentException("Boss encounters require a native spawner block");
            if(creatures==null)throw new IllegalArgumentException("Boss spawner export requires an immutable world creature snapshot");
            var definition=creatures.definitions().get(encounter.getCreatureId());
            if(definition==null || definition.getBoss()==null || definition.getCategory()!=com.wjz.worldsmith.core.content.CreatureCategory.HOSTILE)
                throw new IllegalArgumentException("Boss spawner references a missing/non-Boss creature: "+encounter.getCreatureId());
            if(encounter.getRespawnTicks()<200||encounter.getRespawnTicks()>30000||encounter.getRequiredPlayerRange()<8||encounter.getRequiredPlayerRange()>32||encounter.getSpawnRange()<1||encounter.getSpawnRange()>8)
                throw new IllegalArgumentException("Boss spawner exceeds bounded native delay/player/spawn ranges");
            if(customItems!=null&&!customItems.bundleHash().equals(creatures.bundleHash()) || customBlocks!=null&&!customBlocks.snapshot().getScope().equals(creatures.bundleHash()))
                throw new IllegalArgumentException("Boss spawner and reward resolvers must belong to the same immutable world");
            CompoundTag spawnEntity=new CompoundTag();
            spawnEntity.putString("id",CreatureRuntime.ENCOUNTER_BOSS_ID);
            spawnEntity.putString("WorldsmithBundle",creatures.bundleHash());
            spawnEntity.putString("WorldsmithCreature",definition.getId());
            spawnEntity.putBoolean(EncounterBossCreatureEntity.INITIALIZE_TAG,true);
            // Full light range is intentional: a landmark route does not depend on natural biome/light selection.
            // Native peaceful-mode and actual initialized-body collision checks still apply.
            var light=new InclusiveRange<>(0,15);
            var spawnData=new SpawnData(spawnEntity,Optional.of(new SpawnData.CustomSpawnRules(light,light)),Optional.empty());
            var settings=TagValueOutput.createWithContext(reporter,registries);
            settings.putShort("Delay",(short)20);
            settings.putInt("MinSpawnDelay",encounter.getRespawnTicks());settings.putInt("MaxSpawnDelay",encounter.getRespawnTicks());
            settings.putInt("SpawnCount",1);settings.putInt("MaxNearbyEntities",1);
            settings.putInt("RequiredPlayerRange",encounter.getRequiredPlayerRange());settings.putInt("SpawnRange",encounter.getSpawnRange());
            settings.store("SpawnData",SpawnData.CODEC,spawnData);
            settings.store("SpawnPotentials",SpawnData.LIST_CODEC,WeightedList.of(spawnData));
            spawner.getSpawner().load(null,pos,TagValueInput.create(reporter,registries,settings.buildResult()));
        } else if(spec instanceof StructureInteraction.Container contents) {
            if(!(entity instanceof Container inventory))throw new IllegalArgumentException("Container content targets a non-container block");
            if(contents.getLootTable()!=null || contents.getLoot()!=null) {
                if(!(entity instanceof RandomizableContainer randomizable))throw new IllegalArgumentException("This container does not support loot tables");
                Identifier id=contents.getLoot()!=null?inlineLoot:Identifier.parse(contents.getLootTable());
                if(id==null)throw new IllegalArgumentException("Inline loot requires an exported table id");
                randomizable.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,id),0L);
            } else for(var item:contents.getItems()) {
                var stack=WorldRewardItems.stack(item.getItem(),item.getCount(),customBlocks,customItems);
                if(item.getSlot()<0||item.getSlot()>=inventory.getContainerSize()||item.getCount()>stack.getMaxStackSize())throw new IllegalArgumentException("Item exceeds the actual container capacity or stack limit");
                inventory.setItem(item.getSlot(),stack);
                expectedItems.put(item.getSlot(),stack.copy());
            }
        }
        entity.saveWithFullMetadata(output);
        if(spec instanceof StructureInteraction.Sign sign) {
            if(!(entity instanceof SignBlockEntity))throw new IllegalArgumentException("Sign text targets a non-sign block");
            // setText expects a live level. Codecs prepare the two sides without
            // invoking that UI path before a world exists.
            output.store("front_text",SignText.DIRECT_CODEC,text(sign.getFront(),sign.getColor(),sign.getGlowing()));
            output.store("back_text",SignText.DIRECT_CODEC,text(sign.getBack(),sign.getColor(),sign.getGlowing()));
        } else if(spec instanceof StructureInteraction.Banner banner) {
            if(!(entity instanceof BannerBlockEntity))throw new IllegalArgumentException("Banner patterns target a non-banner block");
            var builder=new BannerPatternLayers.Builder();
            for(var layer:banner.getPatterns())builder.add(registries.lookupOrThrow(Registries.BANNER_PATTERN)
                .getOrThrow(ResourceKey.create(Registries.BANNER_PATTERN,Identifier.parse(layer.getPattern()))),color(layer.getColor()));
            output.store("patterns",BannerPatternLayers.CODEC,builder.build());
        }
        if(!reporter.isEmpty())throw new IllegalArgumentException("Block entity encoding failed: "+reporter.getReport());
        var tag=output.buildResult();
        entity.loadWithComponents(TagValueInput.create(reporter,registries,tag));
        if(!reporter.isEmpty())throw new IllegalArgumentException("Block entity readback failed: "+reporter.getReport());
        if(spec instanceof StructureInteraction.BossSpawner encounter) {
            var roundTrip=TagValueOutput.createWithContext(reporter,registries);
            entity.saveWithFullMetadata(roundTrip);
            var restored=roundTrip.buildResult();
            if(!reporter.isEmpty() || !tag.equals(restored))throw new IllegalArgumentException("Boss spawner configuration changed during native NBT readback");
            var spawn=TagValueInput.create(reporter,registries,restored).read("SpawnData",SpawnData.CODEC).orElseThrow();
            var payload=spawn.getEntityToSpawn();
            if(!CreatureRuntime.ENCOUNTER_BOSS_ID.equals(payload.getStringOr("id","")) || !creatures.bundleHash().equals(payload.getStringOr("WorldsmithBundle",""))
                || !encounter.getCreatureId().equals(payload.getStringOr("WorldsmithCreature","")) || !payload.getBooleanOr(EncounterBossCreatureEntity.INITIALIZE_TAG,false)
                || payload.size()!=4 || spawn.getCustomSpawnRules().isEmpty() || spawn.getEquipment().isPresent())
                throw new IllegalArgumentException("Boss spawner lost its fixed world/species/initialization identity");
        }
        if(!expectedItems.isEmpty()) {
            Container inventory=(Container)entity;
            for(var entry:expectedItems.entrySet()) {
                var actual=inventory.getItem(entry.getKey());var expected=entry.getValue();
                if(actual.getCount()!=expected.getCount()||!ItemStack.isSameItemSameComponents(actual,expected))
                    throw new IllegalArgumentException("Structure reward identity/components changed during native container NBT readback");
            }
        }
        return tag;
    }

    public static LootTable loot(StructureLoot source) {
        return loot(source,null);
    }

    public static LootTable loot(StructureLoot source, WorldBlockBindings.Resolver customBlocks) {
        return loot(source,customBlocks,null);
    }

    public static LootTable loot(StructureLoot source, WorldBlockBindings.Resolver customBlocks, CustomItemRuntime.Snapshot customItems) {
        if(source.getMinRolls()<0||source.getMaxRolls()<source.getMinRolls()||source.getMaxRolls()>8||source.getEntries().isEmpty()||source.getEntries().size()>32)
            throw new IllegalArgumentException("Inline structure loot requires bounded rolls and 1 through 32 entries");
        var pool=LootPool.lootPool().setRolls(UniformGenerator.between(source.getMinRolls(),source.getMaxRolls()));
        for(var entry:source.getEntries()) {
            if(entry.getMinCount()<1||entry.getMaxCount()<entry.getMinCount()||entry.getWeight()<1||entry.getWeight()>10000)
                throw new IllegalArgumentException("Invalid bounded structure loot entry: "+entry.getItem());
            // Keep the canonical stack patch, not just the generic host Item: identity, model, name,
            // lore, rarity and per-definition stack size all belong to the actual reward.
            ItemStack prototype=WorldRewardItems.stack(entry.getItem(),entry.getMaxCount(),customBlocks,customItems).copyWithCount(1);
            var reward=LootItem.lootTableItem(prototype.getItem()).setWeight(entry.getWeight());
            for(var component:prototype.getComponentsPatch().entrySet()) {
                if(component.getValue().isEmpty())throw new IllegalArgumentException("Reward prototypes with removed native components are not supported: "+entry.getItem());
                reward.apply(setComponent(component.getKey(),component.getValue().orElseThrow()));
            }
            reward.apply(SetItemCountFunction.setCount(UniformGenerator.between(entry.getMinCount(),entry.getMaxCount())));
            pool.add(reward);
        }
        return LootTable.lootTable().setParamSet(LootContextParamSets.CHEST).withPool(pool).build();
    }

    @SuppressWarnings("unchecked")
    private static <T> LootItemConditionalFunction.Builder<?> setComponent(DataComponentType<T> type,Object value) {
        // Value comes from the same typed DataComponentPatch entry, not from a user-supplied untyped map.
        return SetComponentsFunction.setComponent(type,(T)value);
    }
    private static DyeColor color(String name) {return DyeColor.valueOf(name.toUpperCase(java.util.Locale.ROOT));}
    private static SignText text(List<String> lines,String color,boolean glow) {
        var text=new SignText().setColor(color(color)).setHasGlowingText(glow);
        for(int i=0;i<lines.size();i++)text=text.setMessage(i,Component.literal(lines.get(i)));
        return text;
    }
}
