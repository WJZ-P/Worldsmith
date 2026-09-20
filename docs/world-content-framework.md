# Worldsmith 世界内容地基：格式 10

## 目标与真实边界

一个世界拥有一个持续保存的主线主题；地形、群系、生态装饰、建筑、自定义方块和自定义生物引用同一套内容身份与冻结资产。
创作阶段可以迭代任意模块，发布阶段必须形成可校验的完整内容快照。游戏运行阶段消费冻结数据和原生实现，不在方块、区块或实体 tick 中重新编译源码或调用大模型；abilities 的有界字节码由共享服务端运行时执行。

当前读写仅接受**格式 10**；旧格式 1–9 被显式拒绝。旧模型 `WorldsmithPackFiles` 已移除，存储和加载统一读取 `manifest.modules`。不遍历、迁移或改写本地旧包与存档。

公共层已经实现：

- 十二个有类型的模块：`theme`、`blocks`、`terrain`、`features`、`biomes`、`creatures`、`structures`、`items`、`quests`、`mechanics`、`abilities`、`story`。
- 本地符号、跨模块引用、确定性编译依赖、能力与生命周期要求。
- PNG 字节验证、内容寻址、资源预算，以及包含全部模块和资产的不可变内容哈希。
- 新格式加载、统一编码、类型化内存投影、主题持久化与损坏诊断。
- 有界分支 quests 模块已安装，并派生世界专属原版进度树；独立 achievements 创作模块仍未安装，不新增该模块数据。
- items schema 2 支持原生装备、独立护甲贴图、消耗品及固定动作组合；theme 为 schema 1，quests 使用 schema 2。story schema 2 统一持久事实、地点角色、对话、知识、交易和环境音。

**内容有效、具备宿主能力、完成准备、实际激活是四件不同的事。** 本文的编译计划和内容测试不等于游戏内验收。

## 模块与清单

`worldsmith.json` 的结构如下；省略号仅供阅读，新格式实际文件必须给出十二个模块及完整哈希：

```json
{
  "formatVersion": 10,
  "id": "<64 位小写 SHA-256>",
  "displayName": "月石诸国",
  "description": "月石沉眠于群山之间，重燃旧日烽火的旅人将连接失散的诸国。",
  "representativeContent": {"kind":"item","id":"moonstone_seal"},
  "modules": {
    "theme": {"schemaVersion": 1, "path": "theme.json"},
    "blocks": {"schemaVersion": 2, "path": "blocks.json"},
    "terrain": {"schemaVersion": 1, "path": "terrain.json"},
    "features": {"schemaVersion": 1, "path": "features.json"},
    "biomes": {"schemaVersion": 1, "path": "biomes.json"},
    "creatures": {"schemaVersion": 1, "path": "creatures.json"},
    "items": {"schemaVersion": 2, "path": "items.json"},
    "quests": {"schemaVersion": 2, "path": "quests.json"},
    "mechanics": {"schemaVersion": 1, "path": "mechanics.json"},
    "abilities": {"schemaVersion": 1, "path": "abilities/abilities.json"},
    "story": {"schemaVersion": 2, "path": "story/story.json"},
    "structures": {"schemaVersion": 3, "path": "structures.json"}
  },
  "assets": []
}
```

十二个模块都出现；非引导的底层内容包允许空方块、生物和建筑库。MCP 的引导创作仍保留现有建筑质量契约：至少两个建筑群、独立建筑与一个主题地标，发布时再次验证；底层格式与引导流程的约束不是同一层。
结构模块继续以 `StructureIndex` 加独立蓝图文件和冻结绘图二进制保存，在内存中投影为 `StructureLibrary`。其他模块各自保存类型化文档。
items 只含普通物品时仍可使用 schema 1；装备、消耗和固定效果要求 schema 2；共享程序调用要求 schema 3。
可选 `manifest.representativeContent` 必须指向本包真实 item 或 block；示例中的 moonstone_seal 应实际定义在 items 中。
该字段选择已有内容PNG作为浏览器图标，不额外生成素材，也不为浏览预览激活世界。

路径必须相对、无跳转、无冲突。模块文档不得占用 `worldsmith.json`、`assets/`、`drawings/`、`structures/` 等保留位置；目录读取也检查真实路径，阻止符号链接越过包根。

## 持久故事与真实地点

[Story 契约](../core/src/main/resources/prompts/contract/story.system.md)统一 WORLD/PLAYER/CHARACTER/PLACE 类型事实。地点与居民来自结构 schema3 的 StoryAnchor 标记；声明存在不代表实例已生成。对话、分支任务、交易、居民日程、知识发现和环境音读取同一事实系统，不复制玩家进度或发明另一个脚本虚拟机。

story_reference 创作链接来自实际定义字段，界面仅展示已经发现的内容。场景内局部效果继续复用 abilities；长期状态、原子交易和实例身份由故事运行时管理。

## 统一主题与真实任务各自承担职责

`WorldTheme` 持久保存：

- `id`、`title`、`premise`：世界身份、标题与核心设定。
- `playerRole`：玩家在世界观里的角色。
- `worldRules`：1..32 条世界规则。
- `mainConflict`：贯穿探索的主线冲突。
- `beats`：1..64 个有名字的叙事节点。

每个 `WorldNarrativeBeat(id, title, description, content)` 的 `content` 必须链接 1..64 个真实定义，例如 `ContentKey("block", "moonstone")`、`ContentKey("creature", "guardian")`、`ContentKey("structure", "observatory")`。空设定、重名节点、缺失定义、只指向主题自身的节点都会得到明确诊断。

这些节点表达创作意图和内容关联，**不是可执行任务条件、完成状态或奖励**。已安装的 `quests` 模块通过 `themeBeat` 关联叙事节点，服务端负责真实任务进度并在领奖后点亮原版进度；独立成就模块仍未安装。

## 身份、引用与执行顺序

`ContentKey(kind, id)` 是包内逻辑身份，和原生注册表 ID、路径、运行实例 ID 分开。

现有材料及建筑几何字段引用自定义方块时使用：

```text
worldsmith:content/moonstone
```

目录会把它解析为 `block / moonstone`，不当作外部 Mod 方块；`minecraft:stone` 等仍是原生引用。生物生成规则通过逻辑群系 ID 链接群系。主题节点通过 `ContentKey` 链接任意已安装内容类型。冻结绘图里的自定义方块同样接受缺失引用检查。

先分配全部符号，再链接引用，因此合法的运行时引用环不等于编译依赖环。只有模块声明的 `compileAfter` 参与拓扑排序，具体顺序由内容检查接口返回。方块先于依赖它的地形与结构；结构也声明在生物之后编译，以校验真实的 BossSpawner 引用。不应维护一份遗漏 items/quests 的手工顺序作为运行时依据。

目录中的蓝图身份限定在所属结构下；现有结构编译器与磁盘布局仍要求不同蓝图具有全包唯一的蓝图 ID。作者应为建筑和组装部件分配独立名字；不同内容共用同一蓝图 ID 会报 `CONFLICTING_BLUEPRINT`，而不是静默覆盖。

## 冻结资产与哈希

`ContentAssetStore` 是通用的内容寻址字节存储；进入世界包还必须经过 `ContentAssetValidation`。两者职责不同：存储成功不代表资产符合某种渲染领域。

当前世界包接受 `image/png`：

- 每个资产记录 `id`、`sha256`、`mediaType`、`byteLength`、`path`。
- 资源路径为 `assets/<sha256>.png`，实际字节必须和长度、摘要一致。
- 在图像解码前检查 PNG 签名、IHDR 尺寸和所有块的 CRC；拒绝截断、尾随数据和动画 PNG。
- 然后实际解码；最大边长 2048，单个编码文件 4 MiB，最多 256 个资产。
- 全包编码资产最多 64 MiB，累计纹理像素最多 16 Mi；冻结绘图二进制另外限制在 256 MiB。
- 方块／物品图标进一步要求 16..256 的方形、二次幂纹理，编码文件最多 1 MiB；护甲独立图集为64x32至512x256的二次幂倍数，最多1 MiB；生物纹理尺寸匹配模型UV声明。纹理引用直接使用实际资产SHA-256。
- `WorldsmithPack.assets` 复制输入字节，并只返回独立字节数组，外部修改不会篡改已冻结资源。

新哈希使用格式 10 独立域，包括模块身份、模式版本、路径、全部类型化文档、结构蓝图、冻结绘图及资产描述与已验证摘要。计算前先进行类型化反序列化与规范化序列化，再对对象键排序，因此 JSON 排版、对象键顺序和省略默认值不改变语义身份。

主题文字、纹理、行为、地形及源码出处变化会生成新 ID。清单展示用的 `displayName`/`description`/`representativeContent` 不参与生成身份，主题模块内的标题和设定参与。未声明的额外输入文件拒绝进入编码/哈希边界。验证时还会重新计算类型化内容哈希，发现冻结后修改会报 `PACK_CONTENT_MUTATED`。
mechanics 的规则、成本、状态与动作参与新格式哈希；只有显示清单元数据被排除。旧格式哈希投影不再用于当前读写。

方块的透明视觉目前应优先选择 GLASS profile；其他 profile 的原生全方块遮挡/面剔除语义不保证任意 alpha 贴图的视觉效果。公共验证层暂不把非 GLASS 透明度作为额外格式错误。

## 一条持久化边界

应使用：

```kotlin
val pack = WorldContentBundleIO.create(
    displayName, description, terrain, biomes, features, structures,
    theme, blocks, creatures, assetsById, items, quests,
    representativeContent = ContentKey("item", "moonstone_seal")
)
val diagnostics = WorldsmithPackValidator.validate(pack)
val frozen = WorldContentBundleIO.encode(pack)
```

`create` 组装有内容地址的清单和资产并计算 ID，不代替领域语义校验。`encode` 返回：

- `manifest`：带最终 ID 的清单。
- `texts`：相对路径到文本，**不包含** `worldsmith.json`。
- `binaries`：相对路径到冻结绘图或 PNG 字节。

保存服务在临时位置写出这三部分，重新加载和校验成功之后再发布；导出必须走同一编码结果，避免 MCP、目录保存、ZIP 导出各自拼出不同格式。

`WorldsmithPackLoader` 提供目录与 classpath 加载，保留 `computedId`；消费者仍需执行 `WorldsmithPackValidator` 并检查声明 ID，不得把“JSON 成功解析”当成有效或激活回执。

单文件交换由 [`.wspack` 资源包](resource-packs.md) 承载：容器版本与内层世界格式独立，导入先检查整个归档，再保存为同一个不可变内容地址；CLI、MCP 和游戏资源库共用实现。

## 能力与生命周期

| 生命周期 | 必需工作 |
| --- | --- |
| `BOOTSTRAP` | 启动注册有界方块宿主、实体宿主、统一物品／能力投射物宿主及原生状态集合 |
| `WORLD_DATA` | 编译地形、群系、装饰、建筑和生物分布 |
| `CLIENT_RESOURCES` | 准备纹理、方块／物品模型、独立护甲图层、实体模型及其渲染数据 |
| `WORLD_BINDING` | 保存并恢复内容哈希、逻辑内容到宿主的稳定映射及运行行为 |

`WorldContentPlan` 分开报告 `catalogValid`、`missingCapabilities` 和编译顺序。能力版本来自实际宿主，不从任意用户字段获取。准备资源和绑定不应自行激活世界；实际激活由原生生命周期控制，并需要真实回执。

同一存档的绑定不得被另一个主题重新解释；资源切换也必须和绑定切换一致。失败时保留上一份可用结果，不用部分成功覆盖完整快照。

## 表现后端

GeckoLib 可以作为以后实体关键帧动画的表现后端，但不是世界内容平台、贴图生成器或行为系统。当前公共模型和存档不依赖它的 Java 类型；主题、模型、UV、状态和动作意图应保持可迁移。

无论采用哪个渲染后端，生物服务端行为与客户端表现必须分开：服务器判定目标、攻击时序与伤害，客户端根据同步状态播放动作。任务和成就同样应共享事实来源，而不是根据画面动画推测完成。

## MCP 与原生接线

`worldsmith_put_content_modules` 用 `sessionId + expectedRevision + modules` 原子合并完整领域文档；建筑、素材与世界内容共用一个持久 revision。`get_content_draft`、`put_texture_asset`、`create_pixel_texture`、`preview_texture_asset` 均带 `worldsmith_` 前缀。真实 PNG 存在会话外的内容寻址存储，草稿只保存已验证句柄；发布时再次读取、检查摘要，并随包冻结。并发修订会报冲突，不会绑定到另一份较新草稿。

`WorldContentLifecycle` 在启动注册固定宿主，原生编译使用显式、不可变 resolver，而不是当前世界全局状态。`WorldContentRuntime` 管理一个本地存档的服务端各维度与客户端 ownership，禁止另一主题重新解释同一组已使用宿主。发布和进入已有存档都先验证并加载对应客户端资源，再允许相应激活。资源重载失败保留此前完整快照；取消创建和断线清理会等待跨流程共享的资源操作完成。

原生数据包携带 `worldsmith-content/` 下完整内容包及 `worldsmith-runtime/block-bindings.json`；读回只查看启用的数据包，不依赖 config 中仍保留草稿。运行中的数据重载在 prepare 阶段拒绝更换内容哈希/槽位，让旧资源管理器保持可用。

当前支持范围是本地集成服。远程服务端的资源与绑定协商尚未安装；有界单线任务及其原版进度投影已安装，独立成就创作仍属后续模块。生物目前是地面宿主、立方体骨骼模型、预定义行为与显式 Boss 阶段，不是任意 Java 行为注入、飞行/游泳或 GeckoLib 关键帧导入。

## 物品能力与旧内容恢复

items schema 1 保持普通资源／遗物；schema 2 可选 equipment、consumable、actions，支持原生武器／工具、四个护甲槽、食用与 heal/feed/status/projectile/blink 固定动作库。
普通右键穿戴护甲，手持潜行右键发动其USE；MELEE_HIT仅适用于非护甲武器／工具。食物USE在完成食用时运行，自带1件生存消耗，额外consumeCount与durabilityCost均为0。
动作冷却按每玩家／世界／逻辑物品共享，不让所有自定义物品因宿主相同而互锁。blink最多8格，统一投射物不爆破地形。
逻辑引用 `worldsmith:item/<id>` 连接创造分类、物种死亡掉落、建筑容器和任务奖励；原生堆栈保留世界身份与完整能力组件。参见 [物品与奖励](items-and-rewards.md)。

当前包使用格式 10 的十二模块，旧格式 1–9 被拒绝。域 schema 的版本独立于包版本：当前包内使用支持的 items schema1..4、creatures schema1..6、structures schema1..3；quests 必须为 schema2，blocks 与 story 为 schema2。

## 玩家文案与作者诊断分离

清单名称／说明、theme的标题／premise／playerRole／mainConflict／worldRules／beats、物品名称／lore、任务标题／说明，只描述世界故事或真实玩法。
字段中不追加包内玩家进度边界、地形校验、原生编译、工具调用或未实现功能的技术提示。需要解释的实现限制、失败原因、兼容说明放在作者诊断和回执中；没有把工程备注塞进故事再以脚注减弱承诺的路径。

`PlayerTextPolicy` 在新创作发布边界检查已知工程泄漏模式，并以 `PLAYER_TEXT_ENGINEERING_LEAK` 返回字段路径。
这是作者修正文案的门禁，不是UI展示时的自动删改，也不是 `WorldsmithPackLoader` 加载旧包的新条件。
已有不可变包、嵌入存档、原版进度说明和现有两包原文保持不动；生成端今后遵循该规则。

## 主线任务与素材复用

第十二模块 quests 定义单条线性主线，可关联 themeBeat，目标为 kill_creature、deliver_item 和 activate_mechanic。
进度由服务器按玩家／世界保存；主动分次交付才消费物品并增加进度，所有目标完成后单独一次领奖，
满背包时领奖无变化。玩家附件和 Inventory 一起保存，客户端只显示快照与发送意图。
详见 [主线任务](mainline-quests.md)；故事与分支任务定义仍不等于大陆规模居民社会模拟或跨文件磁盘崩溃事务。

新内容使用格式 10，能力字段不回填旧文件。素材生成服务不与 AI 厂商绑定：确定性像素配方、专用 inbox 导入、实际 PNG
校验／哈希／模型预览都通过 MCP 公开。外部生图模型由调用方自己提供，参见 [贴图复用](texture-authoring.md)。


## 生物声音

Creature schema 3 支持原版声线选择与音高／音量调制，覆盖环境、受伤、死亡、攻击。
旧包在运行时自动匹配默认声音，不重写包或存档。新配方请显式设计 sounds；
详见 [生物声音词汇表与创作参数](creature-sounds.md)。

## 方块构型与交互

第十二模块 [mechanics](mechanics.md) 提供有界事件驱动状态机：玩家成功放置任一最后构件或主手右键锚点，匹配构型、成本与状态后事务性执行 set_block/spawn_creature/give_item。没有扔物检测、tick 脚本或命令解释器。

## 通用能力程序

`abilities` 为必需模块，包含实际 AbilityScript source、名称、执行预算与能力版本需求。
源码进入不可变哈希，prepare 阶段一次编译；编译后的指令由三个宿主共用。
creatures schema 4 的 `ability`、items schema 3 的 `run_program`、mechanics 的
`run_program` 都链接 `ability/<id>`，并在 abilities 之后编译。
WorldBible/设计计划/创作审查通过 `invokes_ability` 验证真实宿主绑定，审查证据读取
`/modules/abilities/programs/<index>/source` 而不是仅看招式名。
详见 [通用能力运行时与 SDK](abilities.md)。
