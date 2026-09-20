package com.wjz.worldsmith.content;

import com.google.gson.JsonParser;
import com.wjz.worldsmith.core.content.CustomBlockBindings;
import com.wjz.worldsmith.core.content.CustomBlockDefinition;
import com.wjz.worldsmith.core.content.CustomBlockLibrary;
import com.wjz.worldsmith.core.content.CustomBlockProfile;
import com.wjz.worldsmith.core.content.BlockAppearance;
import com.wjz.worldsmith.core.content.BlockFaceTexture;
import com.wjz.worldsmith.core.content.BlockOrientation;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

final class GeneratedBlockResourcesTest {
    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x80aabbcc);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
    }
    private static CustomBlockLibrary library(String hash, CustomBlockProfile profile) {
        return new CustomBlockLibrary(2, List.of(new CustomBlockDefinition("moon", "Moon \"stone\" 月晶", profile, BlockAppearance.uniform(hash), 7, "")));
    }

    @Test void generatedModelsItemsLootAndToolTagsUseTheSameNativeBinding() throws Exception {
        byte[] png = png(16, 16); String hash = GeneratedBlockResources.sha256(png);
        var library = library(hash, CustomBlockProfile.STONE); var snapshot = CustomBlockBindings.plan("realm", library);
        var client = GeneratedBlockResources.clientResources(snapshot, library, Map.of(hash, png));
        String path = "content/block/stone/00";
        assertTrue(client.containsKey("assets/worldsmith/textures/block/content/" + hash + ".png"));
        assertTrue(client.containsKey("assets/worldsmith/blockstates/" + path + ".json"));
        assertTrue(new String(client.get("assets/worldsmith/items/" + path + ".json"), StandardCharsets.UTF_8).contains("worldsmith:block/" + path));
        assertEquals("Moon \"stone\" 月晶", JsonParser.parseString(new String(client.get("assets/worldsmith/lang/en_us.json"), StandardCharsets.UTF_8)).getAsJsonObject().get("block.worldsmith.content.block.stone.00").getAsString());
        var server = GeneratedBlockResources.serverResources(snapshot, library);
        assertTrue(server.containsKey("data/worldsmith/loot_table/blocks/" + path + ".json"));
        assertTrue(new String(server.get("data/minecraft/tags/block/mineable/pickaxe.json"), StandardCharsets.UTF_8).contains("worldsmith:" + path));
        assertTrue(new String(server.get("data/minecraft/tags/block/base_stone_overworld.json"), StandardCharsets.UTF_8).contains("worldsmith:" + path));
        assertTrue(new String(server.get("data/minecraft/tags/block/stone_ore_replaceables.json"), StandardCharsets.UTF_8).contains("worldsmith:" + path));
        png[0] = 0;
        assertNotEquals(0, client.get("assets/worldsmith/textures/block/content/" + hash + ".png")[0]);
    }

    @Test void glassUsesNative26MaterialTransparencyInsteadOfMutableRenderLayerMap() throws Exception {
        byte[] png = png(16, 16); String hash = GeneratedBlockResources.sha256(png);
        var library = library(hash, CustomBlockProfile.GLASS); var snapshot = CustomBlockBindings.plan("realm", library);
        var files = GeneratedBlockResources.clientResources(snapshot, library, Map.of(hash, png));
        var model = JsonParser.parseString(new String(files.get("assets/worldsmith/models/block/content/block/glass/00.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        for (String face : List.of("up", "down", "north", "south", "west", "east", "particle"))
            assertTrue(model.getAsJsonObject("textures").getAsJsonObject(face).get("force_translucent").getAsBoolean());
    }

    @Test void sixFaceModelsKeepPerFaceUvTurnsParticleAndDirectionalVariants() throws Exception {
        byte[] first = png(16,16), second = png(32,32);
        String a = GeneratedBlockResources.sha256(first), b = GeneratedBlockResources.sha256(second);
        var face = new BlockFaceTexture(a); var appearance = new BlockAppearance(face, face, new BlockFaceTexture(b, 1), face, face, face, b, BlockOrientation.HORIZONTAL);
        var library = new CustomBlockLibrary(2, List.of(new CustomBlockDefinition("sign", "Sign", CustomBlockProfile.STONE, appearance, 0, "")));
        var snapshot = CustomBlockBindings.plan("realm", library);
        var files = GeneratedBlockResources.clientResources(snapshot, library, Map.of(a, first, b, second));
        var model = JsonParser.parseString(new String(files.get("assets/worldsmith/models/block/content/block/stone/00.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("worldsmith:block/content/" + b, model.getAsJsonObject("textures").get("particle").getAsString());
        var faces = model.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces");
        assertEquals(6, faces.size()); assertEquals(90, faces.getAsJsonObject("north").get("rotation").getAsInt());
        assertEquals("north", faces.getAsJsonObject("north").get("cullface").getAsString());
        var variants = JsonParser.parseString(new String(files.get("assets/worldsmith/blockstates/content/block/stone/00.json"),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("variants");
        assertEquals(0, variants.getAsJsonObject("facing=north").get("y").getAsInt());
        assertEquals(90, variants.getAsJsonObject("facing=east").get("y").getAsInt());
        assertEquals(180, variants.getAsJsonObject("facing=south").get("y").getAsInt());
        assertEquals(270, variants.getAsJsonObject("facing=west").get("y").getAsInt());
        assertFalse(variants.getAsJsonObject("facing=east").get("uvlock").getAsBoolean());
        assertThrows(IllegalArgumentException.class, () -> GeneratedBlockResources.clientResources(snapshot, library, Map.of(a,first)));
    }

    @Test void corruptHashInvalidPngAndImageDimensionBombsAreRejected() throws Exception {
        byte[] valid = png(16, 16);
        assertThrows(IllegalArgumentException.class, () -> GeneratedBlockResources.validateTexture("0".repeat(64), valid));
        for (byte[] invalid : List.of(new byte[24], png(15, 16), png(16, 32), png(512, 512))) {
            assertThrows(IllegalArgumentException.class, () -> GeneratedBlockResources.validateTexture(GeneratedBlockResources.sha256(invalid), invalid));
        }
        var library = library(GeneratedBlockResources.sha256(valid), CustomBlockProfile.STONE);
        assertThrows(IllegalArgumentException.class, () -> GeneratedBlockResources.clientResources(CustomBlockBindings.plan("realm", library), library, Map.of()));
    }

    @Test void virtualPackOwnsImmutableBytesAndOnlyExposesItsClientNamespace() throws Exception {
        SharedConstants.tryDetectVersion();
        byte[] original = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> input = new HashMap<>(); input.put("assets/worldsmith/models/test.json", original);
        var pack = new GeneratedWorldResourcePack("realm", input);
        original[0] = 0; input.clear();
        var location = new PackLocationInfo("test", Component.literal("test"), PackSource.WORLD, Optional.empty());
        try (var resources = pack.supplier().openPrimary(location)) {
            var id = Identifier.fromNamespaceAndPath("worldsmith", "models/test.json");
            assertEquals('{', resources.getResource(PackType.CLIENT_RESOURCES, id).get().read());
            assertNull(resources.getResource(PackType.SERVER_DATA, id));
            assertEquals(java.util.Set.of("worldsmith"), resources.getNamespaces(PackType.CLIENT_RESOURCES));
            assertNotNull(resources.getRootResource("pack.mcmeta"));
            assertNull(resources.getRootResource("..", "pack.mcmeta"));
            List<Identifier> listed = new java.util.ArrayList<>(); resources.listResources(PackType.CLIENT_RESOURCES, "worldsmith", "models", (found, stream) -> listed.add(found));
            assertEquals(List.of(id), listed);
        }
    }

    @Test void virtualPackRejectsTraversalForeignNamespaceAndReservedSentinel() {
        for (String path : List.of("assets/minecraft/models/a.json", "assets/worldsmith/../a.json", "assets/worldsmith/models//a.json", GeneratedWorldResourcePack.SENTINEL_PATH)) {
            assertThrows(IllegalArgumentException.class, () -> new GeneratedWorldResourcePack("realm", Map.of(path, new byte[1])));
        }
    }
}
