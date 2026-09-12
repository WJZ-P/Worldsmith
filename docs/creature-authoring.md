# 生物创作基座

本文聚焦生物模型、贴图创作与内容访问。项目已安装地面近战 Boss 的 2–3 个生命阶段、
有界单线任务以及普通资源／遗物模块；这些能力分别遵循 [Boss](creature-bosses.md)、
[主线任务](mainline-quests.md) 和 [物品与奖励](items-and-rewards.md) 合约，不由模型大小或名字自动获得。

## MCP

后续生物贴图参考原版 Minecraft 的像素美术：有限色阶、整洁大色块、成簇阴影和清晰脸部/物种特征。
普通皮毛、皮肤与布料默认避免全身随机斑点、棋盘格及抖色；只有物种设定需要时才局部添加斑纹、鳞片或风化。
此规则同时用于外部图像模型 prompt 和确定性像素配方。保留实际 UV 面与朝向，在正背侧及运动预览中检查，
并按游戏内尺寸判断辨识度；已有两包贴图本轮保持原样。

1. `worldsmith_get_creature_authoring_contract` 读取完整字段与约束。
2. `worldsmith_build_creature(sessionId, recipe)` 自动编译骨骼／方盒／镜像与 UV，返回不可变 buildId 和定位图。
3. `worldsmith_preview_creature(sessionId, buildId, mode, view, pose)` 查看模型、UV 或多姿态联系表。
4. 上传真实 PNG 后，使用 `textureAsset` 重建并预览。
5. 将返回的 definition 合并到 CreatureLibrary，再按原有共享 revision 发布整个世界。

`worldsmith_get_creature_build` 恢复精确配方和生成定义。定位图不会自动挂为世界资产，也不会假装完成皮肤。构建结果与发布草稿分离，读取/预览不激活世界。

原生和离线预览共用 `CreaturePose`；预览包含 box UV、镜像、逐像素遮挡、固定机位及 idle/walk/windup/strike/recovery。它不是游戏截图或实玩验收。

## 本地 Java Builder 与 CLI

参见 [可复用源码与 API](examples/creature-authoring/README.md)。

```powershell
./gradlew.bat :core:creatureAuthoring -PrecipeFile=<Java或JSON> -PoutputDir=<目录>
./gradlew.bat :core:creatureAuthoring -PrecipeFile=<JSON> -PtextureFile=<PNG> -PoutputDir=<目录>
./gradlew.bat :core:previewCreature -PcreatureFile=<creature.json> -PtextureFile=<PNG> -PpreviewDir=<目录>
```

Java 源码只在开发者显式调用本地 CLI 时执行；MCP 编译的是配方数据，不要求调用方另行执行 Java。

## 创造模式与样例

[创造模式内容目录](creative-content.md) 自动枚举当前世界已激活的方块、物种召唤器和普通物品；items provider 已实际接入。

[黯星守门者完整外观素材](assets/creatures/darkstar-gatekeeper/README.md) 包括源贴图、256×256 运行时贴图、模型配方和真实效果图。

配方和 Java Builder 现可透传可选 drops（Builder.drops），重建时保留已设计的奖励；参见 [物品与奖励](items-and-rewards.md)。此字段不改变骨骼、UV 或动作模型。
