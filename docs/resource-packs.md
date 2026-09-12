# 可复用的 Worldsmith 资源包：`.wspack`

`.wspack` 是一个自包含的 Worldsmith 创作包：一个文件同时携带世界定义和实际资产。
它不是只有贴图的 Minecraft 原版资源包；使用它仍需要对应版本的 Worldsmith。
导入和选择用于创建世界是两个独立动作。

常规生成链路的 `worldsmith_write_pack` 默认生成 `<bundle-id>.wspack`，在返回体中提供
`resourcePackReady` 和 `resourcePack` 文件回执；`worldsmith_finish_world` 也检查这份可交付文件，
然后再继续独立的原生流程。可用 `resourcePackFilename` 指定名称，并沿返回的 `nextArguments`
传递给下一步。归档失败会保留已冻结的 Core 内容并明确报告，不把部分成功伪装成完整交付。

## 包里有什么

容器版本为 **1**，使用普通 ZIP32；根目录 `wspack.json` 声明容器格式与内层 bundle ID：

```json
{"format":"worldsmith-resource-pack","containerVersion":1,"bundleId":"<sha256>"}
```

其余文件来自既有 `WorldContentBundleIO.encode`，不另外拼一套内容格式：

| 内容 | 存放方式 |
| --- | --- |
| 内容清单、依赖和资源摘要 | `worldsmith.json`，当前世界格式 6 |
| 世界观、地形、群系、地物 | 对应模块 JSON |
| 方块、物品、生物、Boss、任务 | 对应模块 JSON；生物模型骨骼、立方体、UV、行为参数也在定义内 |
| 建筑与组装 | 结构索引、蓝图和 `drawings/<hash>.wsdraw` 冻结几何 |
| PNG 原始字节 | `assets/<sha256>.png`，同一资产只列一份 |
| 作者源码出处 | 既有结构来源记录，作为可读数据保留，不在导入或运行世界时编译执行 |

格式 3/4/5 的旧世界包可保留原身份进行封装和导入，不自动迁移、不附加新玩法语义。
导出不收集宿主设置、会话日志或玩家存档；任务的玩家进度也不属于可分享的定义包。

## 复用究竟发生在哪一层

- **整包复用**：按 bundle SHA-256 入库。再次导入同一内容返回 `reusedExisting`，不产生第二个同内容目录。
- **贴图复用**：导入的 PNG 加入共享 `content-assets` 内容寻址存储，相同字节沿用同一句柄。
- **冻结资产复用**：模型定义与建筑几何直接读回，不为导入动作重新调用图片模型或 SDK worker。
- **草稿复用**：`worldsmith_attach_pack_textures` 把已有包的 PNG 批量附加到另一个草稿，使用一次共享 revision 更新。

PNG 去重是字节哈希去重，不是“视觉上相似就合并”。自包含的两个外发文件各带完整依赖，
以便离线移动；这不意味着所有磁盘副本都被全局消除。
当前不自动拼接多个包的生物、物品和任务定义：相同 local ID 可能有不同含义，
作者应显式处理引用和依赖，再冻结成新的 bundle ID。

## 游戏入口

主菜单在“选项 / 退出游戏”上方的小图标行末尾提供 **地球＋铁砧** 按钮。
它与现有图标一起居中排列，有无 Mod Menu 都按实际按钮数布局；悬停和键盘焦点
显示“世界资源包”提示。窗口缩放或返回主菜单不会重复添加。
“选择世界”页面不再另放右上角文字按钮；资源库统一从主菜单图标进入，不依赖 Cloth Config。

界面仅保留标题、左侧卡片、右侧详情，以及底部一行“用于创建世界 / 世界包目录 / 返回”。
没有版本说明栏、资源库/导入目录页签、手动刷新或固定提示横幅。

1. 打开“世界包目录”，把 `.wspack` 放进去。目录变更会自动更新列表，不必手动刷新。
2. 左侧用配置中的名称显示卡片；空列表显示“暂无导入的资源包”。已保存的世界包也会显示，并按内容 ID 去重。
3. 选择卡片，右侧显示 description 和群系、建筑、地物、方块、生物、物品、任务、贴图的配置数量。
4. 点击“用于创建世界”才完整校验并导入外部归档，再进入既有创建流程；浏览目录不自动激活或执行源码。

目录监听只在页面打开时持有资源，关闭时释放；不支持原生文件通知的文件系统使用后台定时快照。
文件复制期间先做短暂防抖，配置读取在 IO 线程完成并缓存，不在绘制时扫描或解码 PNG/几何。
卡片与统计是配置预览，不取代创建世界时的完整校验。导出仍可通过 MCP / CLI 使用。

库目录位于宿主的 `worldsmith/packs`；交换文件位于其同级
`resource-packs/inbox` 与 `resource-packs/exports`。“世界包目录”打开的是 `inbox`。

## MCP 与其他 AI

先调用 `worldsmith_get_resource_pack_workflow` 获取当前宿主的真实目录和支持范围。
导入工具只接受导入目录中的文件名，不读取任意路径、URL 或链接文件。

| 工具 | 用途 |
| --- | --- |
| `worldsmith_inspect_resource_pack(filename)` | 完整检查外部文件，不入库、不激活 |
| `worldsmith_import_resource_pack(filename)` | 验证后入库，保持原内容 ID |
| `worldsmith_export_resource_pack(id, filename?)` | 从已保存世界包导出一个 `.wspack` |
| `worldsmith_list_packs` / `worldsmith_inspect_world_content` | 沿用既有库查询与内容检查 |
| `worldsmith_attach_pack_textures(sessionId, expectedRevision, packId, assetIds?)` | 复用指定 PNG；省略 assetIds 表示全部 PNG，不复制模块或生成绘图任务 |

同内容包的展示名称可能不同；入库保留已存在的名称，并返回 `existingMetadataRetained`。
导出遇到同名但不同内容的文件会保留旧文件并报告冲突，请另选文件名。

## 无游戏命令行

Java 25 开发环境下，Gradle 的 `--args` 直接传给同一个交换服务：

```powershell
./gradlew.bat :core:resourcePack --args='paths "C:/path/to/worldsmith/packs"'
./gradlew.bat :core:resourcePack --args='list "C:/path/to/worldsmith/packs"'
./gradlew.bat :core:resourcePack --args='inspect "C:/path/to/eclipse-crown.wspack"'
./gradlew.bat :core:resourcePack --args='import "C:/path/to/worldsmith/packs" eclipse-crown.wspack'
./gradlew.bat :core:resourcePack --args='export "C:/path/to/worldsmith/packs" <bundle-id> eclipse-crown.wspack'
```

`import` 的文件须先位于 `paths` 返回的 inbox；`export` 写到返回的 exports。
CLI、MCP 与游戏界面共用归档与保存实现，没有三套互不兼容的格式。

## 校验与兼容边界

容器最多 4096 个文件，压缩文件及总展开量各不超过 384 MiB；另执行现有 JSON、PNG、
冻结绘图的单文件和分类总预算。导入检查 ZIP 目录与实际成员、CRC、路径、内容摘要、
PNG 和冻结绘图、模块字段及引用；缺件、额外文件、重复名、路径穿越和异常归档会被拒绝。
v1 不使用目录成员、ZIP64、分卷或加密。请优先使用 Worldsmith 导出器生成文件。

先校验，后发布完整内容目录。入库不覆盖损坏或不相符的既有文件，不执行来源代码，
不改变正在运行的世界。世界内容格式、绘图目标数据版本与原生运行时是独立边界：
Core 导入检查通过，仍须由目标 Minecraft/Worldsmith 完成原生验证和激活。

## 选择、图标与加载

创建选择绑定到一次创建页面请求；后台发布不覆盖玩家正在选择的世界。确认创建与进入新存档均核对目标包身份。相同scope、资源哈希和已加载标记一致时复用资源，列表浏览/图标预览/纯内容校验不激活世界。

新包可通过manifest的representativeContent选择本包item/block为图标；旧包优先使用最终主线遗物等已有素材，不重写包。入场主线提示默认开启，可在客户端体验设置关闭。
