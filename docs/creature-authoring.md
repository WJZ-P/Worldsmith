# 生物创作基座

当前工作聚焦于创作和内容访问，不扩展 Boss 战斗、任务或完整独立物品系统。

## MCP

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

[创造模式内容目录](creative-content.md) 自动枚举当前世界已激活内容，并为未来普通物品提供 Provider 接口。

[黯星守门者完整外观素材](assets/creatures/darkstar-gatekeeper/README.md) 包括源贴图、256×256 运行时贴图、模型配方和真实效果图。

配方和 Java Builder 现可透传可选 drops（Builder.drops），重建时保留已设计的奖励；参见 [物品与奖励](items-and-rewards.md)。此字段不改变骨骼、UV 或动作模型。
