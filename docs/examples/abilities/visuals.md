# 源码表现与投射物控制

`AbilityVisualExample.create()` 是独立、可重复生成的样板。它复用已有 PNG 资产和任意立方体骨架，通过四份 AbilityScript 编排表现，不在原生代码中定义招式名称。

生成入口：`com.wjz.worldsmith.core.examples.AbilityVisualExample`。可选参数是**输出目录**；默认在 `build/ability-runtime/expansion/visual-example/` 写入 `visual-playground.wspack` 和四份带真实资产哈希的 `.ability` 文件。

## 通用接口

| 接口 | 行为与边界 |
| --- | --- |
| `fx.particles(textureAsset, pos, velocity, count, ticks, scale, rgb)` | 当前包 PNG 的哈希；1–64 个真实贴图粒子，1–200 tick，大小 0.05–4；RGB 整数 0–16777215。客户端遵循粒子设置，总活粒子最多 2048，并在已加载区块及施法起点 32 格范围内表现。 |
| `fx.path(points, width, rgb, ticks)` | 2–64 个世界坐标组成任意非零折线，宽 0.01–2；使用真实几何提交，不是固定攻击形状。 |
| `fx.item(logicalItem, pos, rotation, scale, ticks)` | 原版或当前包的规范物品模型；是无碰撞、非拾取、非存档的 Display，不复制玩家物资。 |
| `fx.transform(handle, pos, rotation, scale, interpolationTicks)` | 仅本次调用拥有的视觉实体；旋转为度，缩放 0.05–4，插值 0–59 tick。原版 Display 插值同时驱动路径、粒子局部空间及物品显示。 |
| `fx.remove(handle)` | 退休本次调用的视觉实体，并清理其粒子。重复退休或其他调用的句柄返回 false。 |
| `animation.play(tracks, ticks, blendTicks)` | 返回本次调用的 NUMBER lease；任意模型骨骼的增量关键帧叠加于已有程序姿态。 |
| `animation.stop(handle)` | 仅停止自己创建的动画 lease；较新动画结束后恢复尚有效的旧动画，原时间轴不重启。 |
| `projectile.velocity(handle, velocity)` | 设置自己仍存活的投射物速度，最大每 tick 2 格。 |
| `projectile.steer(handle, desiredVelocity, maxTurnRadians)` | 每次调用最多转向给定弧度（0–π）；目标速度仍受上限约束。目标追踪、循环、加速节奏由源码编排。 |
| `projectile.retire(handle)` | 按资源所有权主动退休；重复调用返回 false。 |

视觉实体使用 ENTITY 句柄；动画使用 NUMBER lease。旧的 `fx.telegraph` / `fx.clear` 保持原接口。它们与其他调用资源共享每次调用 8、每个维度 128 的资源上限，以及取消、死亡、卸载和世界时间到期清理。

视觉位置与路径点逐个经过当前调用的世界/维度、已加载区块和起点 24 格范围检查。显示旋转各轴为 ±720 度；粒子初速度长度上限 2 格/tick。路径变换后的每个端点也重新校验范围。这里只提供渲染几何，不把可见线束当作伤害判定。

## 骨骼关键帧

`tracks` 是普通嵌套列表，格式为：

```text
[
  [boneId, [
    [tick, translationVector, rotationDegreesVector, scaleVector],
    ...
  ]],
  ...
]
```

平移使用模型像素单位（16 像素 = 1 格），旋转使用度，缩放为倍率。第一个关键帧位于 tick 0，后续时间严格递增；每段 1–1200 tick，blend 为 0–min(40, duration/2)。每段最多 64 条骨骼轨道、每轨最多 64 帧、总计最多 256 帧。

骨骼 ID 在实际模型中解析。平移、旋转、缩放和层级累计范围都先完整校验；运行时只做不可变轨道采样。骨骼每帧先恢复基础姿态，再叠加本段增量，避免累积漂移。客户端收到带世界 scope、维度、实体 UUID 和递增 revision 的类型化快照；新追踪者也收到当前剩余时间轴。

每轴平移为 ±32 模型像素，每轴旋转为 ±720 度，关键帧缩放为 0.1–4。父子层级累计缩放和位移还须满足 32 格的骨架绘制范围；这与 `fx.transform` 的独立显示对象缩放下限 0.05 不同。动画轨道采用线性插值，进入/离开时采用平滑 blend；超出时间轴后回到已有程序姿态。

## 碰撞上下文

`on projectile_hit` 的 `event_data` 包含 `kind`、`projectile`、`incoming_velocity`、`normal`、`normal_kind`。方块碰撞另有 `block_position`、`block_id`、`block_loaded`、`inside`、`world_border`。

- 方块法线来自真实命中面，`normal_kind = "block_face"`。
- 实体命中没有原版精确面法线，明确标为 `incoming_velocity_approximation`。
- 默认命中仍退休旧投射物。反射、分裂等行为由源码读取上下文后重新 `projectile.emit`；没有隐式切换成新的碰撞生命周期。

## 样板中的四份程序

- `visual_showcase`：资产粒子、任意路径、原生物品 Display、插值变换、两段不同骨骼动画和独立清理。
- `visual_intruder`：故意尝试操作另一调用的句柄，验证所有权拒绝。
- `projectile_control_demo`：真实投射物速度和转向，记录实际撞墙法线、材质和入射速度。
- `projectile_retire_demo`：主动退休与重复退休。

## 验证入口

```powershell
.\gradlew.bat -PmechanicsGameTest -PabilityRuntime -PabilityVisualTest runGameTest --console=plain
.\gradlew.bat -PmechanicsGameTest -PabilityVisualClientTest runClientGameTest --console=plain
```

服务端新增独立 `visuals` 环境；客户端使用隔离世界和真实 PNG atlas、几何及模型管线，输出五张原始截图：基础表现、较新右臂动画与物品变换、旧左臂时间轴恢复、独立清理、全部到期。报告在 `build/ability-runtime/expansion/client-visual-report.txt`；以实际报告和人工看图结果为准。
