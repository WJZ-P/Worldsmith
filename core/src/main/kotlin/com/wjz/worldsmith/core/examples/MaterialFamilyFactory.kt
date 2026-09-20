package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.mcp.TextureOperation
import com.wjz.worldsmith.core.mcp.TextureRecipe
import com.wjz.worldsmith.core.mcp.TextureRecipes

/** A coherent material vocabulary made only from portable pixel recipes, with no external image service. */
object MaterialFamilyFactory {
    const val HEARTH_TIMBER = "hearth_timber"
    const val WAYSTONE = "hearth_waystone"
    const val LAMP_UNLIT = "hearth_lamp_unlit"
    const val LAMP_LIT = "hearth_lamp_lit"
    const val LEADED_GLASS = "hearth_leaded_glass"
    const val KEY = "hearth_key_icon"
    const val WICK = "hearth_wick"
    const val RIVET = "hearth_rivet"
    data class Family(val blocks: CustomBlockLibrary, val items: CustomItemLibrary,
        val assets: Map<String, ByteArray>, val recipes: Map<String, TextureRecipe>)

    @JvmStatic fun create(): Family {
        val assets = linkedMapOf<String, ByteArray>(); val recipes = linkedMapOf<String, TextureRecipe>()
        fun texture(name: String, palette: List<String>, operations: List<TextureOperation>): String {
            val recipe = TextureRecipe(width = 16, height = 16, palette = palette, seed = 20260918L, operations = operations)
            val asset = TextureRecipes.render(recipe); assets[asset.descriptor.id] = asset.bytes; recipes[name] = recipe
            return asset.descriptor.id
        }
        val wood = listOf("#67452E", "#89603B", "#AD814A", "#3C3028", "#CAA572")
        val bark = texture("timber_side", wood, buildList {
            add(fill(0,0,16,16,0)); add(fill(1,0,4,16,1)); add(fill(8,0,5,16,1))
            add(line(2,0,2,6,2)); add(line(9,0,9,9,2)); add(line(12,10,12,15,2))
            add(line(5,0,5,15,3)); add(line(14,0,14,15,3)); add(line(2,10,2,15,2))
            add(line(10,5,12,7,3)); add(line(12,7,10,9,3)); add(line(10,9,9,7,3)); add(line(9,7,10,5,3))
        })
        val end = texture("timber_end", wood, listOf(fill(0,0,16,16,3), fill(1,1,14,14,1), fill(2,2,12,12,2),
            fill(4,4,8,8,0), fill(5,5,6,6,2), fill(7,7,2,2,0), line(1,14,6,9,3), line(15,3,11,7,1)))
        val stone = listOf("#515D66", "#64717A", "#35424C", "#A77943", "#DAB56C", "#E7DCC0", "#F8DB83", "#A8E2CE")
        val masonry = listOf(fill(0,0,16,16,0), fill(0,1,16,6,1), fill(0,9,16,6,1),
            line(0,0,15,0,2), line(0,8,15,8,2), line(7,0,7,7,2), line(1,8,1,15,2), line(8,1,15,1,0), line(2,9,15,9,0))
        val wall = texture("stone_back", stone, masonry)
        val top = texture("lamp_cap", stone, listOf(fill(0,0,16,16,2), fill(1,1,14,14,3), fill(2,2,12,12,0),
            fill(4,4,8,8,1), fill(6,6,4,4,3), fill(7,7,2,2,4)))
        val route = texture("waystone_front", stone, listOf(fill(0,0,16,16,2), fill(1,1,14,14,3), fill(2,2,12,12,0),
            fill(7,5,2,8,5), fill(5,5,6,2,5), fill(6,4,4,2,5), fill(7,3,2,2,5), fill(4,12,8,1,3)))
        val closed = texture("lamp_closed", stone, listOf(fill(0,0,16,16,2), fill(1,1,14,14,3), fill(2,2,12,12,2),
            fill(4,3,8,10,0), fill(5,4,6,2,1), fill(5,7,6,2,1), fill(5,10,6,2,1),
            fill(7,6,2,1,3), fill(7,9,2,1,3), fill(2,2,1,1,4), fill(13,13,1,1,4)))
        val open = texture("lamp_open", stone, listOf(fill(0,0,16,16,2), fill(1,1,14,14,3), fill(2,2,12,12,2),
            fill(3,3,2,10,3), fill(11,3,2,10,3), fill(6,4,4,8,4), fill(5,6,6,4,4),
            fill(7,3,2,10,6), fill(6,5,4,6,6), fill(7,5,2,6,5), fill(2,2,1,1,4), fill(13,13,1,1,4)))
        val glass = texture("leaded_glass", listOf("#A6D9C85C", "#40585AFF", "#D0AF70FF", "#DAF8E48C"),
            listOf(fill(0,0,16,16,0), line(0,0,15,0,1), line(0,0,0,15,1), line(0,8,8,0,1), line(8,0,15,7,1),
                line(15,8,8,15,1), line(8,15,0,7,1), fill(7,7,2,2,2), fill(3,2,2,1,3)))
        val metal = listOf("#302D31", "#9F6639", "#D8A355", "#F4D691", "#F5E8C8", "#826A51")
        val key = texture("key_icon", metal, listOf(stamp(listOf(
            "................", ".....00000......", "....0123210.....", "...012...210....",
            "...023...320....", "...012...210....", "....0123210.....", ".....02320......",
            "......0320......", "......0320......", "......03200.....", "......032320....",
            "......03200.....", "......012320....", ".......0000.....", "................"))))
        val wick = texture("wick_icon", metal, listOf(stamp(listOf(
            "................", ".......00.......", "......0340......", "......0340......",
            ".....034440.....", ".....034440.....", "......0340......", ".......00.......",
            ".....011110.....", "....01233210....", "....02344320....", "....02344320....",
            "....01233210....", ".....011110.....", "......0000......", "................"))))
        val rivet = texture("rivet_icon", metal, listOf(stamp(listOf(
            "................", "................", "...000000000....", "..01233333210...",
            "..02344444320...", "..01222222210...", "...000220000....", ".....0220.......",
            ".....0230.......", ".....0230.......", ".....0230.......", ".....0230.......",
            ".....0120.......", "......00........", "................", "................"))))
        fun lamp(front: String) = BlockAppearance(BlockFaceTexture(top), BlockFaceTexture(wall), BlockFaceTexture(front),
            BlockFaceTexture(wall), BlockFaceTexture(wall), BlockFaceTexture(wall), wall, BlockOrientation.HORIZONTAL)
        val blocks = CustomBlockLibrary(blocks = listOf(
            CustomBlockDefinition(HEARTH_TIMBER, "归灯梁木", CustomBlockProfile.WOOD,
                BlockAppearance(BlockFaceTexture(end), BlockFaceTexture(end,2), BlockFaceTexture(bark), BlockFaceTexture(bark), BlockFaceTexture(bark), BlockFaceTexture(bark), bark), themeRole = "梁端年轮与长向树皮，材质方向不依赖颜色区分。"),
            CustomBlockDefinition(WAYSTONE, "归路刻石", CustomBlockProfile.STONE, lamp(route), themeRole = "正面浅刻箭纹指向道路，可按放置朝向转向。"),
            CustomBlockDefinition(LAMP_UNLIT, "归灯座 · 闭芯", CustomBlockProfile.STONE, lamp(closed), themeRole = "铜框关闭的三道灯芯护片；等待实际修复。"),
            CustomBlockDefinition(LAMP_LIT, "归灯座 · 明芯", CustomBlockProfile.STONE, lamp(open), light = 13, themeRole = "修复后护片打开，亮芯显露且真实发光。"),
            CustomBlockDefinition(LEADED_GLASS, "菱铅归灯玻璃", CustomBlockProfile.GLASS, BlockAppearance.uniform(glass), themeRole = "有铅线和细小琥珀节点的半透明菱格。"),
        ))
        val items = CustomItemLibrary(items = listOf(
            CustomItemDefinition(KEY, "刻印归灯钥", key, kind = CustomItemKind.RELIC, rarity = CustomItemRarity.UNCOMMON, description = "中空钥环与两枚钥齿；铜匠使用的归灯印式。"),
            CustomItemDefinition(WICK, "归灯灯芯", wick, description = "上方纤维束、下方铜托；用于替换闭芯灯座的旧灯芯。"),
            CustomItemDefinition(RIVET, "錾头铜铆钉", rivet, description = "宽头与长脚把木梁和灯框固定在一起。"),
        ))
        return Family(blocks, items, java.util.Collections.unmodifiableMap(assets), java.util.Collections.unmodifiableMap(recipes))
    }
    private fun fill(x:Int,y:Int,width:Int,height:Int,color:Int)=TextureOperation("fill",x,y,width,height,color)
    private fun line(x:Int,y:Int,x2:Int,y2:Int,color:Int)=TextureOperation("line",x=x,y=y,color=color,x2=x2,y2=y2)
    private fun stamp(rows:List<String>)=TextureOperation("stamp",rows=rows,glyphs=(0..5).associate {it.toString() to it})
}
