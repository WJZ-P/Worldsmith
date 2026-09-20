# 贴图由谁生成，其他 AI 怎么复用

**图像模型负责画图，Worldsmith 负责通用素材管线；二者没有绑定。**
上次 Boss 的贴图由会话里的生图工具按精确 UV 绘制，再导入 256×256 PNG；实际效果图来自
模型＋贴图的离线渲染。该生图工具不属于 Mod，其他 AI 不会因为连接 MCP 就自动拥有它。
但模型、配方、PNG、UV、内容引用和以下 MCP 接口都可以复用。

## 三类贴图

| 用途 | 当前实际支持 | 绑定 |
| --- | --- | --- |
| 普通物品 | 16..256 二次幂方形图标，可透明，<=1 MiB | items.textureAsset |
| 方块 | 16..256 二次幂方形；六面独立 PNG、每面 0..3 次 UV 转向；独立粒子图 | blocks.appearance.{up,down,north,south,west,east}.textureAsset / appearance.particle |
| 生物 | 与骨骼方盒 UV 一一对应的图集，16..512 二次幂尺寸 | model.texture |

方块应考虑拼接边缘，生物必须先定模型／UV再画对应面；概念图不是可直接使用的实体皮肤。
方块 schema 2 统一使用六面外观；当前没有 PBR 或动画 PNG 支持。透明方块采用 GLASS profile。

## 支持 MCP 的 AI 可复用的、模型无关的部分

- `worldsmith_get_texture_workflow`：目标规格、完整配方说明、当前主机的导入 inbox。
- `worldsmith_build_texture`：用 palette＋seed＋fill/noise/checker/line/stamp 小配方生成真实 PNG，
  不依赖生图模型，也不需要输出上万项逐像素数组。
- `worldsmith_create_pixel_texture`：保留原有精确逐像素 palette/rows 接口。
- `worldsmith_import_texture_file`：导入专用 inbox 的单个 PNG 文件；源图保留，可显式最近邻整图缩放。
- `worldsmith_put_texture_asset`：供客户端以程序方式上传 PNG 字节（base64 仅是传输格式）。
- `worldsmith_preview_texture_asset` / `worldsmith_preview_creature`：返回真实图片和模型结果。
- `worldsmith_preview_content_appearance(sessionId, kind:"block", ids:[id])`：绑定后的六面实贴图立方体、
  标注展开图、3×3 重复平铺和小尺寸查看；`kind:"item"` 支持 1..8 件物品在浅／深背景对比轮廓。
  图片来自已校验 PNG 的最近邻采样，不是游戏截图，也不是自动美术评分。

其他 AI 可调用自己的生图服务、使用人工绘制文件，或完全使用确定性像素配方。是否能画出好素材
取决于其创作／视觉能力，不应把“支持 MCP”说成“保证任何模型都能达到同样画工”。

本机文件入口只接受 inbox 内的单文件名，无任意路径／URL／软链接读取。云端客户端需要先传文件，
或由客户端程序提供 PNG 字节；不要求语言模型手抄大型 base64。`fitWidth`＋`fitHeight`＋
`resample="nearest"` 是显式导入适配，默认不偷偷改变尺寸，也不裁切或重排 UV。

完整机器可读说明来自 MCP `worldsmith_get_texture_workflow`；可复制配方见
[texture-recipes](examples/texture-recipes)。所有素材写入仍遵循同一 expectedRevision、PNG 校验和内容哈希。

## 六面外观与可感知反馈

`BlockAppearance` 明确包含 up/down/north/south/west/east 六个 `{textureAsset, quarterTurns}`，
以及 `particle` 和 `orientation`。`BlockAppearance.uniform(hash)` 只是一次填好六面的便捷构造；
存盘、校验和模型导出始终使用同一种 canonical 外观。旧 block-level `textureAsset` 已移除。

FIXED 材料的纹样保持世界坐标轴；HORIZONTAL 材料以 north 为局部正面，放置时朝向玩家，
结构 mirror/rotate 同步改变正面。只有声明 HORIZONTAL 的逻辑方块可以携带 cardinal `facing`；
光照、物理 profile、orientation 均由定义与存档绑定决定，任意 property 覆盖仍被拒绝。

`MaterialFamilyFactory` 提供可重现的归灯材料族：端面／侧面不同的梁木、四向路标、
闭芯／开芯灯座、菱铅玻璃和钥匙／灯芯／铆钉三个不同轮廓。`MaterialShowcaseExample` 将它们放进
真实工坊结构、补给箱与一次换芯机关：实际消费一枚灯芯后，护片打开，原生亮度由 0 变为 13。

验收须同时看离线审片与 native ClientGameTest。后者检查真实 baked model 和 particle PNG，
通过四次客户端放置数据包验证朝向，记录背包／玻璃／交互前后截图，并断言修复只付费一次。
