# 黯星守门者：外观样例

这个样例展示生物创作基座，不包含新 Boss 战斗系统、阶段技能或任务链。

- 模型：14 骨骼、56 方盒，来自 `docs/examples/creature-authoring/DarkstarGatekeeper.java`。
- 贴图：内置 ImageGen 根据精确 UV guide 绘制，暗石、古铜、紫晶风格。完整生成提示词见 `imagegen-prompt.txt`。
- `texture-source.png`：ImageGen 原始 1254×1254 图，保留不动。
- `texture.png`：导入用 256×256 PNG，仅对整张源图按像素中心最近邻缩放；未重排 UV、裁切或补绘图形。
- `creature.json` / `creatures.json`：绑定最终 PNG 实际 SHA-256 的运行时定义。
- `recipe.json` / `uv-layout.json` / `uv-guide.png`：可继续编辑的模型配方和逐面 UV 信息。UV guide 是定位图，不是完成皮肤。
- `previews/hero.png`：真实模型＋最终贴图的离线效果图。
- `previews/sheet.png`：多视角／五种姿态对照；其余 PNG 与哈希清单在同目录。

效果图不是 Minecraft 截图；紫晶亮色属于贴图画法，没有宣称发出原生环境光。样例仍使用已有地面生物及基础近战行为，未自动添加到内置世界或打开用户存档。

要用于世界：先经 MCP 上传 `texture.png`，然后将 `creature.json` 合并进当前会话的 CreatureLibrary；与主题／群系一起发布。进入该世界后，在“Worldsmith：当前世界”创造分类获得对应物种召唤器。
