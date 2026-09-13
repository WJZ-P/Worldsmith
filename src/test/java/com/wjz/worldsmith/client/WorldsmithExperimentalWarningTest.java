package com.wjz.worldsmith.client;

import com.mojang.serialization.Lifecycle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldsmithExperimentalWarningTest {
    @Test void onlyGenericExperimentalLifecycleIsEligible() {
        assertTrue(WorldsmithExperimentalWarning.eligible(Lifecycle.experimental(), false, false));
        assertFalse(WorldsmithExperimentalWarning.eligible(Lifecycle.stable(), false, false));
        assertFalse(WorldsmithExperimentalWarning.eligible(Lifecycle.deprecated(1), false, false));
    }

    @Test void experimentalFeaturesAndLegacyCustomizedWorldsKeepTheirConfirmation() {
        assertFalse(WorldsmithExperimentalWarning.eligible(Lifecycle.experimental(), true, false));
        assertFalse(WorldsmithExperimentalWarning.eligible(Lifecycle.experimental(), false, true));
        assertFalse(WorldsmithExperimentalWarning.eligible(Lifecycle.experimental(), true, true));
    }
}
