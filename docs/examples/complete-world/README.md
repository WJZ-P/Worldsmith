# 月蚀诸峰：可复用的完整世界源套件

这个目录把 `prompt.txt` 的一句世界设定展开为显式、可检查的输入，而不是把
“生成完成”当成一句模型回答。任意能够调用 Worldsmith MCP 的 AI 都可以阅读、
修改和执行它；纹理由程序化配方生成，不依赖特定图片生成服务、账号或模型。

## 内容与入口

`kit.json` 是文件入口和稳定 ID 清单。保留已有 ID 有助于延续任务、引用和生成记录。

| 输入 | 内容 |
| --- | --- |
| `world-design-plan.json` | 目标、模块间关系、四段叙事和 Boss 对应关系 |
| `modules/` | 地形、8 个群系、9 个地物、4 个方块、4 个物品、主题、4 段任务 |
| `creatures/` | 银角月鹿、碎晶哨兵、三阶段黯星守门者 |
| `textures/` | 8 份方块/物品程序化纹理配方 |
| `make-creature-texture.mjs` | 根据真实构建返回的 UV 布局生成生物纹理配方 |
| `structure-targets.json`、`structures/sources/` | 5 个 Java 源文件、12 个实际 SDK 绘图目标 |
| `architecture.json`、`structures.json` | 王城地标、村落和修道院群组，以及 3 个独立探索地点 |
| `run-mcp.mjs` | 有请求记录、断点状态和真实 MCP 回执的分阶段执行器 |

`@asset:<id>` 和 `@drawing:<id>` 是本套件的引用语法；执行器用当前会话实际返回的
资源 ID 和绘图 ID 替换它们。它们不是 Minecraft 原生资源路径，也不是待猜测的 ID。

## 第三方 AI 的复用方式

1. 先读取 `prompt.txt`、`kit.json` 和设计计划，保持资源 ID、叙事、产出来源一致。
2. 确认当前 MCP 的工具目录和 schema。此示例需要完整世界设计、纹理、生物、
   SDK 绘图和内容写包工具；执行器本身不调用任何 LLM。
3. 使用可执行这些 Java 源文件的真实 authoring host。启动/源码执行许可由宿主处理；
   该脚本只连接既有 host，不启动游戏、不创建玩家存档，也不生成原生发布回执。
4. 新世界使用新的输出目录；续接同一个世界保留原输出目录及 `state.json`。
   发现文件里的 `running` 只是记录，连接前仍须确认 host 正在响应。

以下命令从仓库根目录执行。`--endpoint` 使用 host 当前公布的 MCP URL；也可以用
`--discovery` 指定它写出的 `mcp.json`。使用支持内置 `fetch` 的 Node.js。

```powershell
$runner = (Resolve-Path 'docs/examples/complete-world/run-mcp.mjs').Path
$out = Join-Path (Get-Location) 'build/my-eclipse-world'
$discovery = 'C:/path/to/authoring-host/mcp.json'

foreach ($phase in 'plan','textures','creatures','drawings','modules','architecture','write','status') {
    node $runner --out $out --discovery $discovery --phase $phase
    if ($LASTEXITCODE -ne 0) { throw "Phase failed: $phase; inspect its saved response before continuing." }
}
```

不要把其他会话的 `state.json` 直接复制到新世界。会话、asset、build、drawing ID
都来自各自的实际生成记录。`drawings` 支持 `--targets crown_keep,crown_gate`，
但更改共享 Java 文件时，应重建所有依赖受影响源文件的目标。

### 修正数据后续接

- 只修改 `modules/`：对同一输出目录执行 `modules` → `write` → `status`。
  沿用已经生成的纹理、UV 和绘图，不重复制作素材。
- 修改结构定义/组装：执行 `architecture`，再 `write`、`status`。
- 修改 Java 建筑：执行相关 `drawings`，再 `architecture`、`write`、`status`。
- 修改生物或静态纹理：执行对应 `creatures` / `textures`，再 `modules`、`write`、`status`。
  若设计目标或关系也变化，先更新计划并执行 `plan`。
  生物仅改职责/行为等元数据时，执行器比较真实 UV 派生配方；配方未变且纹理资源仍在
  当前会话，就复用既有纹理，仅重新构建生物定义，不重复制作皮肤。
- 写包失败时修复诊断指定的输入，不降低校验要求；响应中的候选 ID 不代表已保存。

当前 `write` 会默认输出可导入的 `.wspack`，回执包含 `resourcePackReady` 与文件路径。
可传 `--resource-pack-filename eclipse-crown.wspack` 自定名称，`finish` 沿用成功写包的文件名。
若错误为 `RESOURCE_PACK_EXPORT_FAILED` 且 `packPreserved: true`，Core 内容已保存：
按该回执的导出重试参数修复文件交付即可，不重复生成素材。

若局部更新源码后有意保留经核对、未受影响的旧绘图，可以给 `architecture` 显式传入
`--reuse-drawings target_a,target_b`。执行器只为列出的冻结目标设置 `allowPreviousRevision`，
不会自动放过所有过期图。共享方法变化时仍应重建所有受影响目标。
  `write` 检查有效性和实际路径并自动读回；已保存的包可单独运行 `inspect` 再检查，
  无需重新写包。状态以当前 MCP 回执为准，续接使用简要会话信息。

## 生态参数与已知取舍

地物 `density` 以及群系内的覆盖值均为 **0..1**；它们不是任意倍数。
`patch.attempts` 控制单片尝试次数。地表和水生 patch 只声明水平扩散，
`verticalSpread` 留给洞穴和悬挂 patch。

本套件让浅海海草密度为 0.65、深潭为 0.30；草甸月花为 0.55、林下为 0.25；
草甸草为 0.85、丘陵为 0.45。这样区别生态，而不是把所有超界值直接截成 1。

地表/水生 patch 的每区块预算按 `round(density * 24) * patch.attempts` 计算，
还要加上同群系树木和其他地物的预算。本例花/草/海草每片分别尝试 2/1/2 次，
8 个群系的总预算依次为 14、32、0.038、47、24.702、12.105、0.144、0.088，
均低于 64 的上限并留出余量；调整密度时请重算群系总和，而非只验证单值范围。

目前保留 8 个群系：平地由湿度分为草甸/银松林，高地由温度分为琥珀丘陵/碎星山脊。
海岸和峰顶各只有一个群系，所以 `MONOTONE_RELIEF_BAND` 的 COAST/PEAKS
警告是实际设计限制：这些带内的群系边界主要跟随地形，尚无横向气候分区。
不要把不同装饰密度当成已经解决该警告，也不要为消除警告把海滩套成丘陵。
若扩展世界，可新增真正有不同生态和表面的海岸/峰顶群系，并同步 ID 清单和设计目标。

## 四段主线的真实来源

| 任务 | 可检查的来源/动作 | 后续保障 |
| --- | --- | --- |
| `gather_moonlight` | 圣坛容器固定 8 枚月辉碎晶；采石场固定 12 枚；玩家击杀哨兵掉落 2–4 枚 | 主动提交 8 枚，支持分次交付 |
| `silence_shards` | 任务解锁后，在琥珀丘陵或碎星山脊击杀 3 名哨兵 | 不把首段之前的击杀追记为进度 |
| `break_darkstar` | 王城大厅的专用刷怪笼，或碎星/霜冠稀有自然生成；击杀一只黯星守门者 | 领取任务奖励固定获得 1 枚核心；无需依赖掉落开关 |
| `rekindle_crown` | 主动提交 1 枚核心，再领取复晓徽记 | 使用前一段奖励，不依赖本段或未来奖励自我解锁 |

容器内容固定不等于建筑在出生点必定生成；地形适配、放置条件和实际探索仍会影响获取。
黯星守门者有血条和三阶段参数，既可在碎星/霜冠稀有自然生成，也可由王城大厅
`(0,3,-6)` 的 typed BossSpawner 提供重复遭遇。该点地板在 y=2，绘图构建会验证
x=-6..6、z=-12..0、y=3..9 的净空。配置为间隔 2400 ticks、玩家激活范围 16、
水平生成范围 4；碰撞检测和附近同类限制仍会影响生成。它不是全世界唯一的 Boss，
也不承诺王城必定放置或进入后立刻刷出。源数据中的阶段阈值为 100%、60%、25%，
实战节奏仍需要原生游戏验证。

刷怪笼的附近上限按 `EncounterBoss` 类别检查生成范围附近的实体，设为 1；
已有 Boss 离开这个局部范围后，后续周期可再次生成，不套用自然生成的 128 格间距。
这是可重复的局部遭遇点，不是全地图唯一性或多处刷怪笼之间的全局去重。
设计计划以 `crown_citadel → darkstar_gatekeeper` 的 `contains_encounter` 关系
承诺实际刷怪笼交互，再以任务的 `kill_objective` 连接击杀目标。

## 验证层级：按证据报告，而不是按意图报告

1. **源数据检查**：JSON 可解析、ID 和引用一致；这仅代表输入可读。
2. **实际构建**：MCP 生物/SDK worker 返回成功、产生真实 asset / build / drawing ID。
3. **视觉检查**：查看真实 PNG。结构预览使用方块代表色，区别于完整纹理采样，
   也区别于原生游戏截图；有预览不代表地形放置或战斗已运行。
4. **完整写包**：`write-pack.json` 中 `valid: true`、完整模式的
   `completeWorldCoverageVerified: true`，再由 `saved-content.json` 读回实际内容。
   仅有设计计划或模块草稿不满足这一层。
5. **原生验证**：导出并读回数据/资源文件，另在隔离游戏环境检查激活、生成、
   容器、任务和 Boss 行为。导出成功与实际游玩验证是两份独立证据。

证据保存在输出目录的 `requests/`、`responses/`、`previews/`、`derived-textures/`
和 `state.json`。请检查当前回执，不把 README 当成某次运行全部通过的证明。
`finish` 是单独阶段：独立 authoring host 返回 `WAITING_NATIVE_CONTEXT` 时，
保留该状态，进入真实原生上下文后继续；写包成功不等于世界已激活。

本次增量运行的可核实摘要见 [2026-09-11 验证记录](verification-2026-09-11.json)，
原生导出命令见[完整世界创作说明](../../complete-world-authoring.md)。验证记录不是未来运行的
保证；从头重放会使用当前全部源码，而增量运行可能显式保留旧的冻结来源，所以 bundle ID
不应被当作所有重放都必须相同的黄金值。

新增单文件交换的[资源包回环记录](resource-pack-verification-2026-09-11.json)包括 `.wspack`
字节一致性、重复导入去重、跨草稿 PNG 复用、默认生成收尾导出，以及导入后的原生回读。
这是共享后端与文件验证；游戏资源库按钮尚未做手动界面验收。
