package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.core.ability.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AbilityGuardRuleTest {
    @org.junit.jupiter.api.BeforeAll static void nativeBootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
    @Test void finiteGuardCalculationNeverOverdrawsOrHeals() {
        var rule=new AbilityCombatEffects.GuardRule(.5,2,"mana",null,null,2,"shield");
        assertEquals(7,rule.prevented(10,100)); assertEquals(3,rule.prevented(10,3)); assertEquals(1,rule.prevented(1,100)); assertEquals(0,rule.prevented(10,0));
    }
    @Test void exactFieldSchemaRejectsTyposAndFractionalHitLimits() {
        assertThrows(IllegalArgumentException.class,() -> AbilityCombatEffects.rule(Map.of("absorbb",AbilityValues.number(1))));
        assertThrows(IllegalArgumentException.class,() -> AbilityCombatEffects.rule(Map.of("maxHits",AbilityValues.number(1.5))));
        assertThrows(IllegalArgumentException.class,() -> AbilityCombatEffects.rule(Map.of("multiplier",AbilityValues.text("0.5"))));
    }
    @Test void boundsAndDefaultsAreExplicit() {
        var defaults=AbilityCombatEffects.rule(Map.of()); assertEquals(0,defaults.prevented(10,10)); assertEquals(128,defaults.maxHits());
        assertThrows(IllegalArgumentException.class,() -> new AbilityCombatEffects.GuardRule(-.1,0,null,null,null,1,""));
        assertThrows(IllegalArgumentException.class,() -> new AbilityCombatEffects.GuardRule(1,101,null,null,null,1,""));
        assertThrows(IllegalArgumentException.class,() -> new AbilityCombatEffects.GuardRule(1,0,null,null,2.0,1,""));
    }
    @Test void additivePreflightIncludesExistingNativeMultipliersBeforeSanitizing() {
        var attribute=new net.minecraft.world.entity.ai.attributes.AttributeInstance(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR,a -> {});
        attribute.setBaseValue(4);
        attribute.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(net.minecraft.resources.Identifier.parse("worldsmith:test_scale"),1,
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        assertEquals(12,AbilityCombatEffects.withAdditiveModifier(attribute,2)); assertEquals(8,attribute.getValue());
    }
    @Test void damageMultiplierProductIsClippedOnlyAfterAllLiveEffects() {
        double forward=1; for(int i=0;i<7;i++) forward*=4; forward*=.00001;
        double reverse=.00001; for(int i=0;i<7;i++) reverse*=4;
        assertEquals(16.384,AbilityCombatEffects.scaledDamage(100,forward),1e-9);
        assertEquals(AbilityCombatEffects.scaledDamage(100,forward),AbilityCombatEffects.scaledDamage(100,reverse),1e-9);
        assertEquals(0,AbilityCombatEffects.scaledDamage(Float.MAX_VALUE,0));
        assertTrue(Double.isFinite(Float.MAX_VALUE*Math.pow(8,256)));
    }
}
