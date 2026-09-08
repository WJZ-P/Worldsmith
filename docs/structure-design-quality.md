# 建筑设计与视觉迭代

目标是改善实际生成的建筑内容，而不是增加尺寸配额或“美观分数”。
本轮保留既有发布校验和几何 SDK，调整创作指引与模型反馈。

## 1. 把主题变成可见决策

`architecture` 的以下章节可通过 `worldsmith_get_contract` 按需读取：

- `creative-brief`：主题如何落实到轮廓、空间顺序、结构与材质语言。full/summary 入口均携带这段核心指引。
- `form-function-and-family`：主次体量、远中近三层阅读、立面进深与节奏、材料分工、有用途的室内和场地关系。
- `visual-quality-loop`：每轮看什么图、观察什么问题、改什么、如何同机位比较。

不用增加蓝图字段：既有 `worldTheme`、`themeFit`、`layoutIntent`、`distinction` 承载具体设计意图。
先打磨一栋有代表性的主建筑，再扩展建筑族。复用窗框、接头、灯具和材质分工，而非把所有功能套进同一栋大厅。
不同主题可以是克制、对称、粗粝、轻盈或异质的；这里没有默认塔楼、哥特尖顶或中式屋檐清单。

## 2. 四轮视觉反馈

以下是 `worldsmith_preview_drawing` 的请求形状，标记值替换为已有 session/drawing id。
**灰模体量**：

```json
{
  "sessionId": "<sessionId>",
  "drawingId": "<drawingId>",
  "views": ["isometric", "isometric_back", "front", "top"],
  "renderMode": "clay"
}
```

看焦点、比例、轮廓、留白与主次关系，而不是靠换色掩盖普通体块。
灰模顶视按高度做明暗区分，避免高屋顶与低庭院合成一个色块；这不是物理阴影。

**外观**：同一绘图请求 `views:["front","back","left","right"]`、`renderMode:"material"`。
检查入口是否醒目、窗墙与柱间节奏、屋顶/墙/基座关系，以及侧面和背面是否敷衍。

**室内**：在每个有用途的楼层，选择合适的 `sliceY`：

```json
{
  "sessionId": "<sessionId>",
  "drawingId": "<drawingId>",
  "views": ["top", "isometric"],
  "cutaway": true,
  "sliceY": 5,
  "renderMode": "material"
}
```

这里的 5 只是示例楼层高度。cutaway 保留此高度及以下所有体素，slice 则只看一个水平层。
原始绘图和建筑群采用原坐标；`preview_structure` 的 sliceY 是归一化后的局部高度。
用 `region` 裁剪楼层，或隐藏确实声明过的 roof 组件，避免把组件过滤误解成自动识别屋顶。
检查房间用途、可达性、楼梯、净空与家具位置。照明作为建筑设计的一部分，通过预检修补具体暗区，
不再推荐通用地灯网格，也不通过删掉小阁楼/暗房声明来“优化”读数。

**建筑群**：`worldsmith_preview_assembly` 传完整 `structure`，同样支持
`views:["top","isometric","isometric_back"]`、`renderMode:"clay"`、frame/region/cutaway。
检查主建筑是否被配角淹没、路径和入口是否合理、院落和间距是否有意图，再另看材质模式。
组装失败时返回有标签的独立成员图，`layoutPreviewAvailable:false`，不把它当成功布局。

## 3. 一次修一个主要问题

记录简短的“观察 → 改动 → 预期改善”，重建后复用响应里的 `frame` 和相同 view/mode。
例如“侧立面过长且没有层次 → 将服务翼退进、重排窗间柱 → 降低主墙的连续压迫感”。
这类判断来自看图，不由程序伪造评分。扩大构图时有意识地更新 frame；修局部时使用局部框。
没有明确收益时停止堆装饰；达到方块数量、构建成功或校验通过本身不代表更美观。

## 4. 验证与限制

`StructureVisualQualityTest` 覆盖精简入口、契约章节、三类工具的参数一致性、多方向 PNG、
灰模对材质变化不敏感、顶视高度区分、保留多层的 cutaway、原坐标裁剪和真实示例回放。
`AuthoringMcpTest` 覆盖源码项目实际编译后，经 drawingId 使用新的多视图反馈。

测试输出在 `build/structure-quality-verification/`，包含观测台示例的体量/立面/室内图片、
庭院组装图及响应 JSON。示例用于验证工具，不作为给所有世界套用的成品模板。

这些 PNG 近似方块形状与颜色，未加载游戏纹理、透明效果、楼梯真实模型和原生光照；
它们验证“能看见并定位问题”，不证明生成审美已经稳定提高。
要判断提示词的实际收益，应对相同主题、模型与预算做完整创作对照，并独立审阅主题表达、
构图、差异、细节和探索体验。本轮未把机械回归冒充这样的创作基准。

2026-09-08 定向验收：44 项测试通过，主程序/客户端/JAR 编译通过；15 张 PNG 已生成，
反向灰模、室内剖切和建筑群顶视/轴测已实际查看。最终 JAR 和带图片哈希的记录位于
`build/structure-quality-delivery/`。没有重启用户游戏、修改已有存档或跑全量测试。
