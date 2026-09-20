# 世界观驱动的完整世界创作

新 `COMPLETE_WORLD` 的主线是：**保存世界观 → AI 自检 → 计划与任务书 →
按依赖制作 → AI 实现审稿与工程检查 → 冻结与发布**。外部 AI 执行扩写与语义
审稿；Mod 负责持久化、引用、摘要时效、证据覆盖与已有工程检查，不内置隐藏 LLM。
AI 自检通过后自动继续，不增加“等待用户批准”步骤。绘图源码执行的独立批准机制仍保留。

## 一份设定事实源

`WorkflowSession.authoring` 保存完整 WorldBible、语义版本、任务书、AI 报告及有界
修复计数。世界观包括原始需求引用、硬约束、AI 假设、世界前提、玩家身份、主要冲突，
以及具稳定 ID 的规则、历史、区域、文化、生态、资源、视听风格和体验节点。
节点区分 `FACT`、`LEGEND`、`BELIEF`；世界内传说与作者事实不是同一叙述层次。

结构化对象是唯一修改入口，Markdown 是确定性阅读视图，不维护另一份独立长篇正文。
现有运行时 `WorldTheme` 从世界观压缩派生，并通过其任务书核对设定来源、内容摘要与
真实叙事锚点。世界事实变化先回写 WorldBible，再更新任务书及主题，避免两套故事。

在制作前一起安排区域生态、主线节拍、资源来源、交付消耗与奖励意义。地形优先仅指
这些依据建立后的实体生产依赖，不是先做完地形、生物、物品再拼接故事和任务。
`requiresBoss` 明确本次 Boss 承诺；新完整世界仍保留群系、建筑、生物、方块、物品、
任务六类内容，和平探索可以不承诺 Boss。已声明的 Boss 仍需真实 profile、遭遇与对应任务。

请求中的机制若超出已安装能力，记录 `openDecisions` 与具体范围问题，不把需求写成背景
故事后宣称已兑现。quests schema2 与 story schema2 提供事实驱动的分支任务、居民、对话、交易与日程；
这些行为需要真实定义、标记和事务，设定文字不代替执行规则。局部世界改造继续使用能力程序。

## MCP 接口

以下作者态写入都使用 **session 的 `expectedRevision`**；`bibleRevision` 是单独的
语义版本，不是 CAS 值。保留每次写入返回的 revision，冲突后读取、合并，不覆盖他人更改。

| 工具 | 输入要点 | 作用 |
| --- | --- | --- |
| `worldsmith_get_world_bible` | `sessionId`, `format: json\|markdown`, 可选 `summary`, `nodeIds` | 全文、摘要或指定节点；筛选视图不当作全量写入 |
| `worldsmith_put_world_bible` | `sessionId`, `expectedRevision`, `bible` | 更新唯一结构化设定 |
| `worldsmith_get_authoring_review_context` | `sessionId`, `subjectId` | 读取实际待审快照、摘要、检查项与证据 |
| `worldsmith_review_world_bible` | `sessionId`, `expectedRevision`, `review` | 提交 `subjectId: world_bible` 的 AI 自检 |
| `worldsmith_put_module_briefs` | `sessionId`, `expectedRevision`, `briefs`, 可选 `removeIds` | 按 ID 原子合并任务书，显式删除指定 ID |
| `worldsmith_review_world_alignment` | `sessionId`, `expectedRevision`, `subjectId: briefId`, `review` | 审查该任务书的实际实现 |
| `worldsmith_upgrade_world_authoring` | `sessionId`, `expectedRevision` | 显式升级旧 COMPLETE_WORLD 会话，不删除已有产物 |

准确字段与预算见 [WorldBible 契约](../core/src/main/resources/prompts/contract/world_bible.system.md)
和 [ModuleBrief 契约](../core/src/main/resources/prompts/contract/module_briefs.system.md)。
读取 draft/resume 中保存的作者态，再合并任务书；未出现在更新请求里的任务书保持原样。
每个目标有一个所属任务书，跨任务书共享通过依赖记录。生产依赖无环，不把世界内每条
互相关联都当成制作依赖。`BriefRecord` 的版本与依据摘要由服务盖章，而非客户端自证。

不直接携带目标 ID 的高成本工具 `worldsmith_build_drawing`、`worldsmith_build_texture`、
`worldsmith_import_texture_file`、`worldsmith_put_texture_asset` 和
`worldsmith_create_pixel_texture` 在新完整流程中携带 `briefIds: [briefId]`。
生物构建从 `recipe.id`、模块和结构写入从真实内容 ID 推导所属任务书。
这是工具参数，不是新增运行时 JSON 字段。旧会话及轻量模式保持原参数兼容路径。

## 从实际上下文审稿

读取审稿上下文后：

1. 将 `expectedBasisDigest`、`expectedContentDigest` 分别放入报告的 `basisDigest`、
   `contentDigest`；`expectedInputDigest` 标识本次输入／重试依据，不另加到报告 DTO。
2. 覆盖 `requiredCheckIds`，用相关 `basisRefs` 说明需求和设定出处。
3. 从 `evidenceRoots` 及其已存在子路径选择 JSON Pointer，指向 `evidenceDocument`
   的真实字段。`blockedEvidenceRoots` 仅用于 BLOCKED 的缺失证据结论。
   若 `evidenceDocumentIncluded: false`，`evidencePreview` 只是片段，先读取相关完整
   当前草稿／结构定义再判断，不把被截短的预览当成完整证据。
4. 每项填写实现 `claim`、实际 `evidencePaths`、有理由的 `conclusion` 与
   `status: PASS|BLOCKED`；失败结论明确矛盾、修复项或范围决策。
5. 保存 AI 报告，再读取 progress 的精确下一步。报告 `source` 固定 `AUTHORING_AI`。

审稿链是“需求／设定 → 实现主张 → 实际证据 → 结论”，不是只挂一个 basisRef、
写泛化赞语或输出任意一致性分数。程序核对报告覆盖和时效；它没有自动证明文学合理性。
AI 已审核也不等于用户确认、模型实看、声音实听或游戏实玩。

既有工程检查继续负责 ID／引用、冻结几何、贴图、目标覆盖、任务前置、自锁／后锁和
有限补给消费。正概率来源不证明取得成本合理，静态交付检查不证明实际路程、耗时、
战力、地标放置或随机掉落。体验审稿应把未观察项说清楚，不冒充实玩证据。

## 修订、修复与恢复

- 全局前提、用户硬约束、规则变化，使受影响的任务书与实现审核待复核。
- 局部节点变化沿引用和显式依赖传播；引用节点被删除时保留引用方并报告缺口。
- 任务书、相关模块、实际引用的纹理或几何变化后，旧实现审核失效。
- 与当前内容无关的 PNG 上传不单独使世界观审核失效；未知影响采用保守复核。
- 失效保留原有草稿、PNG、已完成绘图与冻结几何，不等于全部重生成。
- CAS 和输入摘要共同保护迟到的报告／发布结果，旧回调不覆盖新修订。
- 同一输入与同一阻断项最多连续自动修复 3 轮；仍失败时返回具体阻断或范围决策，
  不无限自评。修复对象或输入实质变化后重新计算对应预算。

中断后读取 `worldsmith_resume_session(detail: summary)` 和
`worldsmith_get_generation_progress`，沿当前缺口继续，避免重启已有成功工作。
阶段增加 `WORLD_BIBLE_DRAFT`、`WORLD_BIBLE_REVIEW`、`MODULE_BRIEFS`、
`CONTENT_ALIGNMENT_REVIEW` 与 `WORLD_AUTHORING_BLOCKED`。
`write_pack` 和 `finish_world` 针对实际候选再次执行作者态与既有工程门槛，inline
文档也遵守这一规则，而不是只在提示词或第一个制作工具里检查。

## 阅读界面与数据边界

创建世界的“天工开物”标签在当前选中会话有世界观时显示 **查看世界观**。
页面展示设定版本、AI 审核状态、当前阶段和有效内容审稿计数；正文支持滚轮、滚动条、
Page Up/Down、Home/End，Esc／返回回到原创建页。该页是打开时的只读快照，重新打开
读取新快照；编辑继续通过 AI/MCP 写回同一会话。

展示对象同时匹配选中的 session 与世界包，导入包没有作者态时不借用其他会话设定。
当已有世界包被选中而新世界仍在制作时，另有 **当前创作稿** 按钮，只读当前活动且
未完成的作者会话；悬停标明新稿标题，打开的页面明确标为创作稿。它采用后台单次快照，
核对连接、会话归属和修订，不替换旧包卡片、不切换待创建世界包或世界类型。
Markdown 视图最多 64 Ki 字符，截短会明确提示；完整结构化数据仍留在 session，
`worldsmith_get_world_bible(format: markdown)` 可读取全文。存储超预算则报告错误，
不通过截短悄悄丢设定。共享 session 仍有 8 MiB 持久化上限。

浏览只读取缓存，不生成内容、不编译几何、不读取 PNG、不触发客户端资源重载或原生
激活；世界类型选择、默认存档名、入场提示与延迟创建行为保持原有路径。

## 兼容和交付

新作者流程的世界包名称和 `WorldTheme.title` 与 `WorldBible.title` 保持一致。
新流程调用 `write_pack` 可省略 `displayName`，默认取世界观标题；旧调用仍需提供非空名称。
相同内容目录复用时还会检查实际保留的包名，不覆写旧包的展示元数据。
`manifest.description` 仍是展示摘要，沿用现有玩家文本校验；本期不把它宣称为另一份已绑定证据的语义审稿结果。

可参考 [灵晶古城设计夹具](../core/src/test/resources/authoring/crystal-world-design.json)：它展示设定、目标关系和任务书如何连接碎晶来源、普通守卫、两章任务与主动治疗奖励。
这是作者态设计示例，不是已生成的运行时模块、可导入世界包或实玩证明。

| 对象 | 行为 |
| --- | --- |
| 新 COMPLETE_WORLD | 开启持久作者态完整流程 |
| WORLDGEN_ONLY / STANDALONE | 保留轻量范围，不强制完整世界观、物品、任务或 Boss |
| 旧未完成 COMPLETE_WORLD | 保留旧工作流及原覆盖要求；仅显式升级启用新作者态 |
| 旧包、嵌入存档与原样重新嵌入 | 不追加作者态加载门槛、不改已有内容身份 |
| 导入旧包继续创作 | 保留原包；在新作者会话中补齐设定、任务书与当前实现审稿 |

完整 WorldBible、任务书与审核证据目前只随 **session** 持久化；`.wspack` 仍是现有
格式 10 十二模块与资产，不声称可携带完整跨设备创作依据。原生可加载与实玩状态继续独立
报告，AI 审稿通过不代替它们。发布文件与原生流程见 [完整世界创作](complete-world-authoring.md)，
界面口径见 [生成进度界面](generation-progress-ui.md)。

## 人工核对清单（不代表本轮已执行）

- 新会话先世界观、自检、计划和任务书；通过后自动继续。
- 和平世界不被固定 Boss 模板阻断，已承诺 Boss 仍检查真实实现。
- 设定／模块修改后旧审核过期，已有图像与几何保留，断点恢复沿缺口继续。
- 合法引用但动机／来源矛盾产生具体 AI 审稿问题，缺证据报告不算通过。
- 在不同包／会话间切换，只读页面不串用设定；返回保留创建页状态。
- 长文截短明确标注，MCP 全文与会话事实保持完整；浏览期间没有资源重载。
- 实际试听、路线、战斗与随机放置仍单独实玩核对。

## 可执行交互

需要仪式、钥匙或兑换时，使用 [mechanics 模块](mechanics.md)：WorldBible RULE → mechanic 目标与任务书 → 实际 pattern/cost/state/actions → 当前审核证据。叙事规则不代替执行规则；十二模块中 mechanics 可为空，但显式承诺过的装置必须有真实定义。
