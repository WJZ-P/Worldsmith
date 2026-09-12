package com.wjz.worldsmith.content.item;

import com.wjz.worldsmith.core.content.CustomItemDefinition;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomItemComponentsTest {
    private static final String HASH="a".repeat(64);
    @BeforeAll static void nativeBootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private static CustomItemDefinition item(String extra){
        return WorldsmithJson.INSTANCE.getFormat().decodeFromString(CustomItemDefinition.Companion.serializer(),
            "{\"id\":\"relic\",\"displayName\":\"Relic\",\"textureAsset\":\""+HASH+"\",\"maxStackSize\":1,"+extra+"}");
    }

    @Test void toolComponentsReuseNativeTierRulesAndConfiguredSpeedOnOneStack(){
        var definition=item("\"equipment\":{\"type\":\"PICKAXE\",\"miningTier\":\"DIAMOND\",\"miningSpeed\":9,\"durability\":500,\"attackDamage\":4,\"attackSpeed\":-2.5}");
        CustomItemComponents.validateNative(definition);
        ItemStack stack=new ItemStack(Items.STICK);
        CustomItemComponents.apply(stack,HASH,definition);
        var tool=stack.get(DataComponents.TOOL);var nativeTool=Items.DIAMOND_PICKAXE.getDefaultInstance().get(DataComponents.TOOL);
        assertNotNull(tool);assertNotNull(nativeTool);
        assertEquals(nativeTool.rules().size(),tool.rules().size());
        for(int i=0;i<tool.rules().size();i++){
            assertSame(nativeTool.rules().get(i).blocks(),tool.rules().get(i).blocks());
            assertEquals(nativeTool.rules().get(i).correctForDrops(),tool.rules().get(i).correctForDrops());
            if(tool.rules().get(i).speed().isPresent())assertEquals(9.0f,tool.rules().get(i).speed().orElseThrow().floatValue());
        }
        assertEquals(500,stack.getMaxDamage());assertEquals(1,stack.getMaxStackSize());
        assertEquals(5.0,stack.get(DataComponents.ATTRIBUTE_MODIFIERS).compute(Attributes.ATTACK_DAMAGE,1.0,EquipmentSlot.MAINHAND));
        assertEquals(1.5,stack.get(DataComponents.ATTRIBUTE_MODIFIERS).compute(Attributes.ATTACK_SPEED,4.0,EquipmentSlot.MAINHAND));
        assertNull(stack.get(DataComponents.EQUIPPABLE));
    }

    @Test void armorStatsAndWearableAssetAreRestrictedToTheirSlotAndWorld(){
        var definition=item("\"equipment\":{\"type\":\"HELMET\",\"armor\":3,\"toughness\":2,\"knockbackResistance\":0.1,\"textureAsset\":\""+HASH+"\"}");
        ItemStack first=new ItemStack(Items.STICK);ItemStack second=new ItemStack(Items.STICK);
        CustomItemComponents.apply(first,HASH,definition);CustomItemComponents.apply(second,"b".repeat(64),definition);
        assertEquals(EquipmentSlot.HEAD,first.get(DataComponents.EQUIPPABLE).slot());
        assertNotEquals(first.get(DataComponents.EQUIPPABLE).assetId(),second.get(DataComponents.EQUIPPABLE).assetId());
        assertEquals(3.0,first.get(DataComponents.ATTRIBUTE_MODIFIERS).compute(Attributes.ARMOR,0.0,EquipmentSlot.HEAD));
        assertEquals(0.0,first.get(DataComponents.ATTRIBUTE_MODIFIERS).compute(Attributes.ARMOR,0.0,EquipmentSlot.MAINHAND));
        assertNull(first.get(DataComponents.TOOL));
    }

    @Test void foodUsesNativeConsumptionAndAllEffectSitesResolveBeforeUse(){
        var food=item("\"consumable\":{\"nutrition\":4,\"saturation\":2,\"consumeSeconds\":0.8,\"alwaysEdible\":true,\"effects\":[{\"effect\":\"minecraft:regeneration\"}]}");
        CustomItemComponents.validateNative(food);ItemStack stack=new ItemStack(Items.STICK);CustomItemComponents.apply(stack,HASH,food);
        assertEquals(4,stack.get(DataComponents.FOOD).nutrition());assertEquals(2.0f,stack.get(DataComponents.FOOD).saturation());
        assertEquals(16,stack.get(DataComponents.CONSUMABLE).consumeTicks());
        assertEquals(1,stack.get(DataComponents.CONSUMABLE).onConsumeEffects().size());
        for(String extra:new String[]{
            "\"consumable\":{\"effects\":[{\"effect\":\"worldsmith:missing\"}]}",
            "\"actions\":[{\"effects\":[{\"kind\":\"status\",\"effect\":\"worldsmith:missing\"}]}]",
            "\"actions\":[{\"effects\":[{\"kind\":\"projectile\",\"hitEffects\":[{\"effect\":\"worldsmith:missing\"}]}]}]"}){
            assertThrows(IllegalArgumentException.class,()->CustomItemComponents.validateNative(item(extra)));
        }
    }
}
