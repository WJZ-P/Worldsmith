# 宏大世界：先组织体验，再扩大资产目录

宏大不等于把群系、建筑和怪物数量拉满。Worldsmith 的创作指引现在要求 AI 先想清楚
世界骨架、区域差异、旅行路线、资源来源和主线关系，再分批落实真实内容。
这是对现有九模块工作流的增强，不是新增 region、faction 或分支任务系统。

## AI 实际从哪里读到

- 非 `STANDALONE` 的 `worldsmith_begin_world`，包括 `detail: "summary"`，返回紧凑的
  `worldPlanningGuide` 和 `worldPlanningReference`，后者指向 `grand_world/world-atlas`。
- `worldsmith_get_contract` 支持 `id: "grand_world"` 的全文、`detail: "index"` 目录
  和 `section` 分节读取，不要求每轮把全部 MD 塞回上下文。
- begin 和 `worldsmith_get_content_framework` 的 `authoringBudgets` 来自代码常量；
  先核对当前安装版本的预算，再决定规模。
- 宏观规划之后，使用现有 `world_design` 合约提交命名目标、链接和 Boss/任务关系；
  进度页因此能显示可读名字，而不是只有匿名数量。

```json
{"id":"grand_world","section":"world-atlas"}
```

将上面的参数交给 `worldsmith_get_contract`。其他分节为 `content-budgets`、
`region-routes`、`production-batches`、`global-review`、`runtime-boundaries`；
`overview` 为紧凑入口。

## 一份可执行的规划应该回答什么

| 层级 | 创作决策 | 实际承载位置 |
| --- | --- | --- |
| 世界骨架 | 海陆、山脉、水系、特殊层、关键地点 | terrain 和 biome 的现有字段 |
| 区域身份 | 生态、色彩、轮廓、资源、建筑功能、生物、历史 | 规划文本、theme、各领域模块 |
| 旅行节奏 | 补给、远景、过渡区、绕行、高潮 | 具体地形/建筑/投放策略；路线文本本身不铺路 |
| 主线关系 | 什么先获得、什么后消耗、在哪里遇见目标 | 真实 kill/delivery 任务、掉落、奖励和 typed BossSpawner |
| 规模预算 | 定义数量、分类型方块槽、蓝图/方案/体素、PNG | 当前 `authoringBudgets` 和整个包的累计账本 |
| 交付证据 | 哪些已定义、已渲染、已冻结、已原生验证 | progress、预览、验证回执和实际 `.wspack` |

规划摘要保存在 `WorldDesignPlan.goal`，具体职责放在目标的 `purpose`；
随资源包保留的世界观使用 theme 的 `premise`、`worldRules`、`beats`。
设计计划属于创作会话，不是资源包新增模块。完整长篇世界地图说明可单独保留为 MD，
不要把未知 JSON 字段塞进合约，也不要虚构 `region` 类型的 ContentKey。

## 扩大规模的方式

1. 按玩家 prompt 选择尺度：小而密集、多个相连区域，或跨度更大的探索世界。
   不把示例里的数量当作每个世界的最低配额。
2. 每个区域都有独特理由，同时保留过渡地带、共享材料体系和安静空间。
   群系在气候选择中实际出现，才有可感知的生态差异。
3. 先做一个代表性片段，检查真实建筑、贴图、生物和资源关系，再扩展同族变体。
4. 分批制作但共用一份预算账本。重复引用相同 PNG 可复用摘要；变更像素或 UV
   布局需要重新核验。每方案上限、整包模板总上限和模块数量上限分别计算。
5. 最后横向检查区域重复、旅行可读性、任务死锁、有限资源消耗和性能风险。
   预留修订余量；接近校验上限并不等于运行性能已经验收。

已有会话继续使用 `resume_session` 和真实缺口，不重做已成功的模型、资产或步骤。
多人/多 agent 可以独立设计文件，写同一会话时仍须遵循共享 revision。

## 需要说清楚的边界

- “五大区域”首先是设计组织方式，不是五个新增硬边界运行时分区。
- 地形锚点与气候偏移可表达关键地点意图；固定/线状建筑仍受真实落点校验约束。
- 线形锚点不自动修路；普通随机群系也不会仅因叙事写了“北方”就位于北方。
- 势力史可以通过遗迹、材料和文本表达；NPC 外交、声望、分支对话和动态战争未安装。
- 当前任务是线性击杀/交付；BossSpawner 是可重复的局部遭遇，不是全局唯一 Boss。
- 定义目录数量不是无限地图中的实例数量。探索新区块使用冻结内容，不会实时调用 AI。
- 地图路线、建筑落点、旅行时长和可玩性，需要对应采样/原生/游玩证据，单看 MD 不算验收。

参见 [未生成的宏大世界规划示例](examples/grand-world-planning.md)、
[完整世界创作](complete-world-authoring.md)、[资源包复用](resource-packs.md)，以及
[AI 实际读取的完整合约](../core/src/main/resources/prompts/contract/grand_world.system.md)。
