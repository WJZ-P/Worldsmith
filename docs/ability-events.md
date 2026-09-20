# 通用事件绑定：物品输入、装备与被动 NPC

事件绑定把真实原生入口连接到 AbilityScript。绑定只负责启动、投递和终止策略，
蓄力、连招、护符或 NPC 逻辑仍由源码决定，没有新增固定招式类型。

当前包格式为 **10**。物品事件字段使用 `items.schemaVersion=4`，生物事件字段使用
`creatures.schemaVersion=5`；带周期观察间隔的绑定使用 schema 6。本地旧包和存档保持原样，不自动改写。

## 公共 DTO

`CustomItemDefinition.abilityBindings` 与 `CreatureDefinition.abilityBindings`
都是以下记录的列表，默认空，最多 16 条：

```json
{
  "id": "charge_input",
  "program": "charge_source",
  "startOn": ["use_start"],
  "listenTo": ["use_tick", "use_release"],
  "cancelOn": ["use_cancel"],
  "cooldownTicks": 20,
  "range": 8.0
}
```

- `id` 是宿主定义内唯一的绑定身份；`program` 指向本包已编译的能力定义。
- `startOn` 至少一个事件：没有自己的实例时尝试启动，有自己的实例时投递事件。
- `listenTo` 只向这个绑定已经成功启动的实例投递，不凭监听事件偷偷新建实例。
- `cancelOn` 硬取消这个绑定拥有的实例，不再执行该事件的脚本 handler。
- 三个列表互不重叠，各最多 8 个不同名称。当前支持的名称由宿主 hook 表确定，未知名称报错。
- 冷却为 1..72000 tick，沿用能力运行时的启动/结束冷却；range 为有限的 0.5..24 格。
  范围用于生物接近/离开、实体交互过滤；物品使用时按该绑定 range、最多 16 格确定初始目标。
  它不会扩大程序本身的世界查询、伤害或运动边界。
- 生物的 `tick` 订阅可用 `intervalTicks` 指定 1..1200 tick 周期（默认 1）；非默认值要求
  creatures schema 6，物品和其它事件保持 1。原生调度按角色 UUID/绑定 ID 错峰；建议普通
  观察使用 20..40 tick 的短 invocation，并将跨周期意图保存在现有 state/shared 数据中。
- 周期后台启动最多占用 48 个全局实例和同角色 3 个实例，为交互/受伤等前台入口保留
  16 个全局槽位及每角色 1 个槽位；已有实例仍接收到期事件。观察本身不取得运动控制。

启动时先排入 `on start`，再排入本次宿主事件，服务器 END tick 才执行代码。
这不是同步函数调用：handler 内的 wait/操作预算仍按普通协作式执行规则运行。
关键初始化应放在 `on start` 的首次等待之前。

已有 `CreatureDefinition.ability` 仍是敌对生物的主要寻敌/追击策略。事件绑定是其它
入口，PASSIVE 和 HOSTILE 都可用，不需要给和平 NPC 填假攻击目标或套 Boss 类型。

## 原生事件

| 宿主 | 当前支持的事件 |
| --- | --- |
| 物品 | `use_start`, `use_tick`, `use_release`, `use_cancel`, `melee_hit`, `equip`, `unequip`, `interact_entity` |
| 生物 | `spawn`, `tick`, `enter`, `exit`, `interact_entity`, `hurt` |

### 物品持用

物品另有 `maxUseTicks`：0 为即时使用，1..12000 开启原生持用会话。
use_tick/release/cancel 需要正值。流程调用原版 `startUsingItem`、`onUseTick`、
`releaseUsing` 和自然完成路径；没有伪造客户端输入包。

按下时生成 nonce，并绑定世界 scope、逻辑物品 ID、手和已接受的 invocation UUID。
所有自定义物品共享一个原生 Item，因此只检查原版 `isSameItem` 不足以识别换物品。
切换逻辑物品或主手选中栏位会取消旧会话，旧 release/清理也不会接管或取消同名程序的新 UUID。

每 tick 续的是有界观察租期，不是无限程序寿命；maxTicks、操作数、并发数、死亡/卸载
等边界仍有效。正常松开或达到 maxUseTicks 会投递 use_release，留短暂处理窗口后放开
观察租期；已经生成的资源仍按自身生命周期处理。强制停止走 use_cancel；若 cancelOn
声明了该事件，立即硬取消，否则允许一次短暂取消处理窗口后结束持用实例。

**同一输入只能选择一套激活模型：**

- use_* 事件绑定与 consumable 分开；持用模式也与旧 USE action 分开。
- use_start 与任何已有 USE action 冲突，melee_hit 与已有 MELEE_HIT action 冲突。
- equip/unequip 等其它入口仍可与旧 actions 共存。
- 事件绑定不隐含扣物品数量、耐久或第二次原生冷却。已有 ItemAction 自己承担既有费用，
  新程序可用资源/背包原语表达条件成本；后续脚本失败不是已完成效果的回滚事务。

护甲保留普通右键穿戴，潜行右键才使用它的主动程序。装备事件由实际槽位变化触发。
melee_hit 使用原生武器成功命中后的 postHurtEnemy，需要非护甲武器/工具装备，
不是“开始挥手”或伤害前意图回调。

### 装备

`equip`/`unequip` 使用 Fabric EQUIPMENT_CHANGE，包括双手与实际装备槽。
比较的是世界/逻辑定义/槽位，普通磨损、修复、改名或数量 mutation 不被误当成重新装备。

以 equip 启动的程序在仍装备时保留观察租期。程序正常寿命/预算结束后，经过自身冷却，
可以重新启动；新一轮的 equip 原因为 `while_equipped`，不是第二次物理穿戴。
移除时按 unequip 的监听/取消策略退出，短暂处理后清理旧装备实例，不留永久悬挂监听器。

### 生物与实体交互

spawn 是真实生物进入已绑定世界后的首次可运行观察；加载存档不恢复旧 PC。
tick 每个真实服务端生物 tick 发生，enter/exit 按绑定 range 观察存活、非旁观玩家。
需要周期性恢复的自动程序可将 tick 放在 startOn，而不是依赖已经过去的 spawn 边沿重发。

interact_entity 使用真实 UseEntityCallback，并检查旁观模式、存活、同世界和原生交互距离。
若手中物品显式声明该入口，先由物品处理；否则检查目标自定义生物，不在一次点击中重复启动两边。
物品程序的 self 是玩家，目标生物程序的 self 是 NPC；target 是本次交互的另一方。
事件启动本身不让原生漫游或居民日程交出运动与视线控制。需要动作时，源码显式申请
`control.claim(priority,ticks)`，并以 `control.held` / `control.release` 管理 MOVE+LOOK；
纯观察、查询、记忆和声音与日程共存。生物控制不要求 NPC 是敌对生物。普通控制让位对话，
紧急 priority>=80 会先关闭会话再接管；详细边界见 [显式生物控制](abilities.md#显式生物控制与居民日程)。

hurt 是伤害之后的观察，不是本页实现的伤害拦截。已有运行中的程序仍接收通用 hurt；
生物绑定的 hurt 负责按 startOn 启动新实例或按 cancelOn 取消，不重复投递同一击。
原生 AFTER_DAMAGE 不覆盖致死一击；死亡执行正常所有者清理。

## 事件数据

宿主事件沿用 `event_name/entity/position/amount/tag/data`。`event_tag` 为绑定 ID；
`event_amount` 对持用是已持用 tick，对新 hurt 启动是原生回调数值。通用运行时已有的 hurt
不附加绑定 tag，也不要将其数值误认成最终扣除护甲后的生命变化。

`event_data` 是有界 map，提供 `binding_id`, `source_kind`, `source_id`, `slot`,
`nonce`, `reason`。slot 是原生装备槽的小写名称，例如 `mainhand`/`offhand`；
nonce 只在实际使用会话中有值。用 `map.get` 读取，缺少实体时先检查 null。

常见 reason：`press`, `hold`, `release`, `duration_complete`, `source_changed`,
`native_stop`, `equipped`, `while_equipped`, `source_removed`, `interaction`,
`spawned`, `loaded`, `range_enter`, `range_exit`, `after_damage`。

## 创作与验证

通过当前 expectedRevision 的 `worldsmith_put_content_modules` 提交完整宿主定义和 abilities。
绑定纳入 hash、深冻结、跨模块引用及 `invokes_ability` 覆盖检查。CreatureBuilder 提供
`abilityBinding(binding)` / `abilityBindings(list)`，按实际绑定选择配方 schema 5；含非默认周期的 tick 绑定自动选择 schema 6。

Core 的 [AbilityEventBindingsTest](../core/src/test/kotlin/com/wjz/worldsmith/core/content/AbilityEventBindingsTest.kt)
检查字段、冲突、冻结和引用；独立服务器 fixture
[AbilityEventBindingGameTests](../src/gametest/java/com/wjz/worldsmith/gametest/AbilityEventBindingGameTests.java)
验证真实持用、槽位变化、确认命中和 PASSIVE NPC 回调。源码校验与实际游戏测试是不同的证据。
