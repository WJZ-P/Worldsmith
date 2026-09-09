package com.wjz.worldsmith.content;

import com.wjz.worldsmith.core.content.CustomBlockBindingSnapshot;
import com.wjz.worldsmith.core.content.CustomBlockDefinition;
import com.wjz.worldsmith.core.content.CustomBlockLibrary;
import com.wjz.worldsmith.core.content.CustomBlockProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class WorldBlockBindingsTest {
    private static CustomBlockLibrary library(String id) { return new CustomBlockLibrary(1, List.of(new CustomBlockDefinition(id, id, CustomBlockProfile.STONE, "a".repeat(64), 0, ""))); }
    @AfterEach void clear() { var active = WorldBlockBindings.active(); if (active != null) WorldBlockBindings.clear(active.getScope()); }

    @Test void preparationHasNoActiveStateSideEffectsAndRollbackRestoresIdentity() {
        var draft = WorldBlockBindings.prepare("alpha", library("moon"), null);
        assertNull(WorldBlockBindings.active());
        assertEquals("worldsmith:content/block/stone/00", draft.nativeIds().get("worldsmith:content/moon"));
        draft.commit();
        assertSame(draft.snapshot(), WorldBlockBindings.active());
        draft.rollback();
        assertNull(WorldBlockBindings.active());
        draft.rollback();
        assertThrows(IllegalStateException.class, draft::commit);
    }

    @Test void stalePreparationsAndRollbackNeverOverwriteNewerActivation() {
        var first = WorldBlockBindings.prepare("alpha", library("moon"), null);
        var stale = WorldBlockBindings.prepare("alpha", library("sun"), null);
        first.commit();
        assertThrows(IllegalStateException.class, stale::commit);
        var newer = WorldBlockBindings.prepare("alpha", library("moon"), first.snapshot());
        newer.commit();
        assertThrows(IllegalStateException.class, first::rollback);
        assertSame(newer.snapshot(), WorldBlockBindings.active());
    }

    @Test void switchingRequiresExplicitExpectedPriorSnapshotAndSupportsRollback() {
        var first = WorldBlockBindings.prepare("alpha", library("moon"), null); first.commit();
        var second = WorldBlockBindings.prepare("beta", library("sun"), null);
        assertThrows(IllegalStateException.class, second::commit);
        assertThrows(IllegalStateException.class, () -> second.commitForNewWorld(null));
        second.commitForNewWorld(first.snapshot());
        assertEquals("beta", WorldBlockBindings.active().getScope());
        second.rollback();
        assertSame(first.snapshot(), WorldBlockBindings.active());
    }

    @Test void sameScopeCannotReplaceSavedNativeIdentityEvenWithSwitchToken() {
        var first = WorldBlockBindings.prepare("alpha", library("moon"), null); first.commit();
        var mutation = WorldBlockBindings.prepare("alpha", library("sun"), null);
        assertThrows(IllegalStateException.class, () -> mutation.commitForNewWorld(first.snapshot()));
        assertSame(first.snapshot(), WorldBlockBindings.active());
        assertThrows(IllegalStateException.class, () -> WorldBlockBindings.clear("beta"));
    }

    @Test void readOnlyCompilationResolversRemainIndependentOfActivatedWorld() {
        var alpha = WorldBlockBindings.prepare("alpha", library("moon"), null);
        var beta = WorldBlockBindings.prepare("beta", library("sun"), null);
        var first = WorldBlockBindings.resolver(alpha.snapshot());
        var second = WorldBlockBindings.resolver(beta.snapshot());
        alpha.commit();
        assertTrue(first.nativeIds().containsKey("worldsmith:content/moon"));
        assertFalse(second.nativeIds().containsKey("worldsmith:content/moon"));
        assertThrows(IllegalArgumentException.class, () -> second.resolve("worldsmith:content/moon"));
        assertThrows(IllegalArgumentException.class, () -> first.resolve("worldsmith:content/moon[light=15]"));
        assertThrows(IllegalArgumentException.class, () -> first.resolve("worldsmith:content/block/stone/00"));
        assertSame(alpha.snapshot(), WorldBlockBindings.active());
    }
}
