package com.wjz.worldsmith.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldsmithCreationNameTest {
    @Test void defaultNameFollowsPackSwitchesAndRestoresForVanilla() {
        var names=new WorldsmithCreationName("新的世界");
        assertEquals("云岚九州",names.selectPack("新的世界","云岚九州"));
        assertEquals("誓火王国",names.selectPack("云岚九州","誓火王国"));
        assertEquals("新的世界",names.selectNative("誓火王国"));
    }

    @Test void customNameSurvivesSwitchesAndCreationIntentPromotion() {
        var names=new WorldsmithCreationName("New World");
        assertEquals("云岚九州",names.selectPack("New World","云岚九州"));
        assertEquals("我的生存档",names.selectPack("我的生存档","云岚九州"));
        assertEquals("我的生存档",names.selectPack("我的生存档","誓火王国"));
        assertEquals("我的生存档",names.selectNative("我的生存档"));
    }

    @Test void preexistingCustomOrRecreatedNameIsPreserved() {
        var names=new WorldsmithCreationName("New World");
        assertEquals("Copy of Adventure",names.selectPack("Copy of Adventure","云岚九州"));
        assertEquals("Copy of Adventure",names.selectNative("Copy of Adventure"));
    }

    @Test void blankNameUsesFullPackTitleAndStateDoesNotLeakToAnotherScreen() {
        String title="云岚九州：山河万象".repeat(8);
        var names=new WorldsmithCreationName("New World");
        assertEquals(title,names.selectPack("",title));
        assertEquals("",names.selectNative(title));
        assertEquals("誓火王国",new WorldsmithCreationName("New World").selectPack("New World","誓火王国"));
    }
}
