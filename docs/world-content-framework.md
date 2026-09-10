# Worldsmith 世界内容地基：格式 4

## 目标与真实边界

一个世界拥有一个持续保存的主线主题；地形、群系、生态装饰、建筑、自定义方块和自定义生物引用同一套内容身份与冻结资产。
创作阶段可以迭代任意模块，发布阶段必须形成可校验的完整内容快照。游戏运行阶段消费冻结数据和原生实现，不在方块、区块或实体 tick 中执行作者源码或调用大模型。

本仓库的未发布格式 1/2 不再作为磁盘兼容目标。新写入格式为 **4**；格式 **3** 保留只读恢复及其原有内容哈希。`WorldsmithPackFiles` 仅暂留源代码构造兼容；它不再序列化，存储和加载必须读取 `manifest.modules`。

公共层已经实现：

- 八个有类型的模块：`theme`、`blocks`、`terrain`、`features`、`biomes`、`creatures`、`structures`、`items`。
- 本地符号、跨模块引用、确定性编译依赖、能力与生命周期要求。
- PNG 字节验证、内容寻址、资源预算，以及包含全部模块和资产的不可变内容哈希。
- 新格式加载、统一编码、类型化内存投影、主题持久化与损坏诊断。
- 未安装的 `quests` 和 `achievements` 模块明确报错，而不是被当成已实现玩法保存。

**内容有效、具备宿主能力、完成准备、实际激活是四件不同的事。** 本文的编译计划和内容测试不等于游戏内验收。

## 模块与清单

`worldsmith.json` 的结构如下；省略号仅供阅读，新格式实际文件必须给出八个模块及完整哈希：

```json
{
  "formatVersion": 4,
  "id": "<64 位小写 SHA-256>",
  "displayName": "月石诸国",
  "description": "主题驱动的完整世界",
  "modules": {
    "theme": {"schemaVersion": 1, "path": "theme.json"},
    "blocks": {"schemaVersion": 1, "path": "blocks.json"},
    "terrain": {"schemaVersion": 1, "path": "terrain.json"},
    "features": {"schemaVersion": 1, "path": "features.json"},
    "biomes": {"schemaVersion": 1, "path": "biomes.json"},
    "creatures": {"schemaVersion": 1, "path": "creatures.json"},
    "items": {"schemaVersion": 1, "path": "items.json"},
    "structures": {"schemaVersion": 2, "path": "structures.json"}
  },
  "assets": []
}
```

八个模块都出现；非引导的底层内容包允许空方块、生物和建筑库。MCP 的引导创作仍保留现有建筑质量契约：至少两个建筑群、独立建筑与一个主题地标，发布时再次验证；底层格式与引导流程的约束不是同一层。
结构模块继续以 `StructureIndex` 加独立蓝图文件和冻结绘图二进制保存，在内存中投影为 `StructureLibrary`。其他模块各自保存类型化文档。

路径必须相对、无跳转、无冲突。模块文档不得占用 `worldsmith.json`、`assets/`、`drawings/`、`structures/` 等保留位置；目录读取也检查真实路径，阻止符号链接越过包根。

## 统一主题，而不是假装已经有任务系统

`WorldTheme` 持久保存：

- `id`、`title`、`premise`：世界身份、标题与核心设定。
- `playerRole`：玩家在世界观里的角色。
- `worldRules`：1..32 条世界规则。
- `mainConflict`：贯穿探索的主线冲突。
- `beats`：1..64 个有名字的叙事节点。

每个 `WorldNarrativeBeat(id, title, description, content)` 的 `content` 必须链接 1..64 个真实定义，例如 `ContentKey("block", "moonstone")`、`ContentKey("creature", "guardian")`、`ContentKey("structure", "observatory")`。空设定、重名节点、缺失定义、只指向主题自身的节点都会得到明确诊断。

这些节点表达创作意图和内容关联，**不是可执行任务条件、完成状态或奖励**。未来任务系统应在共同身份、资产和存档生命周期上增加真实领域模块，再把节点连接到任务与成就；现在不得提交伪造的 `quest`/`achievement` 定义。

## 身份、引用与执行顺序

`ContentKey(kind, id)` 是包内逻辑身份，和原生注册表 ID、路径、运行实例 ID 分开。

现有材料及建筑几何字段引用自定义方块时使用：

```text
worldsmith:content/moonstone
```

目录会把它解析为 `block / moonstone`，不当作外部 Mod 方块；`minecraft:stone` 等仍是原生引用。生物生成规则通过逻辑群系 ID 链接群系。主题节点通过 `ContentKey` 链接任意已安装内容类型。冻结绘图里的自定义方块同样接受缺失引用检查。

先分配全部符号，再链接引用，因此合法的运行时引用环不等于编译依赖环。只有模块声明的 `compileAfter` 参与拓扑排序。目前完整包的顺序是：

```text
blocks → terrain → features → biomes → creatures → structures → theme
```

其中 creatures 与 structures 的先后只是无依赖节点的确定性排序，不表示结构依赖生物。方块先于地形是明确声明的依赖。

目录中的蓝图身份限定在所属结构下；现有结构编译器与磁盘布局仍要求不同蓝图具有全包唯一的蓝图 ID。作者应为建筑和组装部件分配独立名字；不同内容共用同一蓝图 ID 会报 `CONFLICTING_BLUEPRINT`，而不是静默覆盖。

## 冻结资产与哈希

`ContentAssetStore` 是通用的内容寻址字节存储；进入世界包还必须经过 `ContentAssetValidation`。两者职责不同：存储成功不代表资产符合某种渲染领域。

当前世界包接受 `image/png`：

- 每个资产记录 `id`、`sha256`、`mediaType`、`byteLength`、`path`。
- 资源路径为 `assets/<sha256>.png`，实际字节必须和长度、摘要一致。
- 在图像解码前检查 PNG 签名、IHDR 尺寸和所有块的 CRC；拒绝截断、尾随数据和动画 PNG。
- 然后实际解码；最大边长 2048，单个编码文件 4 MiB，最多 256 个资产。
- 全包编码资产最多 64 MiB，累计纹理像素最多 16 Mi；冻结绘图二进制另外限制在 256 MiB。
- 方块纹理进一步要求 16..256 的方形、二次幂纹理，编码文件最多 1 MiB；生物纹理实际尺寸必须匹配其模型 UV 图集声明。方块与生物的纹理引用必须直接等于资产 SHA-256，不能使用另一个看起来像摘要的逻辑别名。
- `WorldsmithPack.assets` 复制输入字节，并只返回独立字节数组，外部修改不会篡改已冻结资源。

新哈希使用格式 4 独立域，包括模块身份、模式版本、路径、全部类型化文档、结构蓝图、冻结绘图及资产描述与已验证摘要。计算前先进行类型化反序列化与规范化序列化，再对对象键排序，因此 JSON 排版、对象键顺序和省略默认值不改变语义身份。

主题文字、纹理、行为、地形及源码出处变化会生成新 ID。清单展示用的 `displayName`/`description` 不参与身份，主题模块内的标题和设定参与。未声明的额外输入文件拒绝进入编码/哈希边界。验证时还会重新计算类型化内容哈希，发现冻结后修改会报 `PACK_CONTENT_MUTATED`。

方块的透明视觉目前应优先选择 GLASS profile；其他 profile 的原生全方块遮挡/面剔除语义不保证任意 alpha 贴图的视觉效果。公共验证层暂不把非 GLASS 透明度作为额外格式错误。

## 一条持久化边界

应使用：

```kotlin
val pack = WorldContentBundleIO.create(
    displayName, description, terrain, biomes, features, structures,
    theme, blocks, creatures, assetsById, items
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

## 能力与生命周期

| 生命周期 | 必需工作 |
| --- | --- |
| `BOOTSTRAP` | 启动注册有界方块宿主、实体宿主及原生状态集合 |
| `WORLD_DATA` | 编译地形、群系、装饰、建筑和生物分布 |
| `CLIENT_RESOURCES` | 准备纹理、方块模型、实体模型及其渲染数据 |
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

当前支持范围是本地集成服。远程服务端的资源与绑定协商尚未安装，任务/成就执行器仍属后续模块；生物目前是地面宿主、立方体骨骼模型和预定义行为，不是任意 Java 行为注入、飞行/游泳或 GeckoLib 关键帧导入。

## 普通物品与旧内容恢复

items 模块定义资源／遗物的图标、名称、稀有度与堆叠限制。逻辑引用 `worldsmith:item/<id>` 连接创造分类、物种死亡掉落和建筑容器奖励；原生 ItemStack 保留世界身份与完整组件。它不是工具、装备、食物动作或任务 DSL。参见 [物品与奖励](items-and-rewards.md)。

格式 3 使用冻结的旧字段投影和原域计算身份，items 为空且新 drops 被拒绝；格式 4 才包含第八模块和掉落语义。读取旧包不会把它悄悄升级，也不会使同名新物品接管旧内容。新创作只写格式 4，格式 1/2 继续要求重新生成。
