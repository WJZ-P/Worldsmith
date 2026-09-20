# 旧庭盟灯：三份源码协作的玩法样板

本样板在既有 `AbilityRuntimeExample` 的小型探索片段上增加一件「盟灯试印」，
放在原补给箱的第 6 号槽位。它用 **items schema 4 的 use_start 事件绑定**启动程序，
不是新的固定技能类型，也不与旧 USE action 重复收费。

## 实际来源

- [工厂与可导出 CLI](../../../core/src/main/kotlin/com/wjz/worldsmith/core/examples/AbilityGameplayExample.kt)
- [ward_conductor：支付、场景和护盾](../../../core/src/main/resources/worldsmith/abilities/examples/ward_conductor.ability)
- [ward_builder：临时方块与恢复](../../../core/src/main/resources/worldsmith/abilities/examples/ward_builder.ability)
- [ward_witness：共享状态与回声信号](../../../core/src/main/resources/worldsmith/abilities/examples/ward_witness.ability)
- [Core 编译、引用与归档读回测试](../../../core/src/test/kotlin/com/wjz/worldsmith/core/examples/AbilityGameplayExampleTest.kt)

CLI 入口 `com.wjz.worldsmith.core.examples.AbilityGameplayExample [output-directory]`，
默认输出到 `build/ability-runtime/expansion`：

```text
gameplay-trial.wspack
gameplay-trial-bundle/
gameplay-sources/ward_conductor.ability
gameplay-sources/ward_builder.ability
gameplay-sources/ward_witness.ability
```

工厂生成当前格式 10 的独立包，不改之前的样板或用户已有包/存档。
CLI 对归档和目录执行内容身份读回；有不同内容的同名归档会被保留并报冲突。

## 可观察流程

1. 在主手使用试印，确保**自身东侧两格**的脚部高度为空，且未站着其它实体。
2. 主程序声明 `oath_light` 池：上限/初始值 12，每 tick 恢复 0.05；同规格重复声明
   不重置余额。`resource.pay` 原子支付 3 点。
3. `scene.join("oath_workshop", origin)` 选取当前方块锚点。主程序记录角色使用次数，
   初始化本轮场景字段，并用 `program.start` 创建两个独立子程序，传入父 UUID 与位置。
4. builder 创建最多 100 tick 的临时玻璃灯石，写入共同的建成标志；预兆没有伤害。
   80 tick 后主动 `world.restore`。恢复采用实际写入状态比较，不盲目覆盖玩家之后的修改。
5. witness 等待建成标志，间隔 12 tick 记录三次场景节拍，发送场景广播和一次定向完成信号。
6. 主程序收到完成信号后安装 80 tick、最多 3 次有效减伤的通用 guard：伤害倍率 0.5、
   固定吸收 1，以剩余灯息支付实际减伤。`damage_guarded` 记录真实阻止数值并广播。

父程序 idle 不会立即截断还活着的子程序/护盾租期；死亡、取消、卸载及程序预算仍会清理。
示例同时展示 nullable child-start 的检查、失败取消后代和一次退款标志。

## 边界与证据

- 这是可复现的**创作/工程样板**。Core 测试证明源码编译、链接与文件读回；
  `AbilityGameplayGameTests` 还从实际物品触发此三程序组合，检查临时玻璃、护盾减伤事件和最终恢复。
  这些工程验证不代表人类通关、完整世界探索或玩法平衡验收。
- 请在同一锚点一次进行一轮试验：同名同锚点确实共享场景数据，不是每件物品暗中私有的副本。
  原生范围、出生点保护、碰撞、未加载区块和临时编辑排他性仍可能拒绝放置。
- Shared 场景字段是协作记录，不是当前世界事实的认证；每轮启动重置本轮字段。
  硬取消不会补跑用户清理 handler，世界恢复由 owned lease 负责。
- 退款只处理源码明确识别的失败路径；原语不是跨多个 tick、磁盘故障与已发生伤害的统一事务。
- 父加两个子会占用三个真实 invocation，仍受每角色 4 / 全局 64 等限制；并发不够时会拒绝
  child start，而不是绕过预算。新绑定本身无隐含数量/耐久费。
- 素材和探索路线沿用已有样板。本次没有把复用贴图宣称为新美术，也没有创建固定攻击枚举。

完整字段和原生边界参见 [通用玩法原语](../../ability-gameplay.md) 与
[事件绑定](../../ability-events.md)。
