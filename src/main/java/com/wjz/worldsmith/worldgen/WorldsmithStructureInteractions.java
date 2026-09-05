package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.structure.StructureInteraction;
import com.wjz.worldsmith.core.structure.StructureLoot;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;

/** Real MC block entities and codecs, with literal text and a small typed content vocabulary. */
public final class WorldsmithStructureInteractions {
    private WorldsmithStructureInteractions() {}

    public static CompoundTag encode(StructureInteraction spec, BlockState state, BlockPos pos,
        HolderLookup.Provider registries, Identifier inlineLoot) {
        if(!(state.getBlock() instanceof EntityBlock factory))throw new IllegalArgumentException("Interaction target is not a block entity: "+state);
        BlockEntity entity=factory.newBlockEntity(pos,state);
        if(entity==null)throw new IllegalArgumentException("Block entity factory returned no entity");
        ProblemReporter.Collector reporter=new ProblemReporter.Collector();
        var output=TagValueOutput.createWithContext(reporter,registries);
        if(spec instanceof StructureInteraction.Container contents) {
            if(!(entity instanceof Container inventory))throw new IllegalArgumentException("Container content targets a non-container block");
            if(contents.getLootTable()!=null || contents.getLoot()!=null) {
                if(!(entity instanceof RandomizableContainer randomizable))throw new IllegalArgumentException("This container does not support loot tables");
                Identifier id=contents.getLoot()!=null?inlineLoot:Identifier.parse(contents.getLootTable());
                if(id==null)throw new IllegalArgumentException("Inline loot requires an exported table id");
                randomizable.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,id),0L);
            } else for(var item:contents.getItems()) {
                Item type=item(item.getItem());var stack=new ItemStack(type,item.getCount());
                if(item.getSlot()<0||item.getSlot()>=inventory.getContainerSize()||item.getCount()>stack.getMaxStackSize())throw new IllegalArgumentException("Item exceeds the actual container capacity or stack limit");
                inventory.setItem(item.getSlot(),stack);
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
        return tag;
    }

    public static LootTable loot(StructureLoot source) {
        var pool=LootPool.lootPool().setRolls(UniformGenerator.between(source.getMinRolls(),source.getMaxRolls()));
        for(var entry:source.getEntries()) {
            Item item=item(entry.getItem());
            if(entry.getMaxCount()>new ItemStack(item).getMaxStackSize())throw new IllegalArgumentException("Loot count exceeds this item's stack limit: "+entry.getItem());
            pool.add(LootItem.lootTableItem(item).setWeight(entry.getWeight())
                .apply(SetItemCountFunction.setCount(UniformGenerator.between(entry.getMinCount(),entry.getMaxCount()))));
        }
        return LootTable.lootTable().setParamSet(LootContextParamSets.CHEST).withPool(pool).build();
    }
    private static Item item(String id) {
        Item item=BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElseThrow(()->new IllegalArgumentException("Unknown structure item: "+id));
        if(item==Items.AIR)throw new IllegalArgumentException("Omit empty inventory slots instead of inserting air");
        return item;
    }
    private static DyeColor color(String name) {return DyeColor.valueOf(name.toUpperCase(java.util.Locale.ROOT));}
    private static SignText text(List<String> lines,String color,boolean glow) {
        var text=new SignText().setColor(color(color)).setHasGlowingText(glow);
        for(int i=0;i<lines.size();i++)text=text.setMessage(i,Component.literal(lines.get(i)));
        return text;
    }
}
