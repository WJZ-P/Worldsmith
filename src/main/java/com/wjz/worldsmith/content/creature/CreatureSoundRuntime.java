package com.wjz.worldsmith.content.creature;

import com.wjz.worldsmith.core.content.CreatureSound;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import java.util.EnumMap;
import java.util.Map;

/** Resolve only the versioned vanilla vocabulary, never arbitrary filesystem paths or unregistered sounds. */
public final class CreatureSoundRuntime {
    private static Map<CreatureSound, SoundEvent> events;
    private CreatureSoundRuntime() {}

    public static synchronized void initialize() {
        if (events != null) return;
        var resolved = new EnumMap<CreatureSound, SoundEvent>(CreatureSound.class);
        for (var sound : CreatureSound.values()) {
            if (sound == CreatureSound.SILENT) continue;
            var id = Identifier.parse(sound.getEventId());
            resolved.put(sound, BuiltInRegistries.SOUND_EVENT.getOptional(id)
                .orElseThrow(() -> new IllegalStateException("Missing vanilla creature sound: " + id)));
        }
        events = Map.copyOf(resolved);
    }

    public static SoundEvent event(CreatureSound sound) {
        initialize();
        return events.get(sound);
    }
}
