package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.*
import java.nio.file.Files
import java.nio.file.Path

/** A playable material workshop: real structure, carried icons, oriented placement and a one-shot repair. */
object MaterialShowcaseExample {
    const val STRUCTURE = "hearth_material_workshop"
    const val REPAIR = "hearth_material_repair"
    @JvmField val LAMP_POSITION = BuildPos(7,2,9)
    @JvmField val CHEST_POSITION = BuildPos(11,1,8)
    @JvmStatic fun create(): WorldsmithPack {
        val base = MechanicDiscoveryExample.create(); val family = MaterialFamilyFactory.create()
        val terrain = base.terrain.copy(shape = (base.terrain.shape as TerrainShape.Procedural).let { it.copy(
            anchors = it.anchors + Anchor(STRUCTURE, AnchorPlacement.Fixed(96,0), 32, 8.0)) })
        val workshop = WorldStructureDefinition(STRUCTURE, blueprint(), StructurePlacement(biomes = base.biomes.biomes.map {it.id},
            rotations = listOf(BuildRotation.NONE), anchor = StructureAnchorTarget(STRUCTURE),
            terrainFit = StructureTerrainFit(maxHeightDifference=12, foundation=StructureFoundation(FoundationMode.FILL,"stone",16),earthwork=StructureEarthwork(8,8192))))
        val repair = WorldMechanicDefinition(REPAIR, "换入新灯芯", rules=listOf(WorldMechanicRule("replace_wick",WorldMechanicEvent.USE_BLOCK,
            pattern=listOf(MechanicPatternCell(MechanicOffset(),MechanicBlockPredicate("worldsmith:content/${MaterialFamilyFactory.LAMP_UNLIT}",mapOf("facing" to "north")))),
            actions=listOf(MechanicAction.SetBlock(MechanicOffset(),MechanicBlockPredicate("worldsmith:content/${MaterialFamilyFactory.LAMP_LIT}",mapOf("facing" to "north")))),
            heldItem=MechanicItemCost("worldsmith:item/${MaterialFamilyFactory.WICK}"))),
            description="主手持归灯灯芯，对闭芯灯座正面使用。旧护片开启、亮芯露出，灯座实际发光；一次消费一枚灯芯。")
        val theme = base.theme.copy(title="归灯材料工坊", beats=base.theme.beats+WorldNarrativeBeat("material_craft","木纹与灯芯", "端面、刻纹和打开的灯芯说明材料的用途，而不只改变颜色。",
            listOf(ContentKey("structure",STRUCTURE),ContentKey("block",MaterialFamilyFactory.HEARTH_TIMBER),ContentKey("block",MaterialFamilyFactory.LAMP_LIT),ContentKey("item",MaterialFamilyFactory.WICK),ContentKey("mechanic",REPAIR))))
        return WorldContentBundleIO.create(theme.title,"可实测的材质工坊：六面木纹、四向路标、半透明铅玻璃、三种物件轮廓与真实换芯。",
            terrain,base.biomes,base.features,base.structures.copy(structures=base.structures.structures+workshop),theme,
            blocks=family.blocks,creatures=base.creatures,assets=base.assets+family.assets,
            items=base.items.copy(items=base.items.items+family.items.items),quests=base.quests,
            representativeContent=ContentKey("item",MaterialFamilyFactory.WICK),mechanics=base.mechanics.copy(mechanics=base.mechanics.mechanics+repair),abilities=base.abilities,story=base.story)
    }
    @JvmStatic fun blueprint(): StructureBlueprint {
        fun p(x:Int,y:Int,z:Int)=BuildPos(x,y,z)
        val palette=linkedMapOf("stone" to BuildMaterial("minecraft:stone_bricks"),"dark" to BuildMaterial("minecraft:deepslate_tiles"),
            "timber" to BuildMaterial("worldsmith:content/${MaterialFamilyFactory.HEARTH_TIMBER}"),
            "glass" to BuildMaterial("worldsmith:content/${MaterialFamilyFactory.LEADED_GLASS}"),
            "lamp" to BuildMaterial("worldsmith:content/${MaterialFamilyFactory.LAMP_UNLIT}",mapOf("facing" to "north")),
            "chest" to BuildMaterial("minecraft:chest",mapOf("facing" to "north")),
            "sign" to BuildMaterial("minecraft:oak_sign",mapOf("rotation" to "8")))
        listOf("north","east","south","west").forEach {palette["way_$it"]=BuildMaterial("worldsmith:content/${MaterialFamilyFactory.WAYSTONE}",mapOf("facing" to it))}
        return StructureBlueprint(id=STRUCTURE,size=p(15,7,13),origin=p(7,0,6),palette=palette,build=buildList {
            add(BuildOperation.Fill("floor",p(0,0,0),p(14,0,12),"stone")); add(BuildOperation.Clear("air",p(0,1,0),p(14,6,12)))
            add(BuildOperation.Fill("back_wall",p(0,1,12),p(14,4,12),"dark"))
            for(x in listOf(0,14))add(BuildOperation.Fill("post_$x",p(x,1,2),p(x,5,12),"timber"))
            add(BuildOperation.Fill("lintel",p(0,5,12),p(14,5,12),"timber"))
            add(BuildOperation.Fill("window",p(2,2,12),p(4,4,12),"glass"))
            listOf("north","east","south","west").forEachIndexed {i,face->
                add(BuildOperation.SetBlock("way_base_$i",p(2+i*3,1,3),"dark"));add(BuildOperation.SetBlock("way_$i",p(2+i*3,2,3),"way_$face"))
            }
            add(BuildOperation.SetBlock("lamp_base",p(7,1,9),"dark"));add(BuildOperation.SetBlock("lamp",LAMP_POSITION,"lamp"))
            add(BuildOperation.SetBlock("supplies",CHEST_POSITION,"chest"));add(BuildOperation.SetBlock("entry",p(7,1,1),"sign"))
            add(BuildOperation.SetBlock("instructions",p(9,1,8),"sign"))
        },keepClear=listOf(BuildBox(p(1,1,0),p(13,6,11))),access=StructureAccess(listOf(p(7,1,0)),listOf(p(7,1,7),p(11,1,7))),
            interactions=listOf(StructureInteraction.Container(CHEST_POSITION,items=listOf(StructureItem(0,"worldsmith:item/${MaterialFamilyFactory.WICK}",4),
                StructureItem(1,"worldsmith:item/${MaterialFamilyFactory.KEY}"),StructureItem(2,"worldsmith:item/${MaterialFamilyFactory.RIVET}",12))),
                StructureInteraction.Sign(p(7,1,1),listOf("归灯材料工坊","端面与长向木纹","四座石牌各朝一方","透过铅玻璃看天光")),
                StructureInteraction.Sign(p(9,1,8),listOf("箱中取归灯灯芯","主手持芯使用灯座","闭片开启见明芯","一次换芯真实发光"))))
    }
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==1) {"Usage: MaterialShowcaseExample <output-directory>"}
        val output=Path.of(args[0]).toAbsolutePath().normalize();Files.createDirectories(output)
        val family=MaterialFamilyFactory.create();val pack=create();WorldsmithResourceArchive.write(pack,output.resolve("hearth-material-workshop.wspack"))
        val previews=output.resolve("previews");Files.createDirectories(previews)
        family.blocks.blocks.forEach {block->Files.write(previews.resolve("${block.id}.png"),ContentAppearancePreview.block(block,family.assets).png())}
        Files.write(previews.resolve("items.png"),ContentAppearancePreview.items(family.items.items,family.assets).png())
        val recipes=output.resolve("recipes");Files.createDirectories(recipes)
        family.recipes.forEach {(name,recipe)->Files.writeString(recipes.resolve("$name.json"),WorldsmithJson.encode(recipe))}
        println("Material workshop ${pack.computedId}: ${family.blocks.blocks.size} blocks, ${family.items.items.size} icons, ${family.recipes.size} reproducible recipes")
    }
}
