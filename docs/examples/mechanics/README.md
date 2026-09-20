# 三种交互，一个规则内核

[`mechanics.json`](mechanics.json) 是可直接作为 `modules.mechanics` 提交的完整类型化文档，
不是脚本，也不是完整世界包。[`authoring.json`](authoring.json) 给出匹配的 WorldBible
与三份 owning brief；它只演示交互设定部分，不宣称满足整个 COMPLETE_WORLD 的内容覆盖。

| 定义 | 玩家动作 | 真实规则结果 |
| --- | --- | --- |
| `sentinel_assembly` | 最后摆上底座、躯干、任一手臂或南瓜，补齐构型 | 消耗铁块和南瓜，底座变哭泣黑曜石，生成 `shardbound_sentinel`，进入 `spent` |
| `keyed_gate` | 主手持绊线钩，站立右键磁石 | 消耗一枚钥匙，移除磁石上方两格铁栅，进入 `open` |
| `crystal_exchange` | 主手持至少四枚紫水晶碎片，站立右键氧化铜块 | 消耗四枚碎片，给予一枚绿宝石；20 tick 后可再次交换 |

## 提交

1. 读取 `worldsmith_get_content_contract({"module":"mechanics"})`。
2. 在现有会话中读取最新 revision；完整世界先保存/审核 Bible，提交对应计划和 brief。
3. 将此文件原样放在 `worldsmith_put_content_modules` 的 `modules.mechanics`，附上
   `sessionId` 与 `expectedRevision`。草稿允许暂缺跨模块引用，发布时必须补齐。
4. 召唤例子的 `shardbound_sentinel` 是真实的生物引用：同包需定义该生物与其 PNG。
   可使用相邻 [`complete-world/creatures/shardbound_sentinel.json`](../complete-world/creatures/shardbound_sentinel.json)
   创作配方，经现有生物/贴图工具构建；或显式换成自己的已定义 creature ID。
   其余两个例子只引用原版方块和物品。不要仅修改 displayName 来伪造另一种生物。
5. 审查实际规则的 event、pattern、heldItem、actions、fromState/toState；保存证据后正常
   `write_pack`。格式 10 冻结十二模块；存档中的锚点状态由原生运行时保存。

## 构型与验证动作

召唤例子以金块 `(0,0,0)` 为原点，铁块位于 `(0,1,0)` 与 `(-1,1,0)/(1,1,0)`，
雕刻南瓜位于 `(0,2,0)`。`(0,1,1)` 必须为空气，为召唤位置；也为生物留出周边空间。
四种水平旋转均匹配。分别测试“南瓜最后”和“任一铁臂最后”，前者没有专用捷径。
世界生成时已经完整的构型不会自行触发，必须发生玩家实际放置事件。

钥匙门是磁石上方的两格**铁栅封口**，不是伪造原版双格门状态。其坐标和规则完全可编辑。
钥匙消耗是本例的设计选择；当前 heldItem 是成本字段，不是无成本的钥匙匹配条件。

兑换使用主手现持堆叠，不搜索背包。材料不足、奖励放不下、构型不完整、区块未加载、
动作受保护或生成位置受阻时，不应消费材料或提交状态。副手或蹲下右键、把材料
扔在台面、普通 setBlock/worldgen 均不冒充 USE_BLOCK；真实玩家放置仍独立触发 BLOCK_PLACED。

检查重进世界后门仍是 `open`、召唤台仍是 `spent`；同位置重搭不会重置，另一个位置独立。
本目录是静态 schema/authoring 测试输入，以上是待实际执行的游戏验证步骤，并非已实玩报告。
