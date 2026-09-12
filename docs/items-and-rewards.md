# 世界物品、装备能力与奖励获取链路

当前新发布使用 **世界包格式 6**、九个模块。`items.schemaVersion=1` 保留资源／遗物；
显式使用 **schemaVersion=2** 可组合原生装备、消耗品与固定动作库。物品仍是同一套世界身份，
不是每件生成独立 Java 类。准确字段和可提交示例见 [items 创作契约](../core/src/main/resources/prompts/contract/items.system.md)。

## 定义与贴图

`CustomItemDefinition` 的公共字段是：id、displayName、textureAsset、kind、maxStackSize、
rarity、description、themeRole，以及可选 equipment、consumable、actions。
`kind` 仍只有 RESOURCE / RELIC，描述用途而不授予能力；rarity 为 COMMON / UNCOMMON / RARE / EPIC。
普通材料完全可以没有动作，装备和可交互遗物应服务于真实探索、战斗或补给需要。

- 每库最多256项；ID为1..64位小写本地标识，排除连续两点和尾点；名称1..128个可打印字符。
- 图标引用已上传真实 PNG 的 SHA-256，尺寸16..256、二次幂正方形，最多1 MiB，支持透明。
- maxStackSize 为1..64；有 equipment 的物品必须为1。
- description 是玩家可见 lore，最多2048字符／256行；themeRole 是创作关联，不执行行为。
- equipment 与 consumable 互斥；actions 默认空，最多2项且每个触发器最多1项。

`ItemEquipment.type` 为 MELEE、AXE、PICKAXE、SHOVEL、HOE、HELMET、CHESTPLATE、LEGGINGS、BOOTS。
其余字段为 durability=256、attackDamage=3、attackSpeed=-2.4、armor=0、toughness=0、
knockbackResistance=0、miningSpeed=4、miningTier=IRON、textureAsset=null。

耐久1..100000；攻击增量0..1024、攻速增量-3.9..20；护甲0..30、韧性0..20、抗击退0..1；
采掘速度0.1..128。所有数值有限，并通过原生属性实际范围检查。
攻击属性是增量：通常 attackDamage=6 对应总攻击7，attackSpeed=-2.4 对应总攻速1.6。
采掘等级只有 WOOD / STONE / IRON / DIAMOND / NETHERITE，沿用真实原版工具标签和掉落等级。
MELEE 使用原生铁剑的采掘配置；其 miningTier/miningSpeed 不是另一套剑采掘定义。
斧、锹、锄沿用原生方块右键操作；点击方块时这类操作可先于主动能力，朝空处使用可触发 USE。

护甲的 `equipment.textureAsset` 必填，且与物品图标分别绘制：使用人形护甲UV图集，
只接受 **64x32、128x64、256x128、512x256**，每张最多1 MiB。
头盔／胸甲／靴使用 humanoid 图层，护腿使用 humanoid_leggings；不是生物UV或放大的物品图标。
非护甲不填此字段。普通右键穿戴；**手持护甲时潜行右键**调用其可选 USE 动作。
没有穿着期间自动循环、ON_EQUIP、盾、弓、弩等额外 type 或触发字段。

## 消耗品与触发时机

`ItemConsumable` 字段为 nutrition=0（0..20）、saturation=0（0..20）、consumeSeconds=1.6（0.1..10）、
alwaysEdible=false、effects=[]（最多8项）。saturation 是原生实际饱和度数量，不是营养值乘数。
每个 `ItemStatusEffect` 仅有 effect、durationTicks=200、amplifier=0：effect 指真实状态效果ID，
持续1..72000 tick，强度为从0开始的0..9。原生准备阶段解析所有效果ID。

消耗品沿用原生进食完成流程。可选 USE 动作在**完成食用**时触发，不在按下右键时触发；
正常生存食用已消耗1件，所以食物动作必须 `consumeCount=0`、`durabilityCost=0`，且只允许 USE。
中断进食或完成动作失败时不完成消耗；动作失败也不进入冷却。创造模式保持原生免消耗行为。

## 固定动作组合

`ItemAction` 字段：trigger=USE、cooldownTicks=20（1..72000）、consumeCount=0（0..maxStackSize）、
durabilityCost=0（不超过装备总耐久；非装备仅0）、effects（必填，1..8项）。
触发器只有 USE / MELEE_HIT。**MELEE_HIT 仅用于非护甲装备**，即 MELEE／AXE／PICKAXE／SHOVEL／HOE；
普通材料、无装备遗物、护甲可选择 USE，不获得 MELEE_HIT。没有任意脚本、命令或实体NBT入口。

| kind | 字段与范围 |
| --- | --- |
| heal | amount 必填0.1..100；target 默认SELF，可为TARGET |
| feed | nutrition 必填0..20；saturation 默认0、0..20；作用于自己 |
| status | effect 必填；durationTicks 默认200、1..72000；amplifier 默认0、0..9；target 默认SELF |
| projectile | damage 默认4、0..100；speed 默认1.5、0.1..4；gravity 默认0.03、0..0.2；lifetimeTicks 默认80、1..200；hitEffects 最多4项 ItemStatusEffect |
| blink | distance 默认6、0.1..8；仅USE |

TARGET 只用于 MELEE_HIT 的 heal/status，指被击中的生物；SELF 指使用者。
一个动作最多一个 projectile 和一个 blink。投射物使用统一原生宿主，外观取来源物品；
命中仅直接伤害／状态，不爆破地形，不配置另一套投射物类或任意模型字段。

短距位移在视线方向寻找已加载区块内、边界内、有落脚支撑、无碰撞和液体的位置，最多8格。
不加载新区块、不穿墙、不跨维度；骑乘／睡眠或没有有效落点时动作失败。
能力解析、落点及投射物插入先于动作自有消耗；失败不扣动作耗材、额外耐久或动作冷却。
MELEE_HIT 在原生近战处理之后执行，这不回滚已经发生的普通攻击或原生武器磨损。

冷却以**每玩家＋世界scope＋逻辑item ID**分组：同一物品的所有堆栈和两个触发器共享冷却，
不同逻辑物品不会因为共用一个宿主而互相锁住。合法能力物品跳过宿主的原生自动冷却，
由成功的 ItemActions 唯一结算。创造模式免动作数量／耐久消耗，但仍受冷却与落点条件约束。

## 获取、身份和存档

引用采用原生物品ID、`worldsmith:content/<blockId>` 或 `worldsmith:item/<itemId>`。
同一原生宿主的每个堆栈保存世界／逻辑ID及完整模型、名称、稀有度、叠加、装备、消耗和动作组件。
奖励构造必须保留完整堆栈；只拿共同 Item 类型会丢失物品身份。
原生耐久、修理、附魔、重命名属于合法变化；异世界同名物品不会获得当前世界的能力。

- **生物掉落**：沿用 drops 的 item、minCount、maxCount、chance、requirePlayerKill；最多16条独立规则，
  每条成功产生一堆，数量1..64且符合实际堆叠限制。装备每条数量为1；依照原生死亡与掉落规则结算。
- **建筑奖励**：固定容器 items 和 inline loot 保留完整组件；固定库存、inline loot、外部lootTable三者互斥。
- **任务奖励**：quests 仍是显式交付与单独领奖的线性主线；schema2物品可直接作为来源／奖励。
  获得、穿戴或使用物品不会凭描述新增任务目标、合成树、分支剧情；详见 [主线任务](mainline-quests.md)。

物品库和图标／护甲图随世界包嵌入存档。创造分类展示当前世界的真实绑定物品。
`worldsmith_write_pack` 的可选 `representativeContent: {"kind":"item","id":"local_id"}`
可指定最有代表性的物品，也可 kind=block。它写入清单，不是 items/theme 的新字段；浏览器独立读取其PNG，
不为预览切换世界或重载资源。旧包缺少代表引用时，浏览器从已有遗物／任务物品中稳定挑选，不改写原包。

## 玩家文案与格式兼容

未来 displayName／description 只写世界故事或真实玩法：地点、意义、使用动作、效果和代价。
技术限制、包内进度边界、地形校验、发布／编译结果仅写作者诊断与回执，不追加到玩家lore。
新创作发布通过 `PlayerTextPolicy` 检查已知泄漏形式，返回 `PLAYER_TEXT_ENGINEERING_LEAK` 及字段路径，
由作者修正文案；不是展示时偷偷删句，也不把新文案规则施加给旧包加载。

新写入为格式6的九模块；schema2能力只出现在格式6。格式3/4/5保留只读恢复及原身份重新封装：
格式3为七模块、没有items/quests；格式4为八模块、普通items与drops；格式5为九模块、已有任务和Boss，
items保持schema1。`LegacyItemsV1` 固定旧物品投影，避免新默认字段改变旧哈希。
旧世界包、嵌入内容、玩家进度与原有文案均不自动迁移或改写。
