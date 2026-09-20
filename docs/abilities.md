# 通用能力运行时与创作 SDK

这里的能力不是 `SWEEP / SLAM / LINE` 之类的招式目录。底座提供查询、几何、
运动、效果、投射物、事件和预算；AI 在世界包中编写真正的 AbilityScript 源码，
用函数、分支、循环、等待和持久状态决定玩法。增加一种招式不需要修改核心枚举。

## 分层

1. **程序**：`AbilityProgramDefinition(id,name,source,maxTicks,maxOperations,requires)`。
   源码与其它世界内容一起哈希、保存，在 prepare 时编译为不可变指令。
2. **执行器**：纯 Core `AbilityMachine`，无 Minecraft 对象、JVM 动态类加载或外部
   编译器依赖。服务器以操作预算推进，动态循环不阻塞单个 tick。
3. **能力提供者**：版本化 `AbilityCapabilitySpec` 和实际 host 实现。编译器、
   MCP 契约与原生执行使用同一份签名快照；新能力通过注册接入，而不是扩充技能 switch。
4. **宿主**：生物、物品使用、机关已提交激活都调用同一个运行时。启动策略和费用属于宿主，
   技能逻辑属于程序。

区域也是通用几何，不是完整技能。`shape.sphere`、`shape.box`、`shape.union`、
`shape.translate` 返回同一类区域值，预兆与命中查询消费相同几何。额外几何通过
`AbilityRegionProvider` 的 validate/bounds/contains/outline 扩展。

## 实际源码

以下片段依次记录目标位置，之后攻击历史位置；既不是自动追踪命中，也不是固定“三连击类型”。

```text
fn strike(mark) {
    for victim in world.entities(shape.sphere(mark, 2)) {
        if (world.visible(self, victim)) { combat.damage(victim, 6); }
    }
}

on start {
    if (target == null) { return; }
    let movement = 0;
    if (entity.kind(self) == "creature") {
        movement = control.claim(60, 100);
        if (movement == 0) { return; }
    }
    motion.stop(self);
    let marks = [];
    repeat 3 {
        let position = entity.position(target);
        if (position == null) { return; }
        marks = list.append(marks, position);
        fx.telegraph(shape.sphere(position, 2), 30, "amber");
        wait 10;
    }
    wait 20;
    if (movement != 0 && !control.held(movement)) { return; }
    for mark in marks { strike(mark); wait 6; }
    if (movement != 0) { control.release(movement); }
}
```

可以另外写 `on hurt`、`on block_break` 更新 `state.interrupted`，在等待后的分支里
取消后续攻击并施加虚弱；也可以在 `on projectile_hit` 中检查标签后分裂投射物。
这些行为是用户函数和控制流，不需要核心知道“落雷”“蓄力”“分裂弹”是什么。

`target` 是初始实体句柄；实时换目标需显式调用 `entity.target(self)`。
可能消失的实体查询返回 null，传入要求 ENTITY/VECTOR 的函数前先检查。

## 世界包与三个宿主

格式 **10** 必须包含 `abilities` 模块，默认路径 `abilities/abilities.json`：

```json
{
  "schemaVersion": 1,
  "programs": [{
    "id": "echo", "name": "Echo",
    "source": "on start { fx.message(\"The chamber answers.\"); wait 10; }",
    "maxTicks": 1200, "maxOperations": 32768, "requires": {}
  }]
}
```

同一个 `echo` 可由下列绑定调用：

```text
CreatureLibrary.schemaVersion = 4
CreatureDefinition.ability = {program:"echo", range:8, cooldownTicks:20,
                              cancelOnTargetLoss:true}

CustomItemLibrary.schemaVersion = 3
ItemAction = {trigger:"USE", cooldownTicks:40,
              effects:[{kind:"run_program", program:"echo"}]}

MechanicAction = {kind:"run_program", program:"echo"}
```

普通 HOSTILE 生物也能绑定程序。绑定后不同时运行原有的自动近战循环。
Boss 可以保留血条/稀有度等身份，设置 `boss.phases:[]`，任意阶段条件和状态在程序中实现。
原有简单的 2–3 段数值型 Boss 仍是可选风格，不是程序能力的边界。

物品程序是非消耗品 USE 动作的唯一效果，原有 cooldown/consumeCount/durabilityCost
控制激活费用；程序启动预约成功后才收费。每个机关规则最多一个 program 启动；
更复杂的组合放入程序。机关只保证**预约与本次同步事务**的一致性：写世界前预约，
事务失败释放，账本提交后发起；程序未来若干 tick 的效果不属于全局回滚事务。

旧格式直接拒绝，不扫描、迁移或修改用户现有包和存档。新源码属于包内容身份，
修改源码/能力需求/绑定参数都需要冻结新包。

## Core 扩展示例

下面的扩展无需改编译器或 VM。真实游戏扩展应由原生提供者同时登记签名和实现。

```java
var registry = AbilityCapabilities.standard().extend(
    new AbilityCapabilitySpec("fixture.note", 1, List.of(AbilityType.TEXT),
        AbilityType.BOOL, true, "Record a fixture note"));
var definition = new AbilityProgramDefinition("note", "Note", """
    fn record(text) { fixture.note(text); }
    on start { record("first"); wait 2; record("second"); }
    """);
var compiled = AbilityCompiler.compile(definition, registry);
var observed = new ArrayList<String>();
var machine = new AbilityMachine(compiled, (name, args) -> {
    if (!name.equals("fixture.note")) throw new IllegalArgumentException(name);
    observed.add(((AbilityValue.TextValue) args.getFirst()).getValue());
    return AbilityValues.bool(true);
}, Map.of());
machine.tick(0, 128);
machine.tick(1, 128);
machine.tick(2, 128);
// observed = [first, second]
```

`registry.extend(spec, AbilityPureFunction)` 还能注册纯函数。重复名称、未知能力、
签名/版本不符会被明确诊断。注册完成后把同一 registry 传给
`WorldsmithPackValidator.validate(pack, registry)`、
`WorldsmithMcpTools(..., abilityCapabilities=registry)` 和 archive read/write。
这些入口默认使用标准签名，扩展世界由安装了相应提供者的宿主准备和运行。

### 原生提供者示例

以下 Java 属于**已安装的 Mod 扩展**，在 Mod 初始化时、MCP bridge 启动及世界
prepare 之前注册一次。包内 AI 源码只调用这个函数，不会被装载为 JVM 类。

```java
import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.core.ability.AbilityCapabilitySpec;
import com.wjz.worldsmith.core.ability.AbilityType;
import com.wjz.worldsmith.core.ability.AbilityValues;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

// Inside the extension's initialization method:
WorldAbilityRuntime.registerCapability(
    new AbilityCapabilitySpec("test.floor_name", 1, List.of(AbilityType.VECTOR),
        AbilityType.TEXT, false, "Read one native block ID at a floor sample point"),
    (context, args) -> {
        var point = context.point(args.getFirst()); // Validates nearby, loaded world scope.
        var state = context.level().getBlockState(BlockPos.containing(point));
        return AbilityValues.text(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    });
```

之后 AbilityScript 可写 `let floor = test.floor_name(vector.add(origin, vec(0,-1,0)));`，
并在定义的 `requires` 中声明 `{"test.floor_name":1}`。MCP 使用
`WorldAbilityRuntime.capabilities()` 的同一签名快照，原生 prepare 捕获对应提供者。
示例只读一个方块，不加载额外区块；提供者也应优先使用 `Context.point`、`resolve`
等范围检查，并自行限制循环、分配与工作量。**VM 的指令/调用预算不会抢占正在执行的
第三方 JVM 代码**，注册 API 不是任意 Java 的隔离执行器。

## MCP 创作与审查

1. `worldsmith_get_content_contract(module:"abilities")` 返回准确语法与从真实 registry
   生成的 capabilities 列表（函数名、版本、参数类型、返回类型、效果标记与逐函数说明）。
   每条标准 `description` 先列真实参数顺序，再说明空值、边界、宿主限制与返回含义。
2. `worldsmith_put_content_modules` 提交完整 abilities 文档，共享 `expectedRevision`。
   草稿可暂时存在待修链接；发布前必须编译通过且所有宿主引用可解析。
3. WorldBible/ModuleBrief/WorldDesignPlan 使用 `ability/<id>`，实际绑定使用
   `invokes_ability`（item/creature/mechanic → ability）。
4. 审查证据读取 `/modules/abilities/programs/<index>/source` 和绑定字段，检查输入
   空值、时序、预兆/查询区域、打断、资源清理、费用与持久化。文本招式名不算实现证据。
5. 分开记录纯编译/VM 测试、包读回、原生激活和实际游戏验收，任何一层都不代替另一层。

完整字段、标准函数和边界见
[打包的 abilities 契约](../core/src/main/resources/prompts/contract/abilities.system.md)。

### 容易误用的 SDK 细节

- `entity.position` 的 ANY 结果可能是 VECTOR 或 null；先检查再传给向量参数。
  `entity.alive(null)` 为 false，但需要 ENTITY 的函数不接受 null。
- `list.append` 返回新列表，需要重新赋值；`list.get` 是零基索引，越过末尾返回 null，
  负数或小数索引则报错。三角函数单位为弧度，box 参数是半尺寸。
- Core 的几何创建上限为半尺寸 32，原生查询/预兆使用上限为 16，中心距 origin 最多 24。
  创建合法几何值与在当前世界位置合法使用，是两件不同的事。
- stop/face/navigate 只操作 self；自定义生物需要当前 invocation 的显式控制令牌，
  navigate 仅用于自定义生物；玩家 self 的 stop/face 保持自身操作，blink 需要玩家。
  push 是累加冲量，不保证最终速度等于输入向量；关闭 PvP 不阻止 self 状态/推动。
  navigate 先拒绝空或已完成的候选路径，moveTo 成功后把**实际 Path**登记到已有令牌；
  返回 true 只表示接受，不等于已经到达。旧令牌清理只停止仍属于自己的 Path，不清新路径。
- pose/caption 持有未过期的实例生命周期，但不会暂停后面的代码；顺序需要显式 wait。
  pose 面向自定义生物，caption 是 Boss 血条后缀；玩家浮层应使用 message。
  同一 actor 的多个实例按各通道最后有效写入展示；新租期结束/到期后回退到仍有效的旧租期。
  旧实例清理不清除较新的展示；只有最后一个 pose 租期结束才重置 idle。
  同实例重复写入是替换自身租期，不会保存该实例之前每次写入的展示栈。
  单次消息/声音不持有实例生命周期，`fx.clear` 清除本实例的预兆或数值资源租期。

### 显式生物控制与居民日程

`control.claim(priority,ticks)` 为 self 自定义生物申请 MOVE+LOOK，返回本调用拥有的
数值句柄，暂未获准时返回 0。priority 为整数 0..100，ticks 为 1..1200；令牌共享
8/调用、128/世界的资源预算。相同优先级先到者保留，更高优先级抢占；同一 invocation
可续期换新令牌，旧句柄随即失效。被抢占的路线不会自动复活。

`control.held(handle)` 检查当前所有权，`control.release(handle)` 主动释放；到期、
取消、死亡、NoAI、卸载或离开生物活动范围也会清理。丢失令牌不自动终止源码，也不撤销
独立的状态/伤害/视觉效果，因此等待后的攻击分支应再次检查 held。战斗 windup 前显式
claim，而非依赖“有运行中程序就冻结 AI”。纯查询、声音、持久记忆或事件观察不取得控制，
居民日程可继续运行。

普通优先级（<80）让位真实对话；玩家开始交谈会退还其控制令牌。紧急优先级（>=80）
成功接管时先关闭会话，紧急令牌结束前暂缓重新交谈。共享给物品/机关玩家宿主的源码，
像上例一样按 `entity.kind(self)` 申请生物令牌，避免把玩家返回 0 误当成整个能力失败。

## 执行与持久化边界

- 每程序、每 tick、每世界均有预算；超限/类型错误停止实例并输出诊断，不重放已退休的效果。
- 值只有有限数字、文本、布尔、向量、实体句柄、列表/映射和 null，没有任意游戏对象引用。
- `state` 持久保存，局部变量、程序计数器、目标和在飞投射物不在重载后中途续跑。
  保存时的恢复债务/冷却避免重新进入半次攻击；已应用的伤害/状态不是可撤销日志。
- 等待期间事件可以先更新共享 state，再由后续分支决定反应。程序取消后不会再运行用户 handler。
- cue/projectile 属于实例资源，实例结束、卸载、死亡和解绑时清理，避免迟到命中和泄漏。
- 原生提供者限制同世界、生命周期、已加载区块、范围、伤害和运动预算，并保留 PVP/队伍/
  creative/spectator 保护。LOS 是程序显式查询，创作者应写出希望采用的视线规则。

这些是运行时资源和一致性边界，不是玩法招式目录。

## 原生事件入口

世界操作、子程序/场景、共享数据、资源池、效果和护盾的具体边界见
[可编程世界与战斗资源](ability-gameplay.md)。可编程视觉使用自定义纹理粒子、路径、物品显示与
骨骼关键帧，不依赖固定招式目录；对应函数签名同样来自实际能力 registry。

items schema 4 / creatures schema 5 的 `abilityBindings` 将真实持用、装备、命中与
被动 NPC 事件接入同一程序运行时；详见 [通用事件绑定](ability-events.md)。
startOn/listenTo/cancelOn 分离，持用以真实 nonce 和 invocation UUID 关联，不凭程序名
接管新实例。事件绑定无隐含物品费用，同一输入拒绝两套相互遮蔽的激活模型。

## 不变状态与重叠程序

`AbilityMachine.stateRevision()` 从 0 开始，只有成功提交结构不同的 `state` 值才递增。
`snapshotState()` 在同一版本内复用不可变快照；保留的旧快照不会跟随后续写入改变。
原生存储仅在状态版本改变时编码 JSON，冷却/恢复债务仍逐 tick 更新，但复用已验证的
状态载荷。序列化格式与 64 KiB 的角色合计状态限额保持不变；这是用有界缓存减少瞬时分配，
不是缩减所有常驻内存或保证整服 TPS 的承诺。

同一角色的重叠程序按资源归属清理：姿态/字幕采用最新仍有效的写入，结束后回退仍有效的
旧租期；**运动令牌不回退**，旧程序结束不会停止新令牌的 Path。生物战斗绑定按自身
invocation 判断执行状态，独立纯观察程序不阻止追击；独立有效控制令牌仍阻止原生动作
覆盖该路线。NoAI、死亡、离开活动范围和世界解绑仍结束所有相关运行。

投射物的资源期限按世界时间检查。进入已加载但暂停实体模拟的区块时，物理不会被人工推进，
也不会加载新区块；到期仍退休其归属，恢复模拟后的旧投射物不会再发送有效命中回调。
