# 从一句话到可检查的完整世界

Worldsmith 提供可复用的 MCP 创作和原生运行时，不内置调用某个 LLM 或图片服务。
外部 AI 将玩家的一句话展开为设计计划，再通过同一套类型化接口生成、预览、修正和保存。
因此“一句话”是玩家入口，不是省略中间设计与验证步骤的承诺。
当前新写入为**格式6的九模块**；theme/quests仍为schema1，物品能力使用items schema2。
格式3/4/5只读恢复并保持原身份与原文，生成流程不自动升级既有世界。

## 完整模式

`worldsmith_begin_world` 显式选择 `mode: COMPLETE_WORLD`，建议 `detail: summary`。
旧客户端省略 mode 时仍为 `WORLDGEN_ONLY`；`STANDALONE` 用于单个作品，不等同于完整世界。

1. 读取 `worldsmith_get_content_contract(module: world_design)`，用
   `worldsmith_put_world_design_plan` 声明世界目标、真实 ID、跨模块关系和 Boss 对应任务。
2. 编写 theme、terrain、biomes、features、blocks、items、creatures、quests。
   使用 `worldsmith_build_texture` 或 PNG 导入绑定真实素材；生物构建返回 UV 后绘制皮肤。
   根据世界体验主动安排武器／工具、护甲、消耗品或固定动作遗物，不把所有自定义物品都做成提交凭证。
   items schema2的准确字段见 [物品契约](../core/src/main/resources/prompts/contract/items.system.md)，普通资源仍可没有能力。
3. 通过 SDK worker 生成结构，查看实际预览，提交建筑组装计划。Boss 地标使用
   [typed boss_spawner](creature-bosses.md)，不是一段任意实体 NBT。
4. 用 `worldsmith_get_generation_progress` 获取当前 revision、缺项和下一步参数。
   内容写入共享 `expectedRevision`，不靠重建素材解决 CAS 冲突。
5. `worldsmith_write_pack` 检查冻结几何和承诺的关系：计划中的方块实际使用、物品有来源、
   生物有配置的遭遇路线、任务绑定叙事、Boss 有真实 profile 和击杀目标。
   新玩家文案通过PlayerTextPolicy，技术提示留在作者回执；可用representativeContent指定真实物品／方块作为包图标。
   成功时默认导出一份可导入的 [`.wspack` 资源包](resource-packs.md)，返回其路径和摘要。
6. `worldsmith_finish_world` 分别报告原生准备和实际激活，按返回的等待状态继续。
   真实创建世界选择由玩家“用于创建世界”操作建立；普通进度查询／Core校验不触发整窗资源重载。
   `WAITING_NATIVE_CONTEXT`／`WAITING_ACTIVATION` 等等待状态保持真实，不写成已经进入玩家世界。

这些检查区分“配置了可达来源”和“玩家在某个种子、位置已经找到它”。
固定地标库存及前置任务奖励按有限数量消费；自身/未来奖励不被用来解锁当前交付。
未知原版或其他 Mod 的获取机制不作猜测。世界地形、随机战利品和玩家操作仍需实际游玩验证。

## 有用的装备与纯粹的玩家文案

把能力落实为真实DTO和来源：装备equipment、消耗品consumable、USE／MELEE_HIT动作actions。
MELEE_HIT仅支持非护甲武器／工具；材料和护甲可用USE。护甲普通右键穿戴，手持潜行右键发动主动能力。
护甲需要独立64x32、128x64、256x128或512x256的人形UV贴图，物品图标不充当穿戴纹理。
食物USE在完成食用时触发，生存自带1件消耗，动作consumeCount和durabilityCost均为0；失败不吃、不进冷却。
冷却是每玩家／世界／逻辑物品的共享组。blink最多8格且检查真实落点；统一投射物不爆破地形。
这些约束属于本文和作者回执；玩家lore写真实控制、效果和故事意义，不照抄DTO／校验机制。

清单、主题、物品与任务的玩家可见名称和描述聚焦世界本身。新创作不出现玩家进度包边界、地形检查、
原生加载声明、未实现功能等工程备注。`PlayerTextPolicy` 检测已知泄漏时返回字段级
`PLAYER_TEXT_ENGINEERING_LEAK`，作者重写该字段；不是发布后在UI中静默删句。
旧包加载不套这道新创作文案门禁，现有包、嵌入存档、任务进度及旧两包原文都不修改。

包图标从世界最有代表性的真实物品或方块中选择：write请求增加
`representativeContent: {"kind":"item","id":"existing_local_id"}`，或使用kind=block。
字段落在manifest，浏览器独立读取对应PNG，不为预览切换全世界资源。

## 断点与修复

失败写包保留有界、绑定 revision 的诊断回执，后续 progress 返回具体字段和修复工具。
内容变化后旧回执标记过期，成功写包清除失败状态；现有 PNG 和已完成绘图继续保留。
`completeWorldCoverageVerified` 与 `complete`、原生激活状态分别报告：Core 覆盖通过
不代表 Minecraft 已加载世界。

可直接使用[《月蚀诸峰》源套件](examples/complete-world/README.md)。执行器保存请求、回执、
素材 ID、源码/绘图 ID 和预览。修改生物文案且 UV 配方未变时复用原贴图。
局部改动后若有意复用旧 revision 的绘图，先核对未受影响的实现，再显式指定：

```powershell
node docs/examples/complete-world/run-mcp.mjs --phase architecture --reuse-drawings crown_gate,crown_belltower
```

只列经核对的目标；不要用全局绕过把已过期的模型误当成最新实现。

## 不启动玩家世界的原生导出

对已经成功保存、含 `worldsmith.json` 的完整目录运行：

```powershell
./gradlew.bat runDatagen "-PauthoredPack=C:/path/to/saved-pack"
```

使用与项目相符的 Java 25。此 opt-in 模式使用真实 Fabric/Minecraft bootstrap、生产编译器和
原生组件初始化器，输出到 `build/authored-native-export/worldsmith-authored/<bundle-id>/`：

- `native-datapack/`：原生世界生成 JSON、结构 NBT，以及内嵌的不可变世界包和素材。
- `client-resourcepack/`：由同一世界定义产生的方块/物品/生物资源。
- `export-report.json`：注册表 codec、结构模板、Boss 刷怪笼身份和内嵌包回读结果。

自定义导出使用独立 build 输出，既不清理普通 datagen 目录，也不把这份示例当成 Mod 内置资源。
导出入口为一次性开发进程，不绑定任何玩家存档、不创建游戏世界、不伪造 MCP 原生激活回执。
客户端资源文件导出也不等于资源重载和游戏画面验收。

## 当前边界

支持的 Boss 是地面近战、血条和 2–3 个生命阶段；自然稀有刷新与可重复地标刷怪笼是两条路线。
没有世界唯一性账本、任意技能脚本、分支 NPC 对话或独立成就创作模块；现有任务会映射为世界专属原版进度树。
当前完整内容仍面向本地整合服务器世界；不是可直接分发的远程多人同步方案。
