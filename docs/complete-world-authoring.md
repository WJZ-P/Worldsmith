# 从一句话到可检查的完整世界

Worldsmith 提供可复用的 MCP 创作和原生运行时，不内置调用某个 LLM 或图片服务。
外部 AI 将玩家的一句话展开为设计计划，再通过同一套类型化接口生成、预览、修正和保存。
因此“一句话”是玩家入口，不是省略中间设计与验证步骤的承诺。

## 完整模式

`worldsmith_begin_world` 显式选择 `mode: COMPLETE_WORLD`，建议 `detail: summary`。
旧客户端省略 mode 时仍为 `WORLDGEN_ONLY`；`STANDALONE` 用于单个作品，不等同于完整世界。

1. 读取 `worldsmith_get_content_contract(module: world_design)`，用
   `worldsmith_put_world_design_plan` 声明世界目标、真实 ID、跨模块关系和 Boss 对应任务。
2. 编写 theme、terrain、biomes、features、blocks、items、creatures、quests。
   使用 `worldsmith_build_texture` 或 PNG 导入绑定真实素材；生物构建返回 UV 后绘制皮肤。
3. 通过 SDK worker 生成结构，查看实际预览，提交建筑组装计划。Boss 地标使用
   [typed boss_spawner](creature-bosses.md)，不是一段任意实体 NBT。
4. 用 `worldsmith_get_generation_progress` 获取当前 revision、缺项和下一步参数。
   内容写入共享 `expectedRevision`，不靠重建素材解决 CAS 冲突。
5. `worldsmith_write_pack` 检查冻结几何和承诺的关系：计划中的方块实际使用、物品有来源、
   生物有配置的遭遇路线、任务绑定叙事、Boss 有真实 profile 和击杀目标。
   成功时默认导出一份可导入的 [`.wspack` 资源包](resource-packs.md)，返回其路径和摘要。
6. `worldsmith_finish_world` 在原生创建世界上下文继续验证、激活。
   独立 authoring host 的 `WAITING_NATIVE_CONTEXT` 是真实边界，不应被改写成成功。

这些检查区分“配置了可达来源”和“玩家在某个种子、位置已经找到它”。
固定地标库存及前置任务奖励按有限数量消费；自身/未来奖励不被用来解锁当前交付。
未知原版或其他 Mod 的获取机制不作猜测。世界地形、随机战利品和玩家操作仍需实际游玩验证。

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
没有世界唯一性账本、任意技能脚本、分支 NPC 对话或成就模块。
当前完整内容仍面向本地整合服务器世界；不是可直接分发的远程多人同步方案。
