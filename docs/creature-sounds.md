# 生物声音：选原版事件，再调制

不是音频生成器，也不增加 OGG 文件。一次发声仍是原版声音事件 + 音高 + 音量。
当前提供 **18 种声线、68 个原版事件**，另有 SILENT 明确静音。

## AI 怎样写

先读取 worldsmith_get_content_contract(module="creatures") 或生物创作契约。
响应的 soundVocabulary 给出全部别名与原版事件 ID，soundVoices 给出每种声线的四类默认事件。
使用 CreatureLibrary.schemaVersion=3、CreatureRecipe.schemaVersion=3；世界包仍是格式 6。
其他生物、Boss 阶段、掉落和已绘制贴图照常保留，合并新物种时不要降级整个库版本。

例如霜狼的定义／配方中添加这个字段：

~~~json
"sounds": {
  "voice": "WOLF",
  "pitch": 0.75,
  "volume": 0.65,
  "pitchVariation": 0.06,
  "ambientIntervalTicks": 260,
  "attack": {"sound": "POLAR_BEAR_WARNING", "pitch": 0.8, "volume": 0.9, "pitchVariation": 0.04},
  "death": {"sound": "WOLF_DEATH", "pitch": 0.65, "volume": 0.8, "pitchVariation": 0.03}
}
~~~

可选声线：COW、SHEEP、PIG、CHICKEN、RABBIT、WOLF、FOX、CAT、SPIDER、ZOMBIE、
SKELETON、ENDERMAN、BLAZE、GHAST、SLIME、IRON_GOLEM、RAVAGER、POLAR_BEAR。
例如石质守卫选铁傀儡低音，鸟类选鸡声略升音高，幽魂选低音量恶魂声。
这是借用声音质感，不会继承原版生物的 AI、飞行或远程攻击。

| 参数 | 默认 | 边界 |
|---|---:|---|
| pitch | 1 | 0.5–2，低于 1 更低沉／更慢 |
| volume | 0.7 | 0–2，0 静音；也可能影响传播距离 |
| pitchVariation | 0.08 | 0–0.2，每次服务端发声随机偏移，最终音高仍夹在 0.5–2 |
| ambientIntervalTicks | 200 | 120–2400，原版环境叫声冷却，另有随机延迟，并非精确定时器 |

ambient／hurt／death／attack 为可选独立覆盖。
省略时使用声线事件与全局参数；填写时是完整独立 cue，数值使用表中默认值，**不继承或乘以全局值**。
sound:"SILENT" 或该 cue 的 volume=0 可关闭一种叫声；全局 volume=0 只静音未覆盖的角色。
铁傀儡声线默认没有环境叫声，部分被动声线没有攻击叫声，可显式指定词汇表中的事件。
所有参数需要有限值，未知声音别名和越界参数在内容提交时拒绝，不静默替换。

## 游戏中的触发

- 环境：原版 Mob 环境声音调度，频率有下限，不逐帧播放。
- 受伤：实际受伤声音钩子，沿用原版受伤间隔与环境冷却重置。
- 死亡：服务端首次确认死亡时一次；掉落、Boss 清理和死亡动画仍走原有流程。
- 攻击：通过距离／视线检查的近战击打帧一次，而非整个挥击动画反复播放。
- 仅服务端广播，客户端伤亡动画不重复播放；支持原版 Silent 标记及友好／敌对生物音量分类。

没有修改脚步、音乐、资源包混音、混响或任意动画时间轴；音高调整是原版播放速率变化，并非独立音色合成。
模型／UV 预览保留声音参数，但不是音频试听。

## 旧世界包

旧定义没有 sounds 时，运行时按物种名字关键词、类别、体型匹配默认声线与音高。
这是确定性兼容规则，不是新的 AI 推断。显式设计的 sounds 优先。
无需重生成旧世界：不改世界包文件、资源哈希、已有生物身份或存档。
缺失／身份不匹配的生物定义继续暂停 AI 与发声，不使用其他世界的声音。

Schema 1/2 序列化仍省略 sounds，原哈希保持原字段域；新声音仅在格式 6 的 creature schema 3 中发布。
Java Builder 支持 .sounds(CreatureSoundProfile)，自动选择 schema 3，贴图重建保留声音配置。
