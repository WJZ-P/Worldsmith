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
| 内容清单、依赖和资源摘要 | `worldsmith.json`，当前世界格式 5 |
| 世界观、地形、群系、地物 | 对应模块 JSON |
| 方块、物品、生物、Boss、任务 | 对应模块 JSON；生物模型骨骼、立方体、UV、行为参数也在定义内 |
| 建筑与组装 | 结构索引、蓝图和 `drawings/<hash>.wsdraw` 冻结几何 |
| PNG 原始字节 | `assets/<sha256>.png`，同一资产只列一份 |
| 作者源码出处 | 既有结构来源记录，作为可读数据保留，不在导入或运行世界时编译执行 |

格式 3/4 的旧世界包可保留原身份进行封装和导入，不自动迁移、不附加新玩法语义。
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

主菜单和“选择世界”页面的“世界资源包”打开资源库，不依赖 Cloth Config：

1. 打开导入目录，把 `.wspack` 放进去，刷新并选择文件。
2. 检查信息后导入；已安装资源包出现在资源库中。
3. 可导出所选包，或单独点击“用于创建世界”。
4. 创建世界仍经过原生编译、资源加载和原版创建流程；导入成功不等于世界已激活。

库目录位于宿主的 `worldsmith/packs`；交换文件位于其同级
`resource-packs/inbox` 与 `resource-packs/exports`。界面直接提供打开目录按钮。

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
