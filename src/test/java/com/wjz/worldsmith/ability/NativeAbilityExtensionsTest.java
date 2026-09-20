package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.ability.extension.*;
import com.wjz.worldsmith.core.drawhost.DrawingRuntime;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeAbilityExtensionsTest {
    @TempDir Path root;

    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test void receiptVerificationPrecedesInitializationAndNextStartupLoadsExactDeclaredProvider() throws Exception {
        String property = "worldsmith.test.extension." + root.getFileName();
        System.clearProperty(property);
        try (var service = service(root.resolve("work"))) {
            var job = ready(service, service.submit(project("fixture_extension", "v1", property, 2, false)));
            Path candidate = service.getWorkDirectory().resolve("artifacts").resolve(job.getArtifactHash() + ".jar");
            var artifact = AbilityExtensionArtifacts.inspect(candidate);
            assertNull(System.getProperty(property), "Compilation/static ABI probing must not initialize in this process");

            Path unapproved = Files.createDirectories(root.resolve("unapproved"));
            Files.copy(candidate, unapproved.resolve("fixture_extension.jar"));
            try (var session = NativeAbilityExtensions.loadApproved(unapproved, AbilityCapabilities.standard(), providers -> fail("Unapproved code registered"))) {
                assertEquals("REJECTED", session.reports().getFirst().status());
                assertFalse(session.reports().getFirst().initializationAttempted());
                assertNull(System.getProperty(property));
            }
            Path directory = root.resolve("approved");
            var installer = new AbilityExtensionInstaller(directory);
            installer.install(candidate, artifact, null, System.currentTimeMillis());
            var registered = new LinkedHashMap<AbilityCapabilitySpec, AbilityExtension>();
            try (var session = NativeAbilityExtensions.loadApproved(directory, AbilityCapabilities.standard(), providers -> {
                NativeAbilityExtensions.registerNative(providers); registered.putAll(providers);
            })) {
                assertEquals("LOADED", session.reports().getFirst().status(), session.reports().toString());
                assertTrue(session.reports().getFirst().initializationAttempted());
                assertEquals("v1", System.getProperty(property));
                assertEquals(1, registered.size());
                assertEquals(registered.keySet().iterator().next(), WorldAbilityRuntime.capabilities().lookup("fixture.scale"));
                var script = AbilityCompiler.compile(new AbilityProgramDefinition("extension_native", "Native extension registry", "on start { state.result = fixture.scale(7); }"), WorldAbilityRuntime.capabilities());
                assertEquals(1, script.getUsedCapabilities().get("fixture.scale"));
                var extension = registered.values().iterator().next();
                assertEquals(AbilityValues.number(14), extension.invoke((name, args) -> { throw new AssertionError("Unexpected native call"); }, List.of(AbilityValues.number(7))));

                // The live loader owns .loaded/<oldHash>.jar, not the replaceable active JAR.
                var newer = ready(service, service.submit(project("fixture_extension", "v2", property, 3, false)));
                Path nextCandidate = service.getWorkDirectory().resolve("artifacts").resolve(newer.getArtifactHash() + ".jar");
                var nextArtifact = AbilityExtensionArtifacts.inspect(nextCandidate);
                installer.install(nextCandidate, nextArtifact, artifact.getArtifactHash(), System.currentTimeMillis());
                assertEquals(nextArtifact.getArtifactHash(), installer.existingHash("fixture_extension"));
                assertEquals("v1", System.getProperty(property), "Updating bytes must not initialize or replace live providers");
                assertEquals(AbilityValues.number(14), extension.invoke((name, args) -> AbilityValues.none(), List.of(AbilityValues.number(7))));
            }
            registered.clear();
            try (var restarted = NativeAbilityExtensions.loadApproved(directory, AbilityCapabilities.standard(), registered::putAll)) {
                assertEquals("LOADED", restarted.reports().getFirst().status(), restarted.reports().toString());
                assertEquals("v2", System.getProperty(property));
                assertEquals(AbilityValues.number(21), registered.values().iterator().next().invoke((name, args) -> AbilityValues.none(), List.of(AbilityValues.number(7))));
            }

            // A tampered JAR fails receipt comparison before initialization is attempted again.
            System.clearProperty(property);
            Path active = directory.resolve("fixture_extension.jar");
            byte[] original = Files.readAllBytes(active);
            Files.write(active, java.util.Arrays.copyOf(original, original.length + 1));
            try (var rejected = NativeAbilityExtensions.loadApproved(directory, AbilityCapabilities.standard(), providers -> fail("Changed artifact registered"))) {
                assertEquals("REJECTED", rejected.reports().getFirst().status());
                assertFalse(rejected.reports().getFirst().initializationAttempted());
                assertTrue(rejected.reports().getFirst().message().contains("approved artifact hash"));
                assertNull(System.getProperty(property));
            }
        } finally { System.clearProperty(property); }
    }

    @Test void runtimeSpecMismatchRegistersNoPartialProvidersAndReportsApprovedInitialization() throws Exception {
        String property = "worldsmith.test.extension.mismatch." + root.getFileName();
        System.clearProperty(property);
        try (var service = service(root.resolve("mismatch-work"))) {
            var job = ready(service, service.submit(project("mismatched_extension", "v1", property, 2, true)));
            Path candidate = service.getWorkDirectory().resolve("artifacts").resolve(job.getArtifactHash() + ".jar");
            Path installed = root.resolve("mismatch-installed");
            new AbilityExtensionInstaller(installed).install(candidate, AbilityExtensionArtifacts.inspect(candidate), null, System.currentTimeMillis());
            assertNull(System.getProperty(property));
            try (var rejected = NativeAbilityExtensions.loadApproved(installed, AbilityCapabilities.standard(), providers -> fail("Mismatched spec registered"))) {
                assertEquals("REJECTED", rejected.reports().getFirst().status());
                assertTrue(rejected.reports().getFirst().initializationAttempted());
                assertTrue(rejected.reports().getFirst().message().contains("spec() differs"));
                assertEquals("v1", System.getProperty(property), "Only this explicitly approved startup may initialize provider code");
            }
        } finally { System.clearProperty(property); }
    }

    @Test void receiptSourceIdentityAndRegistryCollisionAreRejectedBeforeApprovedConstructorsRun() throws Exception {
        String property = "worldsmith.test.extension.preflight." + root.getFileName();
        System.clearProperty(property);
        try (var service = service(root.resolve("preflight-work"))) {
            var project = project("preflight_extension", "v1", property, 2, false);
            var job = ready(service, service.submit(project));
            Path candidate = service.getWorkDirectory().resolve("artifacts").resolve(job.getArtifactHash() + ".jar");
            Path installed = root.resolve("preflight-installed");
            new AbilityExtensionInstaller(installed).install(candidate, AbilityExtensionArtifacts.inspect(candidate), null, System.currentTimeMillis());
            var occupied = AbilityCapabilities.standard().extend(project.getDeclaredSpecs().values().iterator().next());
            try (var rejected = NativeAbilityExtensions.loadApproved(installed, occupied, providers -> fail("Duplicate provider registered"))) {
                assertEquals("REJECTED", rejected.reports().getFirst().status());
                assertFalse(rejected.reports().getFirst().initializationAttempted());
                assertNull(System.getProperty(property));
            }
            var receipt = new AbilityExtensionInstallReceipt(1, "preflight_extension", "0".repeat(64), job.getArtifactHash(), System.currentTimeMillis(), true);
            Files.writeString(installed.resolve("preflight_extension.approved.json"), WorldsmithJson.INSTANCE.getFormat().encodeToString(AbilityExtensionInstallReceipt.Companion.serializer(), receipt));
            try (var rejected = NativeAbilityExtensions.loadApproved(installed, AbilityCapabilities.standard(), providers -> fail("Changed source receipt registered"))) {
                assertEquals("REJECTED", rejected.reports().getFirst().status());
                assertFalse(rejected.reports().getFirst().initializationAttempted());
                assertNull(System.getProperty(property));
            }
        } finally { System.clearProperty(property); }
    }

    private AbilityExtensionService service(Path work) {
        var runtime = new AbilityExtensionRuntime(new DrawingRuntime(Path.of(System.getProperty("worldsmith.workerRuntime")), Path.of(System.getProperty("java.home"))), AbilityExtensionRuntime.currentClasspath());
        return new AbilityExtensionService(work, runtime);
    }
    private static AbilityExtensionJob ready(AbilityExtensionService service, AbilityExtensionJob initial) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            var current = service.get(initial.getJobId());
            if (!List.of(AbilityExtensionStage.QUEUED, AbilityExtensionStage.COMPILING, AbilityExtensionStage.STATIC_PROBING).contains(current.getStage())) {
                assertEquals(AbilityExtensionStage.READY, current.getStage(), current.getMessage() + "\n" + current.getLog());
                return current;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Provider fixture build did not complete");
    }
    private static AbilityExtensionProject project(String id, String revision, String property, int multiplier, boolean mismatch) {
        var spec = new AbilityCapabilitySpec("fixture.scale", 1, List.of(AbilityType.NUMBER), AbilityType.NUMBER, false, "fixture.scale(value): bounded numeric test provider.");
        String description = mismatch ? "mismatching actual description" : spec.getDescription();
        String source = """
            package fixture;
            import java.util.List;
            import com.wjz.worldsmith.core.ability.*;
            import com.wjz.worldsmith.core.ability.extension.AbilityExtension;
            public final class Provider implements AbilityExtension {
                static { System.setProperty("%s", "%s"); }
                public Provider() {}
                public AbilityCapabilitySpec spec() { return new AbilityCapabilitySpec("fixture.scale", 1, List.of(AbilityType.NUMBER), AbilityType.NUMBER, false, "%s"); }
                public AbilityValue invoke(AbilityHost host, List<AbilityValue> arguments) { return AbilityValues.number(((AbilityValue.NumberValue)arguments.get(0)).getValue() * %d); }
            }
            """.formatted(property, revision, description, multiplier);
        return new AbilityExtensionProject(id, "Native test provider", revision, List.of("fixture.Provider"), Map.of("fixture.Provider", spec), Map.of("fixture/Provider.java", source));
    }
}
