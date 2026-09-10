# 贴图由谁生成，其他 AI 怎么复用

**图像模型负责画图，Worldsmith 负责通用素材管线；二者没有绑定。**
上次 Boss 的贴图由会话里的生图工具按精确 UV 绘制，再导入 256×256 PNG；实际效果图来自
模型＋贴图的离线渲染。该生图工具不属于 Mod，其他 AI 不会因为连接 MCP 就自动拥有它。
但模型、配方、PNG、UV、内容引用和以下 MCP 接口都可以复用。

## 三类贴图

| 用途 | 当前实际支持 | 绑定 |
| --- | --- | --- |
| 普通物品 | 16..256 二次幂方形图标，可透明，<=1 MiB | items.textureAsset |
| 方块 | 16..256 二次幂方形；当前六面共用一张 tile | blocks.textureAsset |
| 生物 | 与骨骼方盒 UV 一一对应的图集，16..512 二次幂尺寸 | model.texture |

方块应考虑拼接边缘，生物必须先定模型／UV再画对应面；概念图不是可直接使用的实体皮肤。
当前没有六面独立方块贴图、PBR 或动画 PNG 支持。透明方块优先采用 GLASS profile。

## 支持 MCP 的 AI 可复用的、模型无关的部分

- `worldsmith_get_texture_workflow`：目标规格、完整配方说明、当前主机的导入 inbox。
- `worldsmith_build_texture`：用 palette＋seed＋fill/noise/checker/line/stamp 小配方生成真实 PNG，
  不依赖生图模型，也不需要输出上万项逐像素数组。
- `worldsmith_create_pixel_texture`：保留原有精确逐像素 palette/rows 接口。
- `worldsmith_import_texture_file`：导入专用 inbox 的单个 PNG 文件；源图保留，可显式最近邻整图缩放。
- `worldsmith_put_texture_asset`：供客户端以程序方式上传 PNG 字节（base64 仅是传输格式）。
- `worldsmith_preview_texture_asset` / `worldsmith_preview_creature`：返回真实图片和模型结果。

其他 AI 可调用自己的生图服务、使用人工绘制文件，或完全使用确定性像素配方。是否能画出好素材
取决于其创作／视觉能力，不应把“支持 MCP”说成“保证任何模型都能达到同样画工”。

本机文件入口只接受 inbox 内的单文件名，无任意路径／URL／软链接读取。云端客户端需要先传文件，
或由客户端程序提供 PNG 字节；不要求语言模型手抄大型 base64。`fitWidth`＋`fitHeight`＋
`resample="nearest"` 是显式导入适配，默认不偷偷改变尺寸，也不裁切或重排 UV。

完整机器可读说明来自 MCP `worldsmith_get_texture_workflow`；可复制配方见
[texture-recipes](examples/texture-recipes)。所有素材写入仍遵循同一 expectedRevision、PNG 校验和内容哈希。
