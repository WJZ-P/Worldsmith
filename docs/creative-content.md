# 当前世界的创造模式内容页

`Worldsmith: Current World` 是一个正常注册的创造模式分类页。它只枚举已经进入的本地世界、
已经完成客户端资源激活的内容，不读取 MCP 草稿、不展示 128 个预注册宿主槽位，
也不把没有物种定义的通用实体当成可用生物。没有自定义内容时集合为空，分类按原版规则隐藏。

## 当前内容

- **方块**：从当前存档的不可变绑定快照获取实际 BlockItem；显示名称和材质来自该世界的资源包。
- **生物**：每个已定义物种有一件专用的创造模式召唤物品。它们共用一个原生 Item，
  用有界自定义 DataComponent 保存 `bundle`（64 位 SHA-256）和 `species`（领域定义允许的 96 字符逻辑 ID）。
- **未来物品**：`WorldsmithCreativeContent.Kind.ITEM` 和 `registerProvider(id, Provider)` 预留扩展入口。
  这里没有新增独立的 items 模块、物品属性 DSL、任务系统或奖励系统。

生物召唤物品不是原版 SpawnEggItem。右键方块后，服务端检查玩家创造模式、该 ServerLevel 的真实绑定、
bundle 与 species、交互位置和生物实际身体碰撞，再创建并初始化指定物种。旧世界的召唤物品、
缺少定义的物品和生存模式使用都会明确反馈而不生成实体。物品不接收客户端实体 NBT，
也不修改刷怪笼、创建幼体或调用自然生成的随机物种选择。和平难度仍遵守敌对宿主的原生限制。

当前方块项仍是既有的原生 BlockItem 和存档绑定，未添加独立、跨存档可移植的物品身份系统。
独立 item 模块及跨世界携带普通物品的规则应在后续领域设计中处理。

## 刷新与扩展

客户端在游戏 tick 和创造模式缓存检查前同步已加入世界的快照。世界离开、切换或定义失配会清空目录。
窄范围 mixin 仅在目录 revision 变化时使 `CreativeModeTabs` 缓存失效，随后由原版重建分类和搜索集合；
不会每帧重建界面，也不在转换期间暴露旧世界内容。

扩展 provider 只产生 `Entry(id, kind, stack)`，不自行注册新的分类页。目录将 stack 复制为单件、
检查逻辑项冲突、去重，并限制总条目数；内部未绑定方块槽位和无效的物种召唤项被拒绝。
provider 可使用 `Context` 中的 scope、只读 block resolver 和冻结 creature snapshot。未来物品模块可在启动注册阶段接入：

```java
WorldsmithCreativeContent.registerProvider("my_items", context -> List.of(
    new WorldsmithCreativeContent.Entry("my_item", WorldsmithCreativeContent.Kind.ITEM,
        new ItemStack(MY_REGISTERED_ITEM))
));
```

示例的 `MY_REGISTERED_ITEM` 由未来物品模块注册和管理；provider 应按 `context.scope()` 过滤该世界的物品。
