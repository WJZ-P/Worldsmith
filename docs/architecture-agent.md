# 世界生成 Agent：建筑群编排与照明约束

这层负责世界主题、建筑群成员和发布校验；Draw SDK 继续只负责绘制。
没有跨玩家固定建筑库，也没有预设必须使用中世纪或中式风格。

## 新 MCP 工作流

`begin_world → 模板/风格/地形与生态 → plan_architecture → build_drawing / get_drawing_job / preview_drawing（修正循环） → put_structure（多次） → validate_architecture → write_pack → finish_world`

工具全名带 `worldsmith_` 前缀。`worldsmith_get_contract` 的 `architecture`
返回完整可执行字段规范；begin_world 同时返回该 MD 内容与机器可读 policy。

硬门槛：

- 至少两套建筑群、一套独立结构；至少一套群为 LANDMARK。
- 群有主体、主题关联、布局意图、与其他群的区别和发现策略。
- 声明必选/可选成员角色及数量；校验每个实际预编译方案的成员数量。
- 每个群至少两栋逻辑建筑。地标每个方案至少三栋逻辑建筑、8192 非空气单元，
  且水平跨度至少 64 或高度至少 32；这只是规模下限，不等于审美验收。
- 可选端口的 chance 决定是否连接，池内权重决定连接哪个成员；必选端口不参与概率跳过。
- 所有根蓝图和子蓝图声明照明。READABLE 室内的步行采样点脚部和头部估计方块光至少 8。

完整文档：`core/src/main/resources/prompts/contract/architecture.system.md`。
结构 JSON 字段：`core/src/main/resources/prompts/contract/structure.system.md`。

## 照明不是摆一个灯就算完成

声明 rooms / indoorPassages，并用 lighting.spaces 覆盖其中的全部可走点；实际放置 sources 指向的发光方块。
两类室内声明总计最多 32 个，存在任意声明时要求 READABLE。
Core 使用有遮挡的六邻接近似光传播，不计天光；原生导出再核对真实方块状态发光等级。
预编译破损保留光源位置，实例材质规则也应保持其发光等级。
EXTERIOR_ONLY 只适用于真正没有室内空间的结构。

这不是完整 MC 光照引擎、碰撞模拟或光影视觉验收。未申报的暗房不属于照明验证覆盖范围。

## 发布与兼容

Architecture 随 structures 索引持久化并参与内容哈希。修改计划保留已有草稿，但会
撤销旧发布结果；write/finish 都会复核，不靠 agent 自报完成。
旧 pack 没有 architecture/lighting 字段仍可读取；新 guided publication 强制满足 policy 1。
会话、方案、源码修订和任务状态原子落盘，可 list_sessions / resume_session。
中断任务标为 INTERRUPTED；源码执行许可不持久化，不自动运行恢复源码。

landmarkGroupCount 是可生成方案数，不是实际出现数量；MCP 明确返回
landmarkInstancesVerified=false。至少一个实例真正落地，仍需未来的运行时验证器确认，
不通过移除地形检查或强加载邻区块来假装保证。

Java SDK 通过独立 worker 接入 MCP：只传源码与参数，返回冻结 drawingId 和模型图片。
结构层引用 drawingId，并在发布前进行原生状态、配对、照明及类型化交互检查。
技术分片最多 32³、每方案最多 128；不占用 16 个逻辑建筑名额，也不独立贴地。
finish_world 仅在原生 NBT 回读、完整数据包重载与当前创建世界上下文激活成功后完成。
缺少上下文返回 WAITING_NATIVE_CONTEXT。详见 [完整流程](structure-agent.md)。
