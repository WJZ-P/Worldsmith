# 自定义方块运行时与资源绑定

## 当前能力与边界

自定义方块使用启动时注册的 **128 个完整立方体宿主**，每个物理 profile 有 32 个槽位。
每个宿主注册一个真正的 BlockItem，并预声明 `light=0..15` 方块状态。实际发光值保存在区块状态中，
而非在运行时修改注册表属性或给地形中的每块方块创建 BlockEntity。

| Profile | 硬度 / 爆炸抗性 | 声音与开采 | 外观/物理限制 |
| --- | --- | --- | --- |
| STONE | 2 / 6 | 石材，镐工具标签，正确工具掉落 | 完整立方体，固定遮挡 |
| WOOD | 2 / 3 | 木材，斧工具标签，也可徒手掉落 | 完整立方体，可被熔岩点燃；不是完整的木材交互系统 |
| METAL | 5 / 6 | 金属，镐工具标签，正确工具掉落 | 完整立方体；未附加工具等级要求 |
| GLASS | 0.3 / 0.3 | 玻璃，无指定工具要求 | 透明材质、完整碰撞体、透光；同槽相邻面剔除；当前掉落自身 |

STONE 作为替代地层还加入 `minecraft:base_stone_overworld` 与 `minecraft:stone_ore_replaceables`，
使按石材基底放置的特征和矿脉能在这些方块上生效。METAL 不加入地层标签。
这些标签随世界服务器数据包加载，不是编译时读取活动世界的标签作为作者意图。

目前所有 profile 都是完整立方体：没有楼梯、半砖、朝向、含水、容器、红石逻辑或任意 Java 行为。
贴图为 SHA-256 寻址的 PNG，16..256 像素、正方形、边长为 2 的幂，原生方块资源生成阶段限制每张 1 MiB。
GLASS 使用 Minecraft 26.2 的 `force_translucent` 纹理材质。其他 profile 保持完整遮挡属性；
当前没有按 profile 拒绝带 alpha 的 PNG，因此非玻璃贴图中的透明像素不意味着获得相应的透明物理或面剔除行为。
此阶段建议 STONE、WOOD、METAL 使用完全不透明的像素。

每个宿主具有诊断用备用模型和“未绑定方块槽位”名称。备用贴图使用原版结构方块的 DATA 图案，
只用于避免尚未激活世界时出现缺失模型；未绑定槽位的物品放置仍然被拒绝。
英文备用名称由 `WorldsmithLangProvider` 保留，中文名称与已有设置界面翻译一起存放。

## 绑定身份

便携创作数据使用 `worldsmith:content/<logical-id>`。`worldsmith:content/block/<profile>/<slot>`
属于内部原生宿主身份，不是作者可直接指定的方块类型。别给逻辑别名附加状态属性；发光由领域定义提供。

`CustomBlockBindings.plan(scope, library, previousSnapshot)` 是纯函数。首次按逻辑 ID 排序分配槽位；
提供存档快照后保留既有分配，新定义使用空闲槽位。删去已绑定定义、修改其 profile 或发光值、
超过槽位容量都会明确报错，要求另行实现存档迁移。显示名称、主题说明和贴图可以在维持身份的前提下修改。
所有维度共享一个活动存档快照；同时运行多个独立存档的隔离，以及远程多人资源同步，尚未实现。

## 集成顺序

1. 主 Mod 初始化时调用 `WorldsmithCustomBlocks.initialize()`，仅在 Fabric 正常注册窗口执行。
2. 编译准备调用 `WorldBlockBindings.prepare(String scope, CustomBlockLibrary library, CustomBlockBindingSnapshot previous)`。
   编译器使用返回值的 `resolve(String logicalId): BlockState`，而不是读取活动全局绑定。
3. 用 `GeneratedBlockResources.serverResources(snapshot, library)` 合并服务器数据包；
   用 `GeneratedBlockResources.clientResources(snapshot, library, Map<String, byte[]> assets)` 合并客户端资源。
4. 客户端调用 `WorldContentResources.prepare(String scope, Map<String, byte[]> mergedResources)`，
   随后等待 `Prepared.activate(): CompletableFuture<Void>`。它复制和散列资源，挂载内存资源包，
   重载并检查内容标记。不要在客户端线程上阻塞等待该 future。
5. 前一服务器停止后，调用方块准备对象的
   `commitForNewWorld(CustomBlockBindingSnapshot expectedPreviousSnapshot)`；参数使用该对象的 `previousSnapshot()`。
   它要求预期旧快照和版本仍一致。普通 `commit()` 只接受空运行时或同一 scope。
6. 将 `CustomBlockBindings.encode(snapshot)` 与世界存档一起持久化；重新载入使用
   `CustomBlockBindings.decode(String)` 得到快照，再走准备与激活过程。跨维度复用，不重新排序分配。
7. 失败时先停止依赖新绑定的运行阶段，再调用方块准备对象的 `rollback()` 和客户端资源准备对象的
   `rollback(): CompletableFuture<Void>`，按相反顺序恢复已完成步骤。较新事务已接管时旧回滚会报错，避免覆盖它。
8. 离开世界调用 `WorldBlockBindings.clear(String expectedScope)` 与
   `WorldContentResources.clear(String expectedScope): CompletableFuture<Void>`。后者只移除本模块的资源源和选择 ID，
   不主动改写用户的持久资源包选项；使用原生重载并报告恢复失败。

`WorldContentResources.activeScope()`、`WorldBlockBindings.active()` 可用于生命周期检查。
资源准备对象提供 `scope()`、`contentHash()` 和 `hashes()`；方块准备对象提供 `snapshot()`、`nativeIds()`。
准备和生成资源本身都不激活世界。原生重载完成加内容标记一致也不等于已经完成游戏内美术和玩法验收。

## 无活动世界的编译上下文

`CompiledPack.blockBindings()` 暴露不可变计划，`blockResolver()` 暴露只读 `WorldBlockBindings.Resolver`。
地形、表面、生态特征、建筑、道路、实例替换和最终结构 NBT 都传递这一显式上下文；它不携带激活事务，
不读取全局活动绑定。结构容器固定物品和内联战利品同样可使用逻辑方块 ID，经 `Resolver.resolveItem` 取得真实 BlockItem。
逻辑方块引用省略任意状态覆盖；在尚未加载新数据包时，带自定义 preferredIds 的材料选择器省略 requiredTags。

原生预检的 `StructureNativeHost.forContent(scope, blocks)` 和绘图导出的
`DrawingExportHost.exportContent(drawing, scope, blocks)` 从当前 MCP 会话内容形成独立计划。
冻结绘图的体素和源文件继续保存逻辑 ID；转换只发生在原生 NBT 导出，不把内部槽位写回便携作者数据。
