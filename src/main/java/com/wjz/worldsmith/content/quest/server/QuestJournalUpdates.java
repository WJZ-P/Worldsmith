package com.wjz.worldsmith.content.quest.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded debounce window: repeated kills merge without postponing a player's first update forever. */
final class QuestJournalUpdates {
    private record Due(Object owner, int tick) {}
    private final ConcurrentHashMap<UUID, Due> pending = new ConcurrentHashMap<>();

    void mark(UUID player, Object owner, int now) {
        pending.compute(player, (id, previous) -> previous != null && previous.owner == owner ? previous : new Due(owner, now + 5));
    }
    List<UUID> drain(Object owner, int now) {
        List<UUID> ready = new ArrayList<>();
        pending.forEach((id, due) -> {
            if (due.owner == owner && now - due.tick >= 0 && pending.remove(id, due)) ready.add(id);
        });
        return List.copyOf(ready);
    }
    void remove(UUID player) { pending.remove(player); }
    void clear(Object owner) { pending.entrySet().removeIf(entry -> entry.getValue().owner == owner); }
}
