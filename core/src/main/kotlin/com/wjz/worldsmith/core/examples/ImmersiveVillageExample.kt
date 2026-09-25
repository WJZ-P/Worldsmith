package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.ability.*
import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.story.*
import com.wjz.worldsmith.core.structure.*
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** A replayable engineering slice: actual anchors, a walkable route, three residents and one irreversible choice. */
object ImmersiveVillageExample {
    const val STRUCTURE = "hearth_route"
    const val VILLAGE = "hearth_village"
    const val RUIN = "old_beacon"
    const val KEEPER = "keeper"
    const val FORAGER = "forager"
    const val ARTISAN = "artisan"
    const val KEY = "hearth_key"
    const val INTRO = "hearth_intro"
    const val ROAD = "hearth_road"
    const val REKINDLE = "hearth_rekindle"
    const val REMEMBER = "hearth_remember"
    const val RETURN = "hearth_return"
    const val MET_KEEPER = "met_keeper"
    const val RUIN_SEEN = "ruin_seen"
    const val RESOLUTION = "beacon_resolution"
    const val ROUTE = "chosen_route"
    const val RETURNED = "returned_home"
    const val APPLIED = "beacon_applied"
    const val OATH_GIVEN = "oath_given"
    const val WITNESSED = "shared_outcome_witnessed"
    const val ROUTE_CLAIMED = "route_claimed"
    const val LIGHT_PROGRAM = "hearth_light_return"
    const val QUIET_PROGRAM = "hearth_quiet_return"
    @JvmField val VILLAGE_POSITION = BuildPos(12, 1, 14)
    @JvmField val RUIN_POSITION = BuildPos(12, 1, 55)
    @JvmField val KEEPER_POSITION = BuildPos(5, 1, 8)
    @JvmField val FORAGER_POSITION = BuildPos(5, 1, 17)
    @JvmField val ARTISAN_POSITION = BuildPos(19, 1, 8)
    @JvmField val LAMP_POSITION = BuildPos(12, 4, 16)
    @JvmField val SUPPLIES_POSITION = BuildPos(19, 1, 17)
    @JvmField val ENTRY_POSITION = BuildPos(12, 1, 0)

    @JvmStatic fun source(id: String): String {
        require(id == LIGHT_PROGRAM || id == QUIET_PROGRAM)
        return requireNotNull(javaClass.getResourceAsStream("/worldsmith/abilities/examples/$id.ability"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    @JvmStatic fun programs() = AbilityLibrary(programs = listOf(
        AbilityProgramDefinition(LIGHT_PROGRAM, "归灯之约", source(LIGHT_PROGRAM), maxTicks = 100, maxOperations = 2048),
        AbilityProgramDefinition(QUIET_PROGRAM, "留声之约", source(QUIET_PROGRAM), maxTicks = 100, maxOperations = 2048),
    ))
    private fun bool(id: String) = StoryCondition.Compare(StoryFactRef(id), StoryComparison.EQ, AbilityValue.BoolValue(true))
    private fun number(id: String, value: Int) = StoryCondition.Compare(StoryFactRef(id), StoryComparison.EQ, AbilityValue.NumberValue(value.toDouble()))
    private fun positive(id: String) = StoryCondition.Compare(StoryFactRef(id), StoryComparison.GT, AbilityValue.NumberValue(0.0))
    private fun all(vararg conditions: StoryCondition) = StoryCondition.All(conditions.toList())
    private fun change(id: String, value: Boolean) = StoryFactChange(StoryFactRef(id), AbilityValue.BoolValue(value))
    private fun change(id: String, value: Int) = StoryFactChange(StoryFactRef(id), AbilityValue.NumberValue(value.toDouble()))

    @JvmStatic fun create(): WorldsmithPack = create(20260917L)

    @JvmStatic fun create(seed: Long): WorldsmithPack {
        val base = MechanicDiscoveryExample.create()
        val terrain = base.terrain.copy(seed = seed, shape = (base.terrain.shape as TerrainShape.Procedural).copy(
            anchors = listOf(Anchor(STRUCTURE, AnchorPlacement.Fixed(0, 0), 160, AnchorRelief.Mesa(80, 0.75, 0.0),
                climateBias = AnchorClimateBias(continentalness = 0.6, erosion = 0.8)))))
        val keyTexture = base.items.items.single().textureAsset
        val assets = linkedMapOf(keyTexture to base.assets.getValue(keyTexture))
        val sourceModel = base.creatures.creatures.single().model
        fun resident(id: String, name: String, color: Int, role: String): CreatureDefinition {
            val texture = residentTexture(color); val hash = ContentAssetValidation.hash(texture); assets[hash] = texture
            return CreatureDefinition(id + "_resident", name, CreatureCategory.PASSIVE,
                sourceModel.copy(texture = hash, bones = sourceModel.bones + listOf(
                    CreatureBone("left_arm", parent = "body", pivot = CreatureVector(5f, -16f), role = CreatureBoneRole.ARM_LEFT,
                        cubes = listOf(CreatureCube(CreatureVector(-1f, -1f, -2f), CreatureVector(3f, 10f, 4f), CreatureUv(42, 0)))),
                    CreatureBone("right_arm", parent = "body", pivot = CreatureVector(-5f, -16f), role = CreatureBoneRole.ARM_RIGHT,
                        cubes = listOf(CreatureCube(CreatureVector(-2f, -1f, -2f), CreatureVector(3f, 10f, 4f), CreatureUv(42, 16)))),
                )), attributes = CreatureAttributes(health = 30.0, speed = .22, followRange = 12.0, attackDamage = 0.0, width = .7f, height = 1.6f),
                behavior = CreatureBehavior(territoryRadius = 16), themeRole = role,
                sounds = CreatureSoundProfile(voice = CreatureVoice.IRON_GOLEM, volume = .35f, ambientIntervalTicks = 800))
        }
        val creatures = CreatureLibrary(3, listOf(
            resident(KEEPER, "星禾 · 守灯人", 0xffc8ab68.toInt(), "记得迁徙缘由的石裔守灯人；接受旅人的道路选择。"),
            resident(ARTISAN, "砾石 · 铜匠", 0xff729eaa.toInt(), "为丢失钥匙的旅人重新铸钥；交易只消费实际随身铜锭。"),
            resident(FORAGER, "苔枝 · 采集人", 0xff73996a.toInt(), "把自己的猜想坦诚称为传说，并指明通往旧灯台的路。"),
        ))
        val items = CustomItemLibrary(items = listOf(CustomItemDefinition(KEY, "归灯铜钥", keyTexture,
            kind = CustomItemKind.RELIC, rarity = CustomItemRarity.UNCOMMON,
            description = "守灯人托付的铜钥。看过旧灯台、在日志选择道路后，交给守灯人。若不慎遗失，铜匠以三枚铜锭补铸。",
            themeRole = "有实际消耗、可由村庄交易恢复的旅程物件，不依赖击杀居民。")))
        val story = story()
        val quests = quests()
        val structures = StructureLibrary(schemaVersion = 3, structures = listOf(WorldStructureDefinition(STRUCTURE, blueprint(),
            StructurePlacement(biomes = base.biomes.biomes.map { it.id }, rotations = listOf(BuildRotation.NONE), anchor = StructureAnchorTarget(STRUCTURE),
                terrainFit = StructureTerrainFit(maxHeightDifference = 12,
                    foundation = StructureFoundation(FoundationMode.FILL, "stone", 16), earthwork = StructureEarthwork(8, 8192))))))
        val theme = WorldTheme(title = "归灯村", premise = "三个石裔住在旧灯台南下的石径旁。他们守着灯火的传说，也愿意听见一个不同的答案。",
            playerRole = "循路倾听、亲自选择如何安放旧日的旅人", mainConflict = "旧灯台曾照亮迁徙，也曾耗尽众人的积蓄；重燃与留念，都需要读懂它。",
            worldRules = listOf("见闻来自交谈和亲身抵达；传说不等同于事实。", "承诺一条道路会关闭另一条，但丢失铜钥仍有补铸途径。", "村民按昼夜往返居所，村庄会记住灯台的结局。",
                "石裔会受伤、会失去躯壳；记忆系在居所的锚石上。居所留有承重地面和两格净空时，他们会在稍候片刻后重新凝结，失落的历史仍会保留。"),
            beats = listOf(
                WorldNarrativeBeat("arrival", "借一盏归灯", "找到守灯人，听取委托，再把传说带到实地验证。", listOf(ContentKey("structure", STRUCTURE), ContentKey("quest", INTRO), ContentKey("item", KEY))),
                WorldNarrativeBeat("crossroads", "旧路的两种答案", "沿可行走的石径抵达旧灯台，看过证据再作承诺。", listOf(ContentKey("quest", ROAD), ContentKey("quest", REKINDLE), ContentKey("quest", REMEMBER))),
                WorldNarrativeBeat("homecoming", "世界记得", "带着已确认的选择回到村庄，听见回应，看见灯柱留下的变化。", listOf(ContentKey("quest", RETURN), ContentKey("ability", LIGHT_PROGRAM), ContentKey("ability", QUIET_PROGRAM))),
            ))
        return WorldContentBundleIO.create("归灯村 · 沉浸旅程样板", "一座有三位居民的小村、可步行抵达的旧灯台、两种结局与失物补铸；真实系统验收样板，非完整大世界或人类长程通关结论。",
            terrain, base.biomes, base.features, structures, theme, creatures = creatures, assets = assets, items = items, quests = quests,
            representativeContent = ContentKey("item", KEY), abilities = programs(), story = story)
    }

    @JvmStatic fun story(): StoryLibrary {
        val unresolved = number(RESOLUTION, 0)
        val lightChoice = all(bool(RUIN_SEEN), number(ROUTE, 1), unresolved)
        val quietChoice = all(bool(RUIN_SEEN), number(ROUTE, 2), unresolved)
        val facts = listOf(MET_KEEPER, RUIN_SEEN, RETURNED, OATH_GIVEN, WITNESSED, ROUTE_CLAIMED, "heard_forager", "village_known", "heard_after_loss").map {
            StoryFact(it, StoryFactScope.PLAYER, StoryFactType.BOOL, AbilityValue.BoolValue(false))
        } + listOf(
            StoryFact(ROUTE, StoryFactScope.PLAYER, StoryFactType.NUMBER, AbilityValue.NumberValue(0.0), 0.0, 2.0),
            StoryFact(RESOLUTION, StoryFactScope.WORLD, StoryFactType.NUMBER, AbilityValue.NumberValue(0.0), 0.0, 2.0),
            StoryFact(APPLIED, StoryFactScope.WORLD, StoryFactType.NUMBER, AbilityValue.NumberValue(0.0), 0.0, 2.0),
            StoryFact("beacon_applications", StoryFactScope.PLACE, StoryFactType.NUMBER, AbilityValue.NumberValue(0.0), 0.0, 2.0),
            StoryFact("keys_recast", StoryFactScope.PLAYER, StoryFactType.NUMBER, AbilityValue.NumberValue(0.0), 0.0, 1000000.0),
        ) + listOf(KEEPER, ARTISAN, FORAGER).map { StoryFact(it + "_was_lost", StoryFactScope.WORLD, StoryFactType.BOOL, AbilityValue.BoolValue(false)) }
        val places = listOf(
            StoryPlace(VILLAGE, "归灯村", "三座木屋围着村庄共同的灯柱。石裔循着昼夜的钟声往返屋舍与石径，也记得旅人说过的话。", STRUCTURE,
                discoveryRadius = 17, onDiscover = listOf(change("village_known", true)), clue = "三座木屋之间有共同的灯柱；右手边的铜匠能够补铸失物。", soundscape = "village_sound", required = true),
            StoryPlace(RUIN, "旧灯台", "旧石座旁的刻文记录了迁徙时消耗的铜与粮。灯火的代价是凡人的劳作，不是林中的诅咒。", STRUCTURE,
                discoveryRadius = 8, onDiscover = listOf(change(RUIN_SEEN, true)), clue = "从村中央灯柱沿灰白石径向南，跨过两段矮灯，再看见缺了一角的高柱。", soundscape = "ruin_sound", required = true),
        )
        fun routines(work: StoryOffset, activity: String) = listOf(
            StoryRoutine("day_work", 0, 12000, work, activity, speed = .55),
            StoryRoutine("night_home", 12000, 0, StoryOffset(), "回到屋内，等待下一次黎明。", speed = .5),
        )
        val characters = listOf(
            StoryCharacter(KEEPER, "星禾 · 守灯人", "keeper_resident", VILLAGE, "keeper_story", onDeath = listOf(change("keeper_was_lost", true)), respawnTicks = 1200,
                routines = routines(StoryOffset(5, 0, 2), "在灯柱附近整理旧日记忆。")),
            StoryCharacter(ARTISAN, "砾石 · 铜匠", "artisan_resident", VILLAGE, "artisan_story", onDeath = listOf(change("artisan_was_lost", true)), respawnTicks = 1200,
                routines = routines(StoryOffset(-5, 0, 3), "在屋前整理铜料，也为旅人补铸失物。")),
            StoryCharacter(FORAGER, "苔枝 · 采集人", "forager_resident", VILLAGE, "forager_story", onDeath = listOf(change("forager_was_lost", true)), respawnTicks = 1200,
                routines = routines(StoryOffset(5, 0, 0), "查看路旁的草木，辨认旅人留下的足迹。")),
        )
        val dialogues = listOf(
            StoryDialogue("keeper_story", "greeting", listOf(
                StoryDialogueNode("greeting", "我们守着旧灯台的名字，也记得旅人带回的答案。你想听听它的来历，还是谈谈这条路上发生的事？\n按 J 阅读委托，按 K 留下自己的见闻；不用急着相信每一句传说。", listOf(
                    StoryDialogueOption("hear_history", "请告诉我旧灯台的故事。", "history", changes = listOf(change(MET_KEEPER, true))),
                    StoryDialogueOption("discuss_beacon", "我到过旧灯台，想谈谈它的将来。", "decision", condition = all(bool(RUIN_SEEN), unresolved)),
                    StoryDialogueOption("return_light", "我想看看重燃之后的归灯村。", "ending_light", condition = all(number(APPLIED, 1), bool(ROUTE_CLAIMED)), changes = listOf(change(RETURNED, true))),
                    StoryDialogueOption("return_quiet", "我想听听你们怎样纪念旧灯台。", "ending_quiet", condition = all(number(APPLIED, 2), bool(ROUTE_CLAIMED)), changes = listOf(change(RETURNED, true))),
                    StoryDialogueOption("witness_light", "灯已经亮起了。我仍想说出自己的答案。", "witness_light", condition = all(number(APPLIED, 1), bool(RUIN_SEEN), positive(ROUTE), StoryCondition.Not(bool(OATH_GIVEN)), StoryCondition.Not(bool(WITNESSED)))),
                    StoryDialogueOption("witness_quiet", "晶石已经归位了。我仍想说出自己的答案。", "witness_quiet", condition = all(number(APPLIED, 2), bool(RUIN_SEEN), positive(ROUTE), StoryCondition.Not(bool(OATH_GIVEN)), StoryCondition.Not(bool(WITNESSED)))),
                    StoryDialogueOption("after_loss", "星禾，你真的重新凝结了？", "stone_oath", condition = bool("keeper_was_lost"), changes = listOf(change("heard_after_loss", true))),
                    StoryDialogueOption("leave", "我再去四周走走。"),
                )),
                StoryDialogueNode("history", "旧灯引领过一次迁徙。后来粮仓空了，灯也熄了；有人说是诅咒，有人说只是不再有多余的铜。\n我不想让你只凭我的记忆作决定。领取委托里的铜钥，循灰白石径走到旧灯台，再回来告诉我你读到了什么。", listOf(StoryDialogueOption("depart", "我会亲自去看。"))),
                StoryDialogueNode("decision", "刻文告诉了你代价。现在可以在旅程日志中选择一条道路：让村中重新有一盏归灯，或把旧灯留作记忆。\n承诺之后，把铜钥交给我。我们会一起承担这个选择，而不是等候一个标准答案。", listOf(
                    StoryDialogueOption("rekindle", "交出铜钥，让灯火重新照亮归路。", "light_committed", condition = lightChoice, trade = "rekindle_oath", program = LIGHT_PROGRAM),
                    StoryDialogueOption("remember", "交出铜钥，让晶石保存旧日的回声。", "quiet_committed", condition = quietChoice, trade = "remember_oath", program = QUIET_PROGRAM),
                    StoryDialogueOption("decide_later", "我先整理见闻，再作承诺。"),
                )),
                StoryDialogueNode("light_committed", "好。铜钥归位，灯柱会再次亮起。请先在日志领取这段旅程的馈赠，再来和我看看村庄的变化。", listOf(StoryDialogueOption("close", "我会回来。"))),
                StoryDialogueNode("quiet_committed", "好。晶石替代火光，我们仍认得回家的路。请先在日志领取这段旅程的馈赠，再来听听村庄的回应。", listOf(StoryDialogueOption("close", "我会回来。"))),
                StoryDialogueNode("witness_light", "这盏灯是村庄与先前来客共同作出的决定，不是你交出的铜钥。\n你愿意重燃，或更愿意安静留念，都可以坦诚告诉我。见证别人的选择，不等于放弃自己的看法；我们也不要求你再付一次代价。", listOf(
                    StoryDialogueOption("acknowledge", "我亲眼见到了改变，也愿意带着自己的承诺继续交谈。", "witnessed", changes = listOf(change(WITNESSED, true))))),
                StoryDialogueNode("witness_quiet", "晶石已经保存了旧路的回声，这是村庄与先前来客共同作出的决定。你的铜钥仍属于你。\n你愿意重燃，或更愿意安静留念，都可以保留自己的答案；承认已经发生的改变，不会抹去你的承诺。", listOf(
                    StoryDialogueOption("acknowledge", "我亲眼见到了改变，也愿意带着自己的承诺继续交谈。", "witnessed", changes = listOf(change(WITNESSED, true))))),
                StoryDialogueNode("witnessed", "谢谢你认真看过，也认真回应。把这一段见证记入旅程，领取你的馈赠后，再来听听归灯村的明天。", listOf(StoryDialogueOption("close", "我会带着自己的答案回来。"))),
                StoryDialogueNode("ending_light", "你看，灯柱重新亮了。我们仍要劳作，也仍会回家；这一次，大家知道灯火为何而燃。\n旧灯台的刻文没有改变，你带回来的理解却改变了归灯村。", listOf(StoryDialogueOption("farewell", "愿这盏灯照见每个人的归路。"))),
                StoryDialogueNode("ending_quiet", "你听，灯柱上的晶石会应风轻响。没有人被要求再次耗尽粮仓，旧路却没有被遗忘。\n一座村庄也可以用记得，而不是燃烧，向旧日致意。", listOf(StoryDialogueOption("farewell", "愿你们平静地记得，也自由地前行。"))),
                StoryDialogueNode("stone_oath", "我记得躯壳碎裂时的声音。这不是没发生过：你和村庄都会记得。\n石裔的记忆系在各自居所的锚石上。有人为归来的身体留出立足之地，我们便能重新凝结。若屋内被堵住，修好地面、留出两格空处，再回来看看。\n先前的约定还在；铜钥、旧灯台和你已经选定的道路，都不必从头来过。", listOf(StoryDialogueOption("continue", "那就带着记忆，继续我们的约定。"))),
            )),
            StoryDialogue("forager_story", "greeting", listOf(
                StoryDialogueNode("greeting", "石径尽头的风，总让我想起旧灯。我的猜想不少，可猜想只是猜想。你想听吗？", listOf(
                    StoryDialogueOption("listen", "说说你听过的传说。", "traces", changes = listOf(change("heard_forager", true))), StoryDialogueOption("leave", "下次再聊。"))),
                StoryDialogueNode("traces", "老人说林子收走了火光。我没有亲眼见过，只在旧石座旁发现记着铜和粮的刻文。\n沿村中灰白的石径向南走，路边的矮灯会陪着你；缺角的高柱就是旧灯台。把传说和亲眼所见分开记，会少迷一些路。", listOf(StoryDialogueOption("thank", "谢谢，我会分清传说与事实。"))),
            )),
            StoryDialogue("artisan_story", "greeting", listOf(
                StoryDialogueNode("greeting", "钥匙会遗失，人却不该因此被困在过去。我能补铸归灯铜钥：三枚铜锭，一把新钥。\n屋后的补给箱有一些铜；往后也可以从地下矿脉补充。先与星禾聊过，再来找我。", listOf(
                    StoryDialogueOption("replace_key", "用三枚铜锭补铸一把铜钥。", "recast", condition = bool(MET_KEEPER), trade = "replace_key"),
                    StoryDialogueOption("leave", "我先检查一下随身物品。"))),
                StoryDialogueNode("recast", "拿好。这次补铸已经消耗了三枚铜锭，也已把铜钥放进你的背包。若以后再丢失，仍可带铜料来找我。", listOf(StoryDialogueOption("thank", "谢谢，这条路还没有断。"))),
            )),
        )
        val knowledge = listOf(
            StoryKnowledge("keeper_account", "守灯人的委托", "星禾托付了一把铜钥，希望旅人亲自阅读旧灯台的刻文，再决定它的未来。", discoverWhen = bool(MET_KEEPER)),
            StoryKnowledge("forest_legend", "林子收走火光？", "苔枝转述了老人们的传说，并明确说自己并未亲眼见过。它是一个值得追问的说法，不是事实。", StoryTruth.LEGEND, bool("heard_forager")),
            StoryKnowledge("beacon_record", "刻文中的铜与粮", "旧石座记录着迁徙期间一次次投入的铜与粮。至少可以确认，灯台运行需要真实劳作与补给。", discoverWhen = bool(RUIN_SEEN)),
            StoryKnowledge("living_light", "共同承担的归灯", "村中灯柱重新亮起。你亲自见证并回应了这个结果，自己的承诺仍被保留。灯仍有代价，但不再由不知情的人独自承担。", discoverWhen = all(number(APPLIED, 1), bool(RETURNED))),
            StoryKnowledge("living_memory", "不再燃烧的记忆", "村庄把灯柱变作晶石纪念。你亲自见证并回应了这个结果，自己的承诺仍被保留。旧路没有被抛弃，也不再为传说耗空粮仓。", discoverWhen = all(number(APPLIED, 2), bool(RETURNED))),
            StoryKnowledge("stone_oath", "石裔留誓", "村里有一位石裔失去了躯壳。村口留誓刻文记载：记忆仍系在各自居所的锚石上，身体会在约一分钟后重新凝结。\n留在村中，保持原居所的承重地面与两格净空；若屋内被毁坏，先修复这处立足点，再回来寻找。若你暂时远行，归来后仍可前往原居所寻找。\n铜匠复归后仍可补铸铜钥；已经作出的选择与村庄的历史不因此重置。", discoverWhen = StoryCondition.Any(listOf(bool("keeper_was_lost"), bool("artisan_was_lost"), bool("forager_was_lost")))),
            StoryKnowledge("keeper_rewoven", "带着裂痕重逢", "你已经与复归的星禾再次交谈。他记得躯壳碎裂，也记得先前的约定。新的身体没有抹去发生过的事。", discoverWhen = bool("heard_after_loss")),
        )
        val trades = listOf(
            StoryTrade("replace_key", "补铸归灯铜钥", listOf(StoryItemAmount("minecraft:copper_ingot", 3)), listOf(StoryItemAmount("worldsmith:item/$KEY")), bool(MET_KEEPER),
                changes = listOf(StoryFactChange(StoryFactRef("keys_recast"), AbilityValue.NumberValue(1.0), StoryChangeMode.ADD)), cooldownTicks = 40),
            StoryTrade("rekindle_oath", "归灯之约", listOf(StoryItemAmount("worldsmith:item/$KEY")), listOf(StoryItemAmount("minecraft:lantern")), lightChoice,
                changes = listOf(change(RESOLUTION, 1), change(OATH_GIVEN, true)), maxUsesPerPlayer = 1),
            StoryTrade("remember_oath", "留声之约", listOf(StoryItemAmount("worldsmith:item/$KEY")), listOf(StoryItemAmount("minecraft:amethyst_shard")), quietChoice,
                changes = listOf(change(RESOLUTION, 2), change(OATH_GIVEN, true)), maxUsesPerPlayer = 1),
        )
        val soundscapes = listOf(
            StorySoundscape("village_sound", listOf(
                StorySoundLayer("waiting", "minecraft:block.bell.resonate", number(APPLIED, 0), volume = .12f, periodTicks = 1000, fadeTicks = 30, subtitle = "远处的旧钟轻轻回应。"),
                StorySoundLayer("living", "minecraft:music.overworld.meadow", number(APPLIED, 1), volume = .45f, periodTicks = 12000, fadeTicks = 100, priority = 5, music = true, subtitle = "归灯村重新有了温暖的旋律。"),
                StorySoundLayer("memory", "minecraft:block.amethyst_block.chime", number(APPLIED, 2), volume = .22f, periodTicks = 300, fadeTicks = 20, priority = 3, subtitle = "晶石的回声在村中停留。"),
            )),
            StorySoundscape("ruin_sound", listOf(StorySoundLayer("old_stones", "minecraft:ambient.cave", volume = .22f, periodTicks = 900, fadeTicks = 60, subtitle = "风掠过空旷的旧石座。"))),
        )
        val projections = listOf(1 to "minecraft:sea_lantern", 2 to "minecraft:amethyst_block").map { (outcome, block) ->
            StoryProjection("beacon_$outcome", VILLAGE, number(RESOLUTION, outcome),
                listOf(StoryBlockChange(StoryOffset(0, 3, 2), StoryBlockState("minecraft:polished_deepslate"), StoryBlockState(block)),
                    StoryBlockChange(StoryOffset(0, 2, 2), StoryBlockState("minecraft:stone_bricks"), StoryBlockState("minecraft:chiseled_stone_bricks"))),
                listOf(change(APPLIED, outcome), StoryFactChange(StoryFactRef("beacon_applications"), AbilityValue.NumberValue(1.0), StoryChangeMode.ADD)))
        }
        return StoryLibrary(facts = facts, places = places, characters = characters, dialogues = dialogues, knowledge = knowledge, trades = trades, soundscapes = soundscapes, projections = projections)
    }

    @JvmStatic fun quests() = QuestLibrary(quests = listOf(
        Quest(INTRO, "借一盏归灯", "找到村中暖色衣饰的守灯人星禾，听取旧灯台的来历。领取这份委托的馈赠，带上铜钥再出发。",
            objectives = listOf(QuestObjective.Fact("听取守灯人的委托", bool(MET_KEEPER))), rewards = listOf(QuestReward("worldsmith:item/$KEY"), QuestReward("minecraft:bread", 3)), themeBeat = "arrival", destination = VILLAGE),
        Quest(ROAD, "循灰白石径而行", "从共同的灯柱出发，沿灰白石径向南走过两段矮灯，亲自查看缺角高柱下的旧石座。记下见闻，再决定相信什么。", prerequisites = listOf(INTRO),
            objectives = listOf(QuestObjective.Fact("亲身抵达旧灯台", bool(RUIN_SEEN))), rewards = listOf(QuestReward("minecraft:copper_ingot", 2)), themeBeat = "crossroads", destination = RUIN),
        Quest(REKINDLE, "让归路重新明亮", "承诺重燃村中的灯火，再回到守灯人身边交出铜钥。若村庄已作出决定，请亲自见证改变并说出自己的回应；你的承诺仍会保留，也无需再次交钥。这个承诺会关闭留念道路。", prerequisites = listOf(ROAD),
            objectives = listOf(QuestObjective.Fact("见证村庄的改变并回应自己的承诺", all(positive(APPLIED), StoryCondition.Any(listOf(bool(OATH_GIVEN), bool(WITNESSED)))))), rewards = listOf(QuestReward("minecraft:bread", 3)), themeBeat = "crossroads",
            manualAccept = true, exclusiveGroup = "beacon_choice", onAccept = listOf(change(ROUTE, 1)), onClaim = listOf(change(ROUTE_CLAIMED, true)), destination = VILLAGE),
        Quest(REMEMBER, "让旧路被安静记得", "承诺用晶石纪念旧灯，再回到守灯人身边交出铜钥。若村庄已作出决定，请亲自见证改变并说出自己的回应；你的承诺仍会保留，也无需再次交钥。这个承诺会关闭重燃道路。", prerequisites = listOf(ROAD),
            objectives = listOf(QuestObjective.Fact("见证村庄的改变并回应自己的承诺", all(positive(APPLIED), StoryCondition.Any(listOf(bool(OATH_GIVEN), bool(WITNESSED)))))), rewards = listOf(QuestReward("minecraft:honey_bottle")), themeBeat = "crossroads",
            manualAccept = true, exclusiveGroup = "beacon_choice", onAccept = listOf(change(ROUTE, 2)), onClaim = listOf(change(ROUTE_CLAIMED, true)), destination = VILLAGE),
        Quest(RETURN, "再听一次村庄的回答", "领取所选道路的馈赠后，再与守灯人谈谈改变之后的归灯村。你的旅程留下的不只是背包里的物品。", prerequisites = listOf(REKINDLE, REMEMBER),
            objectives = listOf(QuestObjective.Fact("听见改变后的归灯村", bool(RETURNED))), rewards = listOf(QuestReward("minecraft:clock")), themeBeat = "homecoming", destination = VILLAGE, prerequisiteMode = QuestPrerequisiteMode.ANY),
        Quest("hearth_forager", "把传说与事实分开", "听听采集人苔枝的说法，并把未证实的传说作为传说记下。这段支线不阻止你的主线旅程。",
            objectives = listOf(QuestObjective.Fact("听取苔枝的传说", bool("heard_forager"))), rewards = listOf(QuestReward("minecraft:apple", 2)), themeBeat = "arrival",
            optional = true, manualAccept = true, discoverWhen = bool(MET_KEEPER), destination = VILLAGE),
        Quest("hearth_reweave", "带着裂痕重逢", "星禾失去了躯壳，村口的石裔留誓说明了复归的方法。保持他的居所地面完整、脚下与头顶留空，在村中稍候片刻；亲自与复归的守灯人再次交谈。",
            objectives = listOf(QuestObjective.Fact("与复归的守灯人再次交谈", bool("heard_after_loss"))), rewards = listOf(QuestReward("minecraft:bread", 2)), themeBeat = "arrival",
            optional = true, discoverWhen = bool("keeper_was_lost"), destination = VILLAGE),
    ))

    @JvmStatic fun blueprint(): StructureBlueprint {
        fun p(x: Int, y: Int, z: Int) = BuildPos(x, y, z)
        val build = mutableListOf<BuildOperation>(
            BuildOperation.Fill("meadow_datum", p(0, 0, 0), p(24, 0, 62), "grass"),
            BuildOperation.Clear("route_headroom", p(0, 1, 0), p(24, 10, 62)),
            BuildOperation.Fill("village_path", p(10, 0, 0), p(14, 0, 62), "path"),
        )
        fun cottage(id: String, x: Int, z: Int, eastDoor: Boolean) {
            build += BuildOperation.Fill("${id}_floor", p(x, 0, z), p(x + 6, 0, z + 7), "planks")
            build += BuildOperation.Fill("${id}_walls", p(x, 1, z), p(x + 6, 4, z + 7), "wood")
            build += BuildOperation.Clear("${id}_room", p(x + 1, 1, z + 1), p(x + 5, 3, z + 6))
            val doorX = if (eastDoor) x + 6 else x
            build += BuildOperation.Clear("${id}_door", p(doorX, 1, z + 4), p(doorX, 2, z + 4))
            build += BuildOperation.Fill("${id}_roof", p(x, 5, z), p(x + 6, 5, z + 7), "roof")
            build += BuildOperation.Fill("${id}_ridge", p(x + 2, 6, z + 1), p(x + 4, 6, z + 6), "roof")
            build += BuildOperation.SetBlock("${id}_window", p(x + 3, 2, z), "glass")
            build += BuildOperation.SetBlock("${id}_warmth", p(x + 3, 4, z + 3), "warm")
        }
        cottage(KEEPER, 2, 4, true); cottage(ARTISAN, 16, 4, false); cottage(FORAGER, 2, 13, true)
        build += listOf(
            BuildOperation.Fill("village_lamp_pole", p(12, 1, 16), p(12, 3, 16), "stone"),
            BuildOperation.SetBlock("village_unlit_lamp", LAMP_POSITION, "dark"),
            BuildOperation.SetBlock("supply_chest", SUPPLIES_POSITION, "chest"),
            BuildOperation.SetBlock("entry_sign", p(10, 1, 2), "sign"),
            BuildOperation.SetBlock("stone_oath_sign", p(14, 1, 2), "sign"),
            BuildOperation.Fill("ruin_floor", p(6, 0, 49), p(18, 0, 62), "mossy"),
            BuildOperation.Fill("ruin_west", p(6, 1, 49), p(6, 3, 62), "mossy"),
            BuildOperation.Fill("ruin_east", p(18, 1, 49), p(18, 2, 62), "mossy"),
            BuildOperation.Fill("old_beacon_pillar", p(16, 1, 59), p(16, 8, 59), "stone"),
            BuildOperation.Fill("broken_beacon_cap", p(15, 9, 59), p(16, 9, 60), "dark"),
            BuildOperation.SetBlock("old_beacon_seat", p(12, 1, 58), "inscription"),
            BuildOperation.SetBlock("ruin_sign", p(10, 1, 53), "sign"),
        )
        for (z in listOf(5, 22, 32, 42, 51)) for (x in listOf(9, 15)) {
            build += BuildOperation.SetBlock("road_light_${x}_$z", p(x, 0, z), "warm")
        }
        return StructureBlueprint(id = STRUCTURE, size = p(25, 11, 63), origin = p(12, 0, 10),
            palette = mapOf("grass" to BuildMaterial("minecraft:grass_block"), "stone" to BuildMaterial("minecraft:stone_bricks"),
                "path" to BuildMaterial("minecraft:gravel"), "planks" to BuildMaterial("minecraft:oak_planks"), "wood" to BuildMaterial("minecraft:stripped_oak_log"),
                "roof" to BuildMaterial("minecraft:spruce_planks"), "glass" to BuildMaterial("minecraft:glass"), "warm" to BuildMaterial("minecraft:ochre_froglight"),
                "dark" to BuildMaterial("minecraft:polished_deepslate"), "mossy" to BuildMaterial("minecraft:mossy_stone_bricks"), "inscription" to BuildMaterial("minecraft:chiseled_stone_bricks"),
                "chest" to BuildMaterial("minecraft:chest", mapOf("facing" to "west")), "sign" to BuildMaterial("minecraft:oak_sign", mapOf("rotation" to "8"))),
            build = build, keepClear = listOf(BuildBox(p(0, 1, 0), p(24, 10, 62))),
            access = StructureAccess(listOf(ENTRY_POSITION), listOf(VILLAGE_POSITION, RUIN_POSITION, KEEPER_POSITION, ARTISAN_POSITION, FORAGER_POSITION, p(18, 1, 17))),
            interactions = listOf(
                StructureInteraction.StoryAnchor(VILLAGE_POSITION, VILLAGE), StructureInteraction.StoryAnchor(RUIN_POSITION, RUIN),
                StructureInteraction.StoryAnchor(KEEPER_POSITION, VILLAGE, KEEPER), StructureInteraction.StoryAnchor(ARTISAN_POSITION, VILLAGE, ARTISAN), StructureInteraction.StoryAnchor(FORAGER_POSITION, VILLAGE, FORAGER),
                StructureInteraction.Container(SUPPLIES_POSITION, items = listOf(StructureItem(0, "minecraft:copper_ingot", 18), StructureItem(1, "minecraft:bread", 8))),
                StructureInteraction.Sign(p(10, 1, 2), listOf("归灯村", "进屋与居民交谈", "J：旅程 K：见闻", "沿石径向南寻旧灯")),
                StructureInteraction.Sign(p(14, 1, 2), listOf("石裔留誓", "躯壳散，记忆留", "居所留空，稍候复归", "地面若毁，先修再等")),
                StructureInteraction.Sign(p(10, 1, 53), listOf("迁徙第三十日", "铜尽，粮亦将空", "今夜停灯以养众", "归路留待来人")),
            ))
    }

    /** Compact deterministic fixture atlas, kept as generated source material rather than claimed final character art. */
    private fun residentTexture(color: Int): ByteArray {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        for (x in 0..63) for (y in 0..63) {
            val base = if (y in 18..31) 0xffbcb5a1.toInt() else color
            val shade = if ((x / 4 + y / 4) % 2 == 0) 1.0 else .82
            image.setRGB(x, y, (0xff shl 24) or (((base shr 16 and 255) * shade).toInt() shl 16) or (((base shr 8 and 255) * shade).toInt() shl 8) or ((base and 255) * shade).toInt())
        }
        return ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: ImmersiveVillageExample [output-directory]" }
        val output = Path.of(args.firstOrNull() ?: "build/immersion-runtime/sample-village").toAbsolutePath().normalize()
        val pack = create(); val errors = WorldsmithPackValidator.validate(pack).filter { it.severity == DiagnosticSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString("\n") { "${it.path}: ${it.message}" } }
        val geometry = StructureGeometryCompiler.compile(pack.structures.structures.single().blueprint)
        val archive = output.resolve("hearth-village.wspack"); val info = WorldsmithResourceArchive.write(pack, archive)
        val encoded = WorldContentBundleIO.encode(pack); val bundle = output.resolve("hearth-village-bundle")
        (encoded.texts + ("worldsmith.json" to WorldsmithJson.encode(encoded.manifest))).forEach { (name, text) -> bundle.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) } }
        encoded.binaries.forEach { (name, bytes) -> bundle.resolve(name).also { Files.createDirectories(it.parent); Files.write(it, bytes) } }
        programs().programs.forEach { definition -> output.resolve("sources/${definition.id}.ability").also { Files.createDirectories(it.parent); Files.writeString(it, definition.source) } }
        require(WorldsmithResourceArchive.read(archive).pack.computedId == pack.computedId)
        require(WorldsmithPackLoader.loadDirectory(bundle).computedId == pack.computedId)
        println("archive=$archive\nbundle=$bundle\nbundleId=${pack.computedId}\narchiveSha256=${info.archiveSha256}\nreachableFeet=${geometry.reachableFeet.size}\nmarkers=${geometry.interactions.count { it is StructureInteraction.StoryAnchor }}")
    }
}
