package com.wjz.worldsmith.content.item;

import com.google.gson.JsonParser;
import com.wjz.worldsmith.core.content.ContentAssetValidation;
import com.wjz.worldsmith.core.content.CustomItemDefinition;
import com.wjz.worldsmith.core.content.CustomItemLibrary;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedItemResourcesTest {
    private static final String WORLD="a".repeat(64);
    @BeforeAll static void nativeBootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private static byte[] png(int width,int height)throws Exception{
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,0xff73839b);var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();
    }
    private static CustomItemDefinition definition(String id,String icon,String profile){
        return WorldsmithJson.INSTANCE.getFormat().decodeFromString(CustomItemDefinition.Companion.serializer(),
            "{\"id\":\""+id+"\",\"displayName\":\""+id+"\",\"maxStackSize\":1,\"textureAsset\":\""+icon+"\",\"equipment\":"+profile+"}");
    }

    @Test void weaponsUseHandheldModelsAndLeggingsUseIndependentHumanoidLeggingsAtlas()throws Exception{
        byte[] icon=png(32,32),atlas=png(128,64);String iconHash=ContentAssetValidation.INSTANCE.hash(icon),atlasHash=ContentAssetValidation.INSTANCE.hash(atlas);
        var sword=definition("blade",iconHash,"{\"type\":\"MELEE\"}");
        var armor=definition("greaves",iconHash,"{\"type\":\"LEGGINGS\",\"textureAsset\":\""+atlasHash+"\"}");
        var snapshot=CustomItemRuntime.prepare(WORLD,new CustomItemLibrary(2,List.of(sword,armor)));
        var resources=GeneratedItemResources.clientResources(snapshot,Map.of(iconHash,icon,atlasHash,atlas));
        assertTrue(resources.entrySet().stream().filter(e->e.getKey().startsWith("assets/worldsmith/models/")).anyMatch(e->
            JsonParser.parseString(new String(e.getValue(),StandardCharsets.UTF_8)).getAsJsonObject().get("parent").getAsString().equals("minecraft:item/handheld")));
        String equipment="assets/worldsmith/equipment/"+GeneratedItemResources.equipmentId(WORLD,"greaves").getPath()+".json";
        var layers=JsonParser.parseString(new String(resources.get(equipment),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("layers");
        assertTrue(layers.has("humanoid_leggings"));assertFalse(layers.has("humanoid"));
        assertArrayEquals(atlas,resources.get("assets/worldsmith/textures/entity/equipment/humanoid_leggings/content/"+atlasHash+".png"));
        assertNotEquals(GeneratedItemResources.equipmentId(WORLD,"greaves"),GeneratedItemResources.equipmentId("b".repeat(64),"greaves"));
    }

    @Test void armorPngValidationMatchesCorePowerOfTwoIndependentUvBounds()throws Exception{
        for(int width:new int[]{64,128,256,512}){
            byte[] image=png(width,width/2);GeneratedItemResources.validateArmorTexture(ContentAssetValidation.INSTANCE.hash(image),image);
        }
        for(int[] size:new int[][]{{32,16},{64,64},{192,96},{1024,512}}){
            byte[] image=png(size[0],size[1]);assertThrows(IllegalArgumentException.class,()->GeneratedItemResources.validateArmorTexture(ContentAssetValidation.INSTANCE.hash(image),image));
        }
    }
}
