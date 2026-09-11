# 地面近战 Boss 基座

Boss 使用 `creatures.schemaVersion = 2`。`CreatureDefinition.boss` 是显式声明，不由生命值、名字或模型大小推断。
旧 schema 1 的无 Boss 文档仍省略该可选字段；旧格式 3/4 继续走各自的兼容限制，不把新字段混入旧哈希。

```json
"boss": {
  "barTitle": "黯星守门者",
  "barColor": "PURPLE",
  "naturalSpawnChance": 0.01,
  "naturalSpacingBlocks": 128,
  "phases": [
    {"name":"守门","healthThreshold":1.0,"speedMultiplier":1.0,"damageMultiplier":1.0,"windupTicks":20,"recoveryTicks":30,"poseIntensity":1.0},
    {"name":"裂星","healthThreshold":0.6,"speedMultiplier":1.15,"damageMultiplier":1.2,"windupTicks":16,"recoveryTicks":24,"poseIntensity":1.25},
    {"name":"黯星暴怒","healthThreshold":0.25,"speedMultiplier":1.3,"damageMultiplier":1.45,"windupTicks":12,"recoveryTicks":20,"poseIntensity":1.5}
  ]
}
```

## 实际运行机制

- 限定 HOSTILE、2..3 个严格递减血量阈值。第一阈值为 1.0，后续阈值大于 0。
- 每个相邻阶段至少改变速度、攻击伤害、前摇或恢复时间之一；仅换阶段名字和摆姿势不算有效战斗阶段。
- 服务端依据真实血量选择阶段，治疗不会倒退阶段；阶段索引保存进实体 NBT 并通过 SynchedEntityData 同步。
- 阶段切换重置正在进行的攻击判断，重新进入新阶段前摇；实际伤害帧仍检查视线、距离和有效目标。
- 原生 `ServerBossEvent` 向正在追踪该实体的玩家显示名称、阶段、颜色与当前血量；死亡、离开追踪、卸载或移除时清理。
- 自然生成的 Boss 保留原版怪物容量计数，但禁止距离/闲置自然消失。和平难度仍按敌对宿主的原生规则处理。

基础生命上限为原生 `max_health` 的 1024；派生速度保持 0.01..1.0、派生攻击伤害不超过 100。
攻击仍是有前摇的地面近战，不包含任意脚本、飞行、多部位破坏或通用技能解释器。

发布准备会以当前游戏的真实 `Attribute.sanitizeValue` 核对所有生物的生命、速度、跟随距离、攻击力、击退抗性，
以及每个 Boss 阶段的派生速度/攻击力。任何原生裁剪均给出字段、请求值和实际原生范围错误，绝不静默改值。
旧 schema 1 的 Core 读取/哈希合约仍保留生命 2048；其中超过目标游戏原生上限的定义在原生发布阶段明确失败。

## 生成与预览

有自然分布时使用已定义的 `spawn.biomes`、权重 1..3、单只成组；选中物种后还有独立的
`naturalSpawnChance`（0.001..0.05）概率，即使该群系只有这一种自定义怪物，也不会每次尝试都生成 Boss。
附近已加载、存活的同物种 Boss 会在指定距离内抑制新生成。

**这是可重复的稀有自然遭遇，不是全世界唯一 Boss，也不保证出现在某一座城堡中。**
当前还有世界绑定的创造模式物种召唤器；它直接初始化所选物种，不受自然稀有概率限制。

## 固定地标遭遇

格式 5、结构库 schema 2 可在真实 `minecraft:spawner` 上声明 typed interaction：

```json
{"kind":"boss_spawner","at":{"x":0,"y":3,"z":-6},"creatureId":"darkstar_gatekeeper","respawnTicks":2400,"requiredPlayerRange":16,"spawnRange":4}
```

Java 作者接口为 `a.bossSpawner(at, creatureId)` 或 `a.bossSpawner(at, creatureId, respawnTicks, requiredPlayerRange, spawnRange)`。
它同步写入原生刷怪笼方块与 typed metadata；坐标会随组件实例、绘图归一化和储存分片一起转换。
`creatureId` 必须引用当前不可变世界内、使用生物 schema 2 的 HOSTILE Boss；世界 scope 由导出器填写，作者不提供实体 NBT。
间隔范围 200..30000 tick、玩家距离 8..32 格、水平尝试范围 1..8 格。首次在玩家附近约 20 tick 后尝试，之后使用固定配置间隔；
刷怪器停用、和平模式、未加载、无附近玩家或碰撞均可能阻止遭遇。

刷怪器每次只尝试一只，并使用独立 `encounter_boss` 宿主与精确 class，在刷怪点附近的原生 `spawnRange` AABB 内上限一只。
普通守卫与旧自然 Boss 宿主不占这个 class 限额。**这只是局部限流：Boss 走出附近范围后，后续周期可再生成；不是世界唯一，也不使用自然 Boss 的 128 格间距。**
刷新实体在最终生成坐标确定后才执行一次完整初始化，拥有定义的满生命、首阶段、体型和领地原点；普通存档重载保留既有生命/阶段/原点。
生成前使用完整体型检查实体、液体、实体边界和实心方块碰撞。应为大 Boss 留出实心地板与足够净空，避免只按通用宿主的较小尺寸设计房间。
旧自然/创造宿主保持原有注册 ID 和存档；新地标宿主共享渲染、Boss 血条、战斗与任务击杀计数。

`CreatureBuilder.boss(profile)` 自动产生 schema 2 recipe；手写 recipe 需显式声明 schemaVersion 2。
离线预览使用 `Options.withBossPhase(0..2)` 或 `sheet(definition, png, phase)`，共享原生骨骼姿态计算，
并用所有阶段共同的相机包围范围比较动作强度。离线图只是外观与姿态检查，不是战斗或游戏内验收。
