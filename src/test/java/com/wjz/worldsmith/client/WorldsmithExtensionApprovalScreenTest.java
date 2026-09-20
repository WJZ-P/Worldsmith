package com.wjz.worldsmith.client;

import com.wjz.worldsmith.core.ability.AbilityCapabilitySpec;
import com.wjz.worldsmith.core.ability.AbilityType;
import com.wjz.worldsmith.core.ability.extension.AbilityExtensionApproval;
import com.wjz.worldsmith.core.ability.extension.AbilityExtensionManifest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldsmithExtensionApprovalScreenTest {
    @Test void immutableReviewIncludesEveryHashAndExactProviderMetadataWithoutDrawingApproval() {
        String source = "0123456789abcdef".repeat(4), artifact = "fedcba9876543210".repeat(4), old = "abcdef0123456789".repeat(4);
        var spec = new AbilityCapabilitySpec("example.scale", 2, List.of(AbilityType.NUMBER), AbilityType.NUMBER, false, "example.scale(value): exact declaration.");
        var manifest = new AbilityExtensionManifest(1, "example", "Example", source, List.of("example.Provider"), Map.of("example.Provider", spec), 21, "static_abi_only");
        var approval = new AbilityExtensionApproval("a".repeat(32), "example", "Example", source, artifact, old, manifest, "source-record.json", "candidate.jar", "Trusted JVM code", true);
        var lines = WorldsmithExtensionApprovalScreen.reviewLines(approval);
        String text = lines.stream().map(component -> component.getString()).collect(java.util.stream.Collectors.joining("\n"));
        String unwrapped = text.replaceAll("\\s+", "");
        assertTrue(unwrapped.contains(source));
        assertTrue(unwrapped.contains(artifact));
        assertTrue(unwrapped.contains(old));
        assertTrue(text.contains("example.Provider"));
        assertTrue(text.contains("example.scale v2 (NUMBER) -> NUMBER; effect=false"));
        assertTrue(text.contains(spec.getDescription()));
        assertThrows(UnsupportedOperationException.class, lines::clear);
    }
}
