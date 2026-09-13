package com.wjz.worldsmith.content.creature;

import com.wjz.worldsmith.core.content.CreatureSound;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreatureSoundRuntimeTest {
    @Test void vocabularyResolvesRegisteredNativeEventsWithoutCreatingSoundAssets() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        CreatureSoundRuntime.initialize();
        for (var sound : CreatureSound.values()) {
            if (sound == CreatureSound.SILENT) assertNull(CreatureSoundRuntime.event(sound));
            else assertNotNull(CreatureSoundRuntime.event(sound), sound.name());
        }
    }
}
