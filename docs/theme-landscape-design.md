# 让建筑与地形共同表达玩家主题

这份指南关注“玩家实际会看见什么”，不是更多形容词、固定风格库或自动美观评分。
建筑的轮廓、材料、用途、场地剖面和到达方式应源于同一个世界前提；地形也应成为
建筑构图的一部分，而非做完模型后随机寻找的一块底板。

本次未发布版统一使用 typed `anchor.relief`，并更新生成内容的 hash 域，防止新地形／
屋顶编译规则复用旧生成回执。旧 anchor 顶层 `amplitude` 不再是当前文档形状；需要从
当前草稿重新冻结新的 bundle。此变更不自动转换、覆盖或删除本地世界和资源包文件。

五组相互对比的创作回归案例见[主题与地貌对照案例](theme-landscape-review-cases.md)，
用于检验同一工具在不同玩家主题下是否产生真正不同的空间决定，而非固定风格模板。

## 1. 先保存可以核对的设计依据

新 `COMPLETE_WORLD` 仍使用既有作者链：

`原始 prompt → WorldBible → WorldDesignPlan / ModuleBrief → 实际内容 → 证据审查`

- `WorldBible.requirements` 保存玩家的硬约束和逐字 `promptQuote`。
- 推导出的选择写进 `assumptions` 或具有稳定 ID 的设定节点，不伪装成玩家原话。
- 使用 `REGION`、`STYLE`、`CULTURE`、`ECOLOGY`、`EXPERIENCE` 等既有节点记录因果关系。
- `WorldDesignPlan` 给内容命名、承诺真实关系；`ModuleBrief.criteria` 描述可验收的实现。
- 一个目标只有一个所属任务书。建筑需要地形、群系或材料作为生产依据时，用
  `dependencies` 引用它们的任务书，不复制一份稍有不同的世界观。

例如，玩家要求“山巅的安静天文文明”，可推导出观测视野、克制的屋顶轮廓、挡风的
服务空间和清晰的登临过程；这不自动等于大量尖塔，也不意味着每个山顶都要放一座殿堂。
每个推导都应能回答：“它服务了原始请求中的哪件事？”

原始需求审查的 `prompt_alignment` 应通读完整 prompt，再核对提取结果。只检查已经
摘录的引文，会漏掉最初就没有记录的排除项、主题对比或规模约束。该项用
`basisRefs:["world/original_prompt"]` 引用原请求，`evidencePaths` 同时包含
`/originalPrompt` 和实际 `/bible` 子树路径，保留原请求与提取结果两端证据。

## 2. 用一张设计对照表代替泛化赞语

以下是作者的思考格式，不是新增 JSON DTO。实际内容继续写在已有字段和任务书中。

| 来源 | 可见决定 | 实现载体 | 核对证据 |
| --- | --- | --- | --- |
| 玩家强调孤立、辽阔 | 低密度背景与单一焦点形成对比 | 地形尺度、群系分布、结构 placement、主次体量 | 分布估计、地形定义、群落顶视与灰模 |
| 聚落依赖某种地貌 | 建筑支撑标高、入口与地貌断面一致 | anchor relief、terrainFit、foundation、access | 地形剖面参数与结构自身的实际定义 |
| 地方材料影响建造 | 地表、结构、饰面共享来源但分工不同 | biome surface、方块、绘图几何及材质 | 当前字段、四立面可见材质、PNG |
| 玩家需要有仪式感的发现 | 远处识别、转折、门槛、内部焦点分阶段出现 | 实际路径、台阶、院落、入口、组装 | 同一建筑群的顶视、正背面、楼层剖切 |

“宏伟且符合主题”难以定位修复；“服务翼退后，留出主塔前的负空间，入口从正面可见”
则有明确的观察对象。刻意空白、重复、低矮、简洁都可能正确，不使用通用细节数量指标。

## 3. 地貌要有真实断面，不只给普通山丘改名

地形 anchor 的 `relief` 明确选择物理断面：

| `relief.kind` | 实际含义 | 合适的设计问题 |
| --- | --- | --- |
| `offset` | 对原地表增加或降低 `amplitude` | 原有地貌上需要抬升或压低多少？ |
| `mesa` | 内部朝 `surfaceY` 的平台收敛，外侧回混原地表 | 聚落台地的可用顶面有多宽，边缘如何退回环境？ |
| `caldera` | `floorY` 的盆底、抬升到 `rimY` 的环壁及外缘 | 玩家先看到环壁还是盆内建筑？入口和水位在哪一层？ |

示例只是单个 anchor 的字段形状，数值应重新由当前世界决定：

```json
{
  "id": "observatory_tableland",
  "placement": {"kind": "fixed", "x": 768, "z": 256},
  "radius": 240,
  "relief": {"kind": "mesa", "surfaceY": 128, "topRadius": 0.6, "roughness": 2},
  "falloff": 1.0
}
```

```json
{
  "id": "sheltered_basin",
  "placement": {"kind": "scattered", "spacing": 3000, "jitter": 0.6},
  "radius": 360,
  "relief": {
    "kind": "caldera", "floorY": 78, "rimY": 142,
    "floorRadius": 0.25, "rimRadius": 0.65, "roughness": 2
  },
  "falloff": 1.0
}
```

`topRadius`、`floorRadius`、`rimRadius` 是扭曲后 anchor 半径的比例，非方块距离。
`roughness` 是独立局部扰动的方块振幅；零表示目标平台或盆底不加入这种扰动。
`mesa` / `caldera` 的实际断面由自己的高度与半径决定，`falloff` 仍服务于共享的
影响域和普通 `offset` 断面。后续洞穴、地形层、水文及其他 anchor 仍可能参与组合，
一个局部剖面结果不等于整个世界的最终表面。

几何形状、气候和表面材料是三个不同决定。需要时显式选择 `climateBias`、群系
surface rules 和表面材料；抬高一个地标不自动给它雪线，挖出盆地也不自动创建湖水。
水位、默认材料、流体与洞穴开关按主题协调，不把示例模板的取值当作风格约束。

### 在作者工具里先看地貌剖面

提交 terrain 草稿后，可只读调用：

```json
{
  "tool": "worldsmith_preview_landform",
  "arguments": {
    "sessionId": "当前会话 ID",
    "anchorId": "observatory_tableland",
    "incomingSurfaceY": 80
  }
}
```

响应含实际 PNG、当前 revision、terrainDigest 和 257 个剖面样本。图中虚线基面
是调用者明确提供的常数 `incomingSurfaceY`，实线是 `localTexture=0` 的断面，
包络表示局部纹理输入 `-1..1` 的数学范围。先比较目标平台／盆底宽度、环壁高度和
边缘过渡，再换一个合理的较低或较高基面做对照。

这不是根据 seed 得到的地形图：它不采样边界扭曲、邻近 anchor 叠加、洞穴、bands、
全局密度上下限淡出、流体占用或建筑放置。line anchor 显示垂直于走向的局部横断面；
统计中的 nominal core width 也不是已保证可建造的宽度。保留这些限制再作主题审查。

## 4. 让建筑真正回应场地

先画一条“场地 → 支撑 → 室内楼面 → 门口 → 到达路径”的剖面逻辑，再加装饰。

1. **确定占地。** 整体刚性组装适合连续建筑；分离的坡地聚落需要对应的 settlement
   策略。普通分布式建筑继续使用 biome-restricted placement，不强加 anchor。
2. **确定支撑。** 读取 `terrainFit.surface`、`maxHeightDifference`、foundation 的
   实际模式、支撑位置与深度。台地不应被误当成自动为任何尺寸建筑铺平的底板。
3. **确定层次。** 主建筑、服务翼、庭院、背景地貌有明确的高低与空实关系。保留负空间，
   不用巨大实心基座凑地标规模。
4. **确定到达。** 用实际 access、ports、台阶、路径或桥梁连接入口。line anchor
   只改变沿线地形；文字中的“朝圣路”不是已铺设的路。
5. **检查筛选叠加。** `placed_in_biome` 只证明 biome allow-list，anchor、群系、坡度、
   水体和预留空间限制仍一起生效。固定坐标或共享 anchor 不保证建筑成功落地。

同一玩家主题可以产生不同但相关的建筑：共享材料接缝、开口比例或光源语汇，按居住、
储藏、生产、仪式等不同用途改变体量和剖面，而不是把同一大厅改名、拉长、换颜色。

## 5. 使用实际体素反馈，再回到图像判断

`worldsmith_preview_drawing`、`worldsmith_preview_structure`、
`worldsmith_preview_assembly` 的绘图响应提供 `visualEvidence`。它针对当前实际选中的
非空气体素报告：

- `occupiedBounds`、`nonAirCells`、`footprintColumns`：实际占用，不是声明尺寸；
- `projections`：正交立面的可见格、深度层、深度跨度、起伏边缘、最大共面板块、轮廓段
  和可见材质数量；
- `review`：带 view / region 的有界观察与检查建议，而非自动审美结论。

这些测量保留整格占用语义，与 PNG 的半格形状渲染分开。PNG 对原版 slab 的
bottom / top / double，以及 stairs 的 facing / half / straight / inner / outer
提供有界的半格形状；相邻半块只遮住实际覆盖部分。其余形状和非法形状属性回退整立方体。
延迟旋转和镜像遵从当前原生导出的状态规则，尤其角楼梯镜像不假设为理想几何反射。
面片先在固定两倍分辨率下无逐面抗锯齿填充，再统一缩图，避免同平面接缝渗出背后颜色；
真实的空隙保持可见。这不是纹理模型、透明、邻居更新或碰撞模拟。

支持范围刻意保持小而明确：

| 方块族 | 离线读取的属性 | 其余边界 |
| --- | --- | --- |
| `minecraft:*_slab` | `type=bottom\|top\|double`，缺省 bottom | 不读取水体、纹理或邻居 |
| `minecraft:*_stairs` | `facing=north\|east\|south\|west`；`half=bottom\|top`；`shape=straight\|inner_left\|inner_right\|outer_left\|outer_right` | 缺省 north / bottom / straight；按已写状态显示，不推导相邻楼梯连接 |
| 其他方块族、其他 namespace、非法形状属性 | 整立方体占用 | 不猜测模组模型，也不等于原生校验通过 |

延迟 orientation 先沿用当前 26.2 导出的 `FRONT_BACK` 状态镜像，再旋转。该原生规则对
朝东西的 outer 角交换左右、inner 角保留左右；朝南北的角形状保持不变。因此角楼梯
镜像不总等于数学上的几何反射。静态原生形状采样回归核对此一致性，而非另创导出规则。
半格轮廓边保留共线中点，避免整边与两段半边形成栅格 T 接缝；矩形仍是一个面，不扩大它。

先查看建议所在的 PNG 区域，再判断它是否违背本次设计。大片平面可能是有意的纪念性
墙体；很多深度层也可能是噪声。平面测量不靠涂色制造层次，藏在墙后的材质也不是立面变化。
等角图使用正交辅助测量，不伪装成透视立面的真实尺寸。

每次只修最影响主题阅读的一两处，并保存“观察 → 几何改动 → 期望差异”。复用相同
`views`、`renderMode`、`frame` 和筛选条件做前后比较。`frame` 控制镜头尺度，不裁掉几何；
`region`、组件筛选和 cutaway 会改变分析对象。单层 slice 与保留多层的 cutaway 分开比较。
轮廓段与材质列表有展示上限，汇总数量保留；截图中的全部细节仍需实看。

完整四轮看图方法见 [建筑设计与视觉迭代](structure-design-quality.md)。
可运行作者源码示例见 [台地观测院](examples/theme-landscape/README.md)：它演示低翼、
焦点塔、留白庭院和转折路径，以及一处真实立面修复，不是跨主题通用建筑模板。

## 6. 跨模块证据保留两端实现

读取 `worldsmith_get_authoring_review_context` 后，先核对 `sourceContext` 中的原始
prompt、当前设定依据、criteria 和依赖任务书。`entries` 按 `kind` 区分 criterion、
basis 和 dependencyBrief；`truncated:true` 时，将 `nextOffset` 作为下次调用的
`sourceOffset` 读取后续页，同时携带第一页的 `expectedInputDigest`。相关来源或内容变化
会拒绝续页，应从零重读；无关 session revision 变化不会单独使它失效。不要混用不同
快照，也不要从第一页的片段下结论。
较长的 assumptions / open decisions 按完整条目分页，使用共同的 basis `id`、
`itemIndex` 与 `collectionSize`；引用依据时仍使用原来的 basis `id`。
`implementationEvidence:false` 表示来源用于说明设计依据，不作为建筑或地形已完成的证据。

对“建筑与台地匹配”这样的 criterion：

- 从该检查自己的 `evidenceRoots` 至少引用一处建筑本体证据，例如实际 placement；
- 从 `dependencyEvidenceRoots` 引用显式依赖任务书拥有的实际地形、群系或材料目标；
- 对照实际 anchor relief、楼面 / foundation / access 及图像观察，写出具体结论；
- 没有实际建筑证据时，单独引用台地定义不足以 PASS；未声明依赖的无关目标也不是补充根；
- BLOCKED 同样需要自身证据；缺失实现可引用自身 `blockedEvidenceRoots`，不以依赖
  的现有成品代替尚未完成的当前目标。

检查仍由 AI 作语义判断。服务验证来源、依赖范围、证据路径、覆盖和摘要时效，不自动
证明“漂亮”“符合玩家审美”或“旅程有趣”。

## 7. 不启动游戏时的完成边界

本轮可使用源码审查、Core 编译与测试、确定性剖面采样、冻结体素分析、PNG 多视角审阅。
这些能核对断面实现、几何区别、字段关联和视图差异，不需要启动 Minecraft。

分别报告：设计意图、已保存定义、离线测量、实际渲染模型、工程校验，以及尚未观察的
原生放置、物理光照、碰撞与真实游玩。整格几何测量将玻璃等也视为占用体素；PNG
支持基本楼梯与台阶形状，但仍未复现游戏纹理、透明、物理光照和真实环境。
地形统计不是实际地图，建筑预览也不是地形内实例。
用户暂缓运行时验证时，保留这些边界，不为补齐创作循环擅自启动游戏。

## 8. 离线预览的资源边界与测量

形状提示只检查每个选中非空气格的八个半格；不遍历整个包围盒。完整方块向空气暴露
的面复用一个矩形，完整方块相邻的内部面直接跳过。局部方块状态缓存最多 1024 项。
最多保留 250,000 个可见方向面，分配下一面的坐标数组前就拒绝超额请求；栅格图在
面收集与预算检查后才创建。每面至多八个边顶点，整块不因半格分析膨胀成四个独立面。

最终图仍为 1280 × 1000，固定 2 倍中间图为 2560 × 2000；两张 RGB 缓冲的原始存储
约 24.4 MiB，此外还有选中格索引、至多 250,000 个面的数组及 PNG 编码缓存。
这是有界图像成本，不是整个 JVM 堆的上限。形状复杂的稀疏目录应按 region、楼层或单体
检查；frame 只是镜头尺度，不减少参与处理的几何。

一次本机离线测量使用 JDK 25、`-Xmx768m`，小场景预热后每个视图取三次中位数，再相加
front / back / top / isometric 四视图。下表对比旧整格渲染与新形状、接缝修复版本：

| 固定测试内容 | 非空气格 | 四图旧 / 新时间 | 等角图旧 / 新面数 | 四图旧 / 新 PNG 总字节 |
| --- | ---: | ---: | ---: | ---: |
| 密集实心体量，压力样本 | 46,464 | 171 / 257 ms | 4,048 / 4,048 | 135,424 / 89,596 |
| 空心大厅、楼梯和台阶屋顶 | 13,484 | 156 / 258 ms | 15,652 / 19,715 | 176,455 / 145,902 |
| 稀疏独立构件，压力样本 | 14,872 | 229 / 373 ms | 44,616 / 59,488 | 304,914 / 386,195 |

该记录用于识别本轮成本，不是跨机器性能保证，也未设为 CI 时间阈值。另以五份确定性
混合形状样本，在七个机位对 64,625 个远离颜色和轮廓边缘的像素进行射线 / 半格 AABB
对照，修复后全数一致；这仍是有限离线正确性样本，不覆盖所有可能的游戏方块模型。
