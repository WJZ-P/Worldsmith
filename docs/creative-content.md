# 当前世界的创造模式内容页

创造模式保留一个正常注册的动态分类，标题使用已激活世界包的名称，例如 **奇幻世界**，
不跟随存档改名，也不为每个世界新增永久注册项。它只枚举已经进入的本地世界、
已完成资源激活的内容，不读取 MCP 草稿，也不展示没有定义的宿主槽位。空分类按原版规则隐藏。

## 三类内容

- **方块**：已激活绑定中的真实 BlockItem；名称与材质来自该世界的资源包。
- **生物**：每个物种有一个创造模式专用召唤器。其有界 DataComponent 保存 bundle/species，
  服务端检查创造权限、所属世界、真实物种、交互位置和身体碰撞后再生成。
- **普通物品**：items 模块的资源／遗物由内置 `Kind.ITEM` provider 自动列出。每个堆栈带
  bundle/item 身份及完整名称、模型、稀有度、说明和堆叠限制；这里已经有实际 items 领域，
  不再只是预留接口。工具、装备、食物动作与完整物品行为 DSL 仍未实现。

召唤器不是原版 SpawnEggItem，不接收客户端实体 NBT，不修改刷怪笼、不随机选择另一物种。
普通物品也不会因为世界切换而套用同名新定义。自定义方块项仍采用其原生 BlockItem／存档槽位，
不能把这种方块表示方式误当成普通 items 的跨世界身份系统。

## 刷新与扩展

客户端 tick 和创造模式缓存检查前会核对资源、方块、生物与普通物品快照的 scope；
离开、切换或失配时清空目录与标题。标题变化也更新目录 revision 并使原版缓存失效，
不每帧重建界面。其它原版及模组分类的标题保持不变。

`WorldsmithCreativeContent.registerProvider(id, Provider)` 仍允许后续内容领域加入同一分类。
Provider 接收 `Context` 的 scope、block resolver、creature snapshot 和 items snapshot，
返回 `Entry(id, kind, stack)`；目录复制物品、检查冲突与总量，并拒绝未绑定/非当前世界宿主。
当前 ordinary-items provider 已存在，外部 provider 不应重复枚举同一套 item ID。

奖励链路与当前字段详见 [物品与奖励](items-and-rewards.md)。
