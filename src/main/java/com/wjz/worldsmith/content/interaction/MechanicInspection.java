package com.wjz.worldsmith.content.interaction;

import com.wjz.worldsmith.core.content.WorldMechanicValidation;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.network.chat.Component;

/** Bounded, immutable observation. It is neither an activation permission nor a durable mechanic fact. */
public record MechanicInspection(Code code, String ruleId, int missingCells, int requiredItems, int heldItems,
                                 long cooldownTicks) {
    public enum Code {
        READY, INCOMPLETE_PATTERN, WRONG_ITEM, INSUFFICIENT_ITEMS, COOLDOWN, SPENT, WRONG_BIOME,
        NO_SPACE, OBSTRUCTED, PROTECTED, UNLOADED, WRONG_ANCHOR, INSTANCE_LIMIT, QUARANTINED, UNAVAILABLE
    }

    public MechanicInspection {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(ruleId, "ruleId");
        if (!ruleId.isEmpty() && !WorldMechanicValidation.validId(ruleId)
            || missingCells < 0 || missingCells > WorldMechanicValidation.MAX_PATTERN_CELLS
            || requiredItems < 0 || requiredItems > 64 || heldItems < 0 || heldItems > 64
            || cooldownTicks < 0 || cooldownTicks > WorldMechanicValidation.MAX_COOLDOWN_TICKS)
            throw new IllegalArgumentException("Invalid mechanic inspection observation");
    }

    public static MechanicInspection of(Code code) { return new MechanicInspection(code, "", 0, 0, 0, 0); }

    public Component message() {
        String key = "worldsmith.mechanics.inspection." + code.name().toLowerCase(Locale.ROOT);
        return switch (code) {
            case INCOMPLETE_PATTERN -> Component.translatable(key, missingCells);
            case INSUFFICIENT_ITEMS -> Component.translatable(key, requiredItems, heldItems);
            case COOLDOWN -> Component.translatable(key, (cooldownTicks + 19) / 20);
            default -> Component.translatable(key);
        };
    }
}
