# Structure Agent：从 Java 到世界中的冻结结构

新创作主线：**规划 → MCP 提交 Java → MC 侧构建 → 模型预览 → 修正 → 编排 → 原生检查 → 发布**。
AI 端只使用 MCP；玩家使用 Minecraft 自带的 Java 运行环境，无需另外安装 JDK、javac 或 Python。

## 分层边界

| 模块 | 职责 |
| --- | --- |
| `core.draw` | 纯 Java 21 体素绘制、画笔、几何、锚点、冻结快照 |
| `draw-worker` / `core.drawhost` | ECJ 3.46.0 编译、隐藏子进程、任务/修订管理、超时、取消、PNG |
| Structure / Architecture | 原点、房间、照明、交互、必选/可选成员、逻辑建筑编排 |
| Native worldgen | 真正的方块状态、NBT、地形适配、道路和按区块放置 |

绘图 SDK 不知道玩家、世界、区块、村庄规则或选址。区块生成不调用 AI，也不启动源码编译器。

## 源码与任务

入口必须是公开、具有无参构造函数的 `DrawProgram` 实现：

```java
import com.wjz.worldsmith.core.draw.*;

public final class Hall implements DrawProgram {
    public DrawStructure generate(DrawContext context) {
        var c = context.canvas(Box.of(-40, 0, -16, 39, 39, 15));
        c.pen("stone_bricks").fill(Box.of(-40, 0, -16, 39, 3, 15));
        c.pen("air").fill(Box.of(-40, 4, -16, 39, 39, 15));
        c.anchor("entry", new Vec3i(0, 4, -16));
        return c.snapshot();
    }
}
```

这是接口示例，不是完整的宏伟建筑设计。真正创作仍应由世界主题推导形体、材质、用途和流线。

`worldsmith_build_drawing` 的参数：

```json
{
  "sessionId": "<begin_world 返回值>",
  "name": "hall",
  "requestId": "hall-r1",
  "entryClass": "Hall",
  "sources": {"Hall.java": "<上述完整 Java 文本>"},
  "seeds": [20260906],
  "parameters": {"purpose": "main_hall"}
}
```

- 支持 1–16 个相对 `.java` 文件、合计 1 MiB；最多 8 个种子、64 个字符串参数。
- Java 21 语法，关闭注解处理与预览特性。ECJ、worker 和纯 SDK 随 Mod 分发。
- 默认单并发，子进程堆 1 GiB；编译 30 秒、全部种子的绘图合计 120 秒。
- SDK 默认预算：200 万 authored cells（包含 AIR）、3200 万绘图工作量、20 万路径采样。
- 单结果至多 64 MiB；日志最多 64 KiB，MCP 返回最多 8 KiB 日志摘录。
- 源码执行遵循宿主设置，默认自动执行；关闭自动执行后按会话在 MC 内确认，重启后重新确认。MCP 没有批准接口。
- 工作进程隔离故障和资源，并清理环境、限制类路径；**不是完整的文件系统或网络安全沙箱**。只批准可信创作会话。

构建立即返回 `jobId`，用 `worldsmith_get_drawing_job` 查询：

`WAITING_APPROVAL → QUEUED → COMPILING → DRAWING → VALIDATING → SUCCEEDED`

其他终态为 `FAILED / CANCELLED / INTERRUPTED`。失败返回文件、行列和诊断；已有成功草稿保留。
传输重试使用同一 `requestId` 和完全相同输入；修改或重试失败任务必须使用新 `requestId`。
同一 `name` 的新任务产生新修订。旧结果仍可预览，默认不参与新修订发布；显式选择旧结果需设置
`drawing.allowPreviousRevision=true`。`worldsmith_cancel_drawing_job` 仅影响指定任务。

## 模型预览与导出

`worldsmith_preview_drawing(sessionId,drawingId,view,sliceY)` 返回 MCP `image/png` 内容，
支持 `isometric / isometric_back / front / back / left / right / top / slice`，`views` 一次最多四张。
`renderMode:clay` 检查体量（灰模顶视按高度明暗），`material` 查看近似材质色。
`cutaway:true` 隐藏 sliceY 以上的体素，保留选择的视角；slice 仅展示单层。
`sliceY` 使用原绘图坐标；负坐标有效。用 `frame` 固定取景，用 `region` 检查局部。
从同一份冻结快照绘制，不是仅返回 MC 机器上的本地路径。PNG 简化方块形状、颜色和光照，
不是游戏截图或实际光照证明。高面数模型可使用正/背视图或切层；渲染有独立面数预算。
完整质量流程见 [建筑设计与视觉迭代](structure-design-quality.md)，不要只看一张有利角度的轴测图。

`worldsmith_export_drawing` 通过原生方块状态检查后返回 gzip NBT 的 MCP 内嵌二进制资源。
超过世界部署范围的绘图仍可以独立预览/导出；部署不会自动缩放、截断或散放片段。

## 冻结绘图接入结构

```json
{
  "id": "hall_encounter",
  "blueprint": {
    "id": "hall",
    "drawing": {"variants": ["<drawingId>"]},
    "origin": {"x": 0, "y": 0, "z": 0},
    "lighting": {"mode": "EXTERIOR_ONLY"}
  },
  "placement": {"biomes": ["<本世界的 biome id>"]}
}
```

SDK 蓝图省略 `build/modules`；`size` 从快照派生。原点、端口、入口、支撑、保护区、房间、光源和
类型化交互均使用**原绘图坐标**，归一化时与方块和命名锚点一起转换。原点 Y 是绘图最低支撑层。
命名锚点是模型元数据，不自动变成建筑群连接端口。

建筑群按**逻辑建筑**计数，至多 16 个成员；SDK 建筑自动拆成不大于 32³ 的非空存储片段，
每个方案总计至多 128 片。所有片段共享所属建筑的旋转及地形适配；道路连接端口，不连接存储片段。
SDK 片段保存已校验的方块形态，避免床等配对方块在另一片段尚未写入时被邻居更新拆掉。
旧已保存片段保留原先读取/放置行为。

总体部署预算维持原值：平面范围 `[-96,96]`、高度 128、方案 262144 authored cells、8192 个脚印列，
以及现有采样和世界写入上限。SDK 单栋支持超过 64 格，不代表无限世界单次部署范围无限。

## 房间与照明

在结构元数据声明 `rooms` 与 `indoorPassages`（包含走廊、楼梯等室内通道），合计最多 32 个盒子。
所有可走点都必须被 `lighting.spaces` 覆盖，并满足 `READABLE` 最低脚部/头部估计方块光照 8。
存在室内声明时 `EXTERIOR_ONLY` 会报错；需要真实发光方块，原生检查核对其状态发光等级。
未声明的室内意图不会由校验器自动推断。Core 的光传播估算不代替 MC 光照或视觉验收。

## 发布与恢复

`write_pack` 完成 Core 校验并保存草稿包；`finish_world` 继续检查：

`CORE_CHECK → WAITING_NATIVE_CONTEXT / NATIVE_CHECK → RELOADING → PUBLISHED`

只有冻结包回读、真实方块/属性/配对/交互检查、原生 NBT 回读、完整数据包重载和当前创建世界上下文
的预设激活全部成功，才返回 `complete=true`。缺少上下文时请打开 Create World，再查询；
原生失败保持未完成，旧上下文/旧修订的回执不覆盖新草稿。

格式 3 世界包中的结构模块模式 2 保存调色板/游程压缩冻结数据、源码来源、目标 MC 数据版本及工具版本，均参与内容哈希。
旧的未发布格式 1/2 世界包需要重新生成，不会自动重写已有存档。结构源码只是来源记录，加载包时不自动执行。

会话与任务原子保存到 Mod 管理目录。`worldsmith_list_sessions / worldsmith_resume_session`
恢复计划、源码引用、结果和任务状态。重启将未完成任务标为 INTERRUPTED，不自动重跑。
容量耗尽或记录损坏会明确返回诊断，保留原文件；不静默淘汰未完成草稿。

`landmarkInstancesVerified=false` 始终保留：宏伟方案通过验证不等于世界中必定已有实例。

## 完整可回放示例

- [完整 Java 程序](examples/structure-agent/CourtyardProgram.java)
- [完整 MCP 调用序列](examples/structure-agent/workflow.json)：两套建筑群、一种独立建筑、可选成员和一次源码修正。

JSON 中每个 `tool` / `arguments` 是一次实际 MCP 调用。将整值 `$VARIABLE` 替换为前面 `capture`
指定的 `structuredContent` JSON Pointer 值；这只是展示返回值接线方式，不是新的 MCP 协议。
`waitUntil` 表示等待任务成功后继续，审批等待由玩家在 MC 操作。最终发布同样等待真实原生回执。
示例借用模板地形用于演示；正式生成须重新按玩家 prompt 设计 Terrain/Biome/Feature。

该调用序列由 `DocumentedDrawingExampleTest` 实际回放，源码通过附带 ECJ 构建。
原生结构、保存重载和固定种子一致性另由定向集成与隔离服务器验收。


新版创作入口与示例：[Structure Authoring Workbench](structure-authoring-workbench.md)。
