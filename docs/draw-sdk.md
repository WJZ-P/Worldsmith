# Java 建筑绘图 SDK

这套 SDK 是一张体素画布和一组画笔，不是预制建筑库。
每个世界可以由 AI 编写不同的 Java 函数、循环和数学表达式来创作。

普通建筑后续生成默认布置真实灯具，覆盖各楼层、楼梯和可用阁楼；无用途屋顶夹层应填实或打开。
高层 AuthoringContext 提供 `hangingLightFixture(id,at,anchor)`，从真实屋梁/曲面屋顶坐标连接连续铁链，
在 snapshot 时复查锚点和链条；`intentionallyDark(reason)` 显式声明墓室等故意幽暗建筑。
默认发布不执行逐点亮度门禁，可选 `worldsmith_preflight_structure(..., estimateLighting:true)` 或 lighting
叠加层提供非阻断估算。声明合法性、真实光源等级、支承与通路检查保留，不修改既有世界内容。

## 分层

- `core/src/main/java/com/wjz/worldsmith/core/draw`：纯 Java 21，只有 JDK 依赖。
- `core.draw.examples.DrawGallery`：可执行示例，包含 80 格宽的画布、拱、穹壳、曲面屋顶、条件绘制和组件复制。
- `WorldsmithDrawExporter`：Mod 侧 Java 适配器，通过当前 Minecraft 注册表解析方块，输出标准 gzip 结构 NBT。
- `worldsmith_get_draw_sdk`：只读 MCP 文档入口，提供完整 API 说明；不执行 Java 源码。
- `draw-worker`：随 Mod 分发的 ECJ 3.46.0 编译/执行宿主，通过 `worldsmith_build_drawing` 提交源码。
- [完整 Structure Agent 流程与示例](structure-agent.md)：只靠 MCP 完成构建、预览、修正、编排和发布。

完整 API 契约位于 `core/src/main/resources/prompts/contract/draw.system.md`。

## 快速示例

```java
import com.wjz.worldsmith.core.draw.*;

var canvas = DrawCanvas.sized(96, 48, 80);
var stone = canvas.pen("stone_bricks");
stone.fill(Box.of(4, 0, 4, 91, 2, 75));
stone.shell(Box.of(12, 3, 12, 83, 24, 67), 2);
stone.clear(Box.of(14, 3, 14, 81, 22, 65));
stone.clear(Box.of(45, 3, 12, 50, 10, 14));

canvas.pen("deepslate_tiles").heightField(
    Box.of(10, 23, 10, 85, 39, 69),
    (x,z) -> 25 + 10 * Math.pow(1 - Math.abs(x - 47.5) / 37.5, 1.5), 1);
DrawStructure drawing = canvas.snapshot();
```

在已经完成 Minecraft bootstrap 的 Mod 环境导出：

```java
WorldsmithDrawExporter.write(drawing, Path.of("build/draw/my_build.nbt"));
```

纯 Core 示例运行：`gradlew :core:drawGallery`。这一步构造模型、打印统计，不启动 Minecraft。

## 已有绘制能力

| 类别 | API |
| --- | --- |
| 基础 | 单点、点集、填充、壳、显式清空、恢复 KEEP |
| 线条 | 六连通线、折线、二次/三次 Bézier、圆弧、圆截面笔触 |
| 实体 | 球、椭球、任意轴柱体、锥台、圆环、凹多边形挤出 |
| 自定义 | 高度函数曲面、任意体素谓词、标量场/SDF |
| 布尔几何 | 并、交、差、偏移、壳、连续旋转与缩放 |
| 材质 | 固定、加权斑块、概率、棋盘格、分层、自定义 Java 画笔 |
| 选择 | KEEP/AIR/非空气/材质/区域过滤，AND/OR/NOT，自定义遮罩 |
| 组合 | 局部坐标、90 度旋转、X/Z 镜像、快照、裁剪、复制、存储分片 |

## 语义与边界

- 坐标是方块中心，Box 两端都包含。负坐标可用；写入会裁剪到画布/clip。
- 未绘制为 KEEP，AIR 是实际清空。壳只画边界，不暗中清空内部。
- 单次操作事务性提交；异常时丢弃本次方块修改，已完成的操作保留。工作预算仍会计入。
- 画布是单线程对象；画笔/遮罩内部不应嵌套修改画布。可通过独立画布组合并发创作结果。
- 整数旋转和镜像携带方块方向信息，交给 MC 原生状态变换处理。任意角度的场旋转只旋转几何。
- 椭球和锥台的 Field 是隐式水平集，不是严格距离场；在它们上面套 shell 不是等厚壳。
- 默认资源预算可由调用方调整。存储分片不是建筑分栋，SDK 尺寸自由不等于世界生成范围无限。
- NBT 输出是结构模板，不是完整 MC 存档；原点规范化到绘图最小角。
- 底层 DrawProgram 几何快照输出方块及状态，不内嵌实体或任意 block-entity NBT；StructureProgram 的类型化语义侧车可声明容器、牌子和 Boss 刷怪笼等已实现交互，见创作工作台合约。
- 世界部署显式引用冻结 drawingId；旧 JSON 是兼容几何入口。建筑语义、编排、选址仍在 SDK 外部。
- 玩家和 AI 端无需安装 Python/JDK/javac；MC 当前 Java 运行时启动隐藏 worker，编译器随 Mod 附带。
- 关闭自动执行时，首次源码执行需要 MC 内的会话确认，重启后重新确认；这是故障/资源隔离，不是完整文件或网络沙箱。
- 新世界包使用格式 5 的九模块，其结构模块模式 2 保存冻结数据与源码来源。格式 3/4 仅按原身份读取恢复或重新封装，不自动升级；旧的未发布世界包格式 1/2 需要重新生成。加载只读取数据，不执行源码出处。


新版创作入口与示例：[Structure Authoring Workbench](structure-authoring-workbench.md)。
