# 可编程世界交互、协作与战斗资源

这些接口是通用运行时原语，不是新的一组招式枚举。参数顺序、类型与版本以
`worldsmith_get_content_contract(module:"abilities")` 返回的实际 registry 为准。
所有原生调用仍受实例调用次数、世界范围、生命周期和资源配额限制。

## 映射与数据

`map.of([["mana",4],["focus",1]])` 创建不可变映射；重复键报错。
`map.get/put/has/keys` 是 Core 纯函数，`put` 返回新值而非原地修改。
`map.get` 缺键返回 null，`has` 可区分缺键和已存在的 null。
程序初始输入增加 `args`，普通宿主启动时为 null，子程序启动时为显式传值。

## 世界与物品

- `world.set_block(position,blockId,properties,ticks)`：已加载且距 origin 至多 24 格。
  原版属性值用文本映射；逻辑自定义方块仅在声明 HORIZONTAL 时接受四向 `facing`。
  FIXED 材质不接受属性覆盖，light/profile/朝向模式保持定义值，不接受原生保留宿主 ID。
  拒绝方块实体、待加载方块实体 NBT、不可破坏方块、流体、放置重力方块/火/TNT/传送门，
  以及会把实体夹入新碰撞体的修改。玩家遵循 mayBuild/mayInteract；非玩家遵循 mobGriefing
  与专用服务器出生点保护。写入同步客户端，不触发无界的邻居更新链。
- ticks=0 为永久修改，单次 invocation 最多 16 次；返回 0。ticks=1..1200 为临时租期，
  返回恢复 handle，占用一个实例资源。相同位置尚有未处理恢复记录时拒绝新写入。
- 临时修改保存“旧状态、实际写入状态、owner、到期时间”。清理使用 compare-and-set：
  方块已被玩家换成不同状态，就保留玩家修改。未加载区块不强载；恢复碰撞会夹住实体时延后。
  世界重新打开后不恢复程序计数器，而是处理遗留恢复记录。账本最多 4096 条，单 tick
  最多完成 128 条恢复/清除；这不是跨区块磁盘崩溃原子事务。
- `world.restore(handle)` 释放自身方块租期，返回是否找到该租期，不保证未加载位置已当场恢复。
- `entity.spawn(creatureId,position,ticks)` 使用真实自定义生物宿主、身份、尺寸与碰撞检查，
  生命周期 1..1200 ticks。召唤物继承场景；结束时 discard 不触发死亡掉落，正常被杀仍走
  生物掉落规则。所有权标记随实体保存，重新加载的失主召唤物会被清理。
- `entity.despawn` 只移除该 invocation 拥有的实体；不是任意世界实体删除权限。
- `inventory.count/take` 操作自己的 36 个主背包槽；自定义物品核对完整世界身份与规范组件，
  正常耐久/命名/附魔不会丢失逻辑身份。`take` 数量 1..64，余额不足不扣任何一格。
  `inventory.give(player,id,count)` 先检查完整容纳能力，不满一笔就不写入，也不把余量扔到地上。
  给其他玩家仍有目标/保护检查；这些背包操作不是与未来若干 tick 的效果共同回滚的事务。

## 子程序和场景

`program.start(id,args)` 只排队，不同步运行子源码。返回新 invocation UUID 文本或 null；
继承 self、初始 target、origin 和场景。子层级最多 4，原有每角色 4 / 全局 64 实例上限继续生效，
同角色同 program 的并发/冷却规则不绕过。子程序默认 20 tick 恢复冷却。

有活子程序时，已 idle 的父程序继续存活。父取消/死亡/解绑会级联结束后代，
`program.cancel(uuid)` 只接受自身后代，不接管同角色的独立实例；`program.self()` 返回当前 UUID。
`signal.send(uuid,tag,data)` 向相关父子或同场景且相距至多 32 格的实例排队，不会重入 VM。

`scene.join(name,anchor)` 的身份是 **包 + 维度 + 名称 + anchor 方块坐标**；默认场景使用
origin 方块。`scene.anchor()` 返回该方块中心，`scene.emit(tag,data)` 返回接受排队的接收数。
同名但不同锚点不是同一场景，不是无边界的全世界广播。

`shared.actor_get/set` 是独立于 program `state` 的角色共享持久值；`shared.scene_get/set`
为场景共享持久值。null 删除键；拒绝嵌套 ENTITY 句柄。每份最多 64 键/16 KiB，场景最多
2048 份/每维度合计 2 MiB；满额时报错，不自动淘汰可能记录已消费进度的数据。

## 资源池、效果与受击

```text
on start {
    resource.define("mana", 40, 40, 0.05);
    if (!resource.pay(map.of([["mana",4]]))) { return; }
    combat.guard(map.of([["pool","mana"],["multiplier",0.5],
                         ["absorb",1],["maxHits",3],["tag","ward"]]), 80);
}
on damage_guarded {
    state.last_prevented = event_amount;
    // 后续反击在事件队列中执行，不同步等待脚本拦截已完成的命中。
}
```

最多 32 个角色持久池；maximum≤1e6，regeneration≤1000/tick，按世界时间惰性再生。
同 max/regen 的 define 是幂等声明，不重置余额；不一致定义报错。get/give/consume/pay
处理有限非负值；pay 检查所有池后一次提交，任何余额不足均不扣费。

`effect.apply(target,tag,stacks,ticks,modifiers)` 的 stacks=1..16，ticks=1..1200，
同目标同标签活跃堆叠最多 64。支持 movement_speed、attack_damage、armor、knockback_resistance
的有界加法修正，以及 incoming/outgoing 伤害倍率。加法按每层乘 stacks，并考虑既有原生倍率后
检查原生属性范围；伤害倍率按层乘方，单效果结果≤8。effect.stacks 查询，effect.remove
按 owned handle 移除。属性使用原生 transient modifier，不会留下永久装备式增益。

`combat.guard(rule,ticks)` 只保护 self。字段：multiplier 0..1、absorb 0..100、可选 pool、
sourceTag、frontDot -1..1、maxHits 1..128、tag。倍率减伤与固定吸收合计，受本次伤害和
资源余额共同限制；指定 pool 时每阻止 1 点伤害支付 1 点资源。命中计数只在实际减伤时增加。
护盾按注册顺序处理；伤害类型标签不匹配、无源位置的朝向规则不匹配时跳过。

原生 LivingEntity 和 Player 两条路径都在**护甲/魔法减伤之后、吸收生命与实际生命之前**
调用这套处理。原生无敌、PvP 等前置规则不绕过，bypasses_invulnerability 类型不被护盾拦截。
`damage_guarded` 提供真实 prevented amount、攻击源实体、tag 与 remaining 数据。
不是先扣血再回血，因此可以阻止原本致死的伤害。

世界对象、临时编辑、效果、护盾、显示和投射物共享 8/实例、128/维度资源上限。
`fx.clear(handle)` 也可释放本实例的数值租期；专用 world.restore/effect.remove
额外校验租期类型。实例结束统一清理，独立实例的资源不受影响。

## 查询和调试

`entity.velocity/forward` 对不可解析目标返回 null。`world.raycast(start,end)` 的两端均需
在范围内，线段≤32 格且沿途已加载；返回 kind/position/normal/normalExact/entity/blockId。
方块法线来自原生命中面，实体法线为包围盒中心方向近似，近似结果不冒充精确表面法线。

模拟、真实 trace 和需确认安装的原生扩展分别参见打包的
`ability_simulation.system.md` 与 `ability_extensions.system.md`。三种证据不要混淆：
fixture true 不等于游戏实际执行，静态 ABI 检查不等于任意 JVM 代码已隔离或可信。
