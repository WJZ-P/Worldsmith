# Structure Authoring Workbench

这次升级保留纯画布、独立 worker 与冻结数据部署，把重复源码维护、几何/语义同步和错误定位移到工程层。
它不承诺自动产生优秀建筑；目标是让 AI 少返工、更早看见问题，并把精力留给构图和细节。

## 模块边界

| 层 | 职责 |
| --- | --- |
| `core.draw` | 几何、画笔、精确变换与不可变快照；现有 `DrawProgram` 保持兼容 |
| `authoring-sdk` | Java 21 的房间、入口、灯具、组件等辅助方法，同时产生几何与语义 |
| `drawhost` / `draw-worker` | 不可变源码修订、编译缓存、隔离执行、结果保存与耗时统计 |
| Structure 服务 | 草稿解析、分阶段预检、诊断预览、原子提交建筑群计划和成员 |
| 原生适配与发布 | 查询真实方块、检查配对和发光，最终完成数据包导出/回读/激活 |

画布仍不认识世界、玩家、群系、随机选址或村庄业务规则。编译产物缓存不等于几何结果缓存：
不同构建请求仍执行 Java，只有同一 `requestId` 的网络重试保持幂等。

## 1. 注册源码项目，按目标构建

```json
{
  "sessionId": "<sessionId>",
  "name": "courts",
  "expectedRevision": 0,
  "changes": {
    "Materials.java": "<公共材质/构件源码>",
    "Pavilion.java": "<StructureProgram 源码>"
  },
  "targets": {
    "pavilion": {
      "entryClass": "Pavilion",
      "files": ["Pavilion.java", "Materials.java"]
    }
  }
}
```

提交给 `worldsmith_put_drawing_source`，获得 `projectId` 和 `revision`。后续修改只传变化文件，
`null` 删除文件，`expectedRevision` 防止覆盖别人的修改；目标的所有依赖必须显式列出。
项目上限 64 文件/4 MiB，每目标仍为 16 文件/1 MiB。

调用 `worldsmith_build_drawing`：

```json
{
  "sessionId": "<sessionId>",
  "name": "main_hall",
  "requestId": "main_hall-r1",
  "sourceRef": {"projectId": "<projectId>", "revision": 1, "target": "pavilion"},
  "parameters": {"kind": "grand"},
  "seeds": [20260906]
}
```

`sourceRef` 与原有 inline `entryClass/sources` 二选一。多个参数实例共享编译产物；修改公共文件只使
引用它的目标失效。缓存键还包含入口、编译选项、SDK/辅助模块/编译器内容和 Java 环境。
旧构建仍保留，但默认发布要求对应目标仍为当前源码内容；使用旧结果需要显式选择。

`worldsmith_get_drawing_source` 可按 `projectId/revision/files` 读取；也支持 `jobId/files` 取回旧式
inline 请求的源码。整个恢复过程只需 MCP，不需要 AI 端访问文件系统。

## 2. 几何与语义一起生成

入口为 `StructureProgram.generate(AuthoringContext)`，返回 `AuthoredStructure`。
完整程序见 [Pavilion.java](examples/authoring-workbench/Pavilion.java)。

常用辅助方法：

- `canvas(bounds)`：创建一栋逻辑建筑的画布；绘图仍使用原有 API。
- `origin(at)`、`material(name,state)`：声明放置基准与结构层使用的材质名。
- `room(id,interior,floor)` / `indoorPassage(...)`：清空整个内部体积、铺设底面，并声明这一层的占用平面。
- `entrance(id,feet,facing,floor,headroom)`：把实体入口连接到画布边界，使用同一组坐标生成地面、AIR 通道及端口。
- `lightFixture(id,at,state,level)`：同时放置灯具和记录发光声明。
- `hangingLightFixture(id,at,anchor)`：真实屋梁锚点必须在灯具正上方，默认生成吊灯和连续竖直铁链；完整重载还接收灯具状态、链状态和亮度。放置前拒绝覆盖障碍，snapshot 再查灯、链和锚点，组件变换后仍生效。
- `intentionallyDark(reason)`：显式声明整栋建筑故意幽暗；理由为 1..512 个可打印字符，仅保留在作者元数据。普通建筑仍默认布置真实灯具。
- `support(at)`、`protect(region)`：记录支撑点与保护区域。
- `component(id,region)`：命名调试区域；`instance(id,child,transform)` 同步变换组件几何和语义标记。
- `container(at,state,items)`：使用类型化物品声明，不接受任意 NBT。

房间辅助方法记录的是这一层可站立的地面，不把书柜顶部自动当作另一层房间；楼上需另外声明。
灯具布局仍由创作者决定。普通建筑的每个楼层、楼梯和可用阁楼都默认布灯，屋顶闲置夹层应填实或打开。
默认预检不运行逐点光传播；显式 `estimateLighting:true` 可查看有界估算，暗区为非阻断警告。
声明边界、源方块及原生发光等级仍受校验；这不是运行时自动补灯或旧存档修复。
辅助方法不改变 KEEP 语义，也不暗中选择建筑风格、群组成员或世界位置。

绘图与语义侧车都冻结保存，在当前格式 5 世界包的结构模块模式 2 中保存绘图引用和语义 JSON。
worker 使用显式数据编码，不使用 Java 对象反序列化。

## 3. 将 authored 结果提交为结构

```json
{
  "sessionId": "<sessionId>",
  "structure": {
    "id": "main_group",
    "blueprint": {
      "id": "main_hall",
      "authored": {"variants": ["<drawingId>"]},
      "portBindings": {"north": {"pool": "halls", "required": true}}
    },
    "placement": {"biomes": ["<本世界的 biome id>"]},
    "assembly": "<按 structure 契约填写实际编排对象>"
  }
}
```

上面的 assembly 字符串仅表示待填位置；[完整调用序列](examples/authoring-workbench/workflow.json)
包含可回放的实际对象。

`authored` 与手写几何/语义字段互斥。`portBindings` 只配置 pool、required、chance；入口位置和方向
来自冻结结果。PILLARS 模式使用 authored 支撑点。多个变体必须共享相同语义声明；布局不同则拆为
不同定义，避免在一个共享蓝图中混入互相矛盾的房间和入口。

`worldsmith_put_architecture_draft` 接受 `sessionId/expectedRevision/architecture/structures/remove`，
将关联的计划和成员修改保存为一个修订。可修复草稿能保存，但不因保存成功而获得发布资格。

## 4. 先预检，再扩展建筑数量

`worldsmith_query_block_states` 按 `ids` 或 `search/limit` 返回当前 MC 的方块字典、合法属性和发光等级。
`ids` 也接受 `block[state=value]`；属性错误时仍返回该方块的允许值。

`worldsmith_preflight_structure` 接受 sessionId，以及 structure、blueprint、drawingId 三选一。
返回 geometry、semantics、native、assembly、deployment 分阶段结果：PASSED / FAILED / NOT_RUN。
执行完成与这些检查分开；缺少建筑群上下文时不伪造组装失败或通过。

诊断包含错误码、阶段、结构/组件、坐标/区域、预期与实际值、修复提示和预算指标。
照明返回暗区、最低值和有界采样；拼装失败返回朝向、重叠、半径、高度、成员/单元预算的拒绝计数。
相同问题跨不同创作修订重复出现时给出提示；查看预览和轮询不会计为新的修复尝试。

native 预检覆盖方块状态、配对和发光。完整物品/方块实体 payload、动态注册表、NBT 回读和数据包激活
仍由最终发布路径完成；预检不替代完整 MC 碰撞/光照模拟。

## 5. 用图片修复，而不是根据一句报错猜坐标

结构预览增加：

- `region`：原绘图坐标中的 BuildBox 裁剪区域。
- `frame`：固定取景框，便于修复前后对照；同一会话/建筑会复用默认取景。
- `components` / `hideComponents`：按声明的组件区域筛选或隐藏，例如屋顶。
- `views`：一次最多四张图，支持 isometric、isometric_back、front、back、left、right、top、slice。
- `renderMode`：material 使用近似材质色；clay 去除材质差异，检查体量。灰模顶视图以明暗表示高度。
- `cutaway:true`：隐藏 sliceY 以上的体素，保留选中的顶视/轴测等视角；与仅显示单层的 slice 区分。
- `overlays`：ports、access、clearance、lighting、errors。

`preview_structure.sliceY` 使用归一化蓝图 Y；`preview_drawing.sliceY` 使用原绘图 Y。
`preview_assembly` 也支持 views/renderMode/frame/region/cutaway，sliceY 使用整体组装后的原坐标。
返回的图片是简化体素模型，标记为穿透式调试叠加，不是实际游戏光照或截图。
组装失败时可返回独立成员图，`layoutPreviewAvailable=false` 明确区分它们和成功组装的布局。

内容质量流程与可用请求见 [建筑设计与视觉迭代](structure-design-quality.md)：先看灰模构图，再看完整立面、
有用途的室内和建筑群；记录可见缺点，改动后用同一 frame/view/mode 对照，而非把“通过校验”当作好看。

## 6. 性能、持久化和兼容

`worldsmith_authoring_stats` 报告排队、编译、执行、冻结、检查、预览、发布请求耗时及缓存命中。
这些是宿主测量，不把请求间隔当作模型思考时间。新任务状态读取保存的统计，不重复解码全部绘图。

- worker 仍单并发，沿用编译 30 秒、绘图 120 秒、堆 1 GiB 的默认值。
- 编译缓存最多 256 项/512 MiB；解析缓存最多 128 MiB，超大项直接使用而不保留。
- 只有可重建缓存按 LRU 淘汰，源码、结果和未完成草稿不静默删除。
- 状态在文件提交后才更新内存；临时文件占用有界重试，持续失败明确报错。
- `worldsmith_archive_session` 归档终态任务和会话，释放活动额度；`resume_session` 只恢复数据。
- 损坏记录单独报告；源码项目旧修订、旧绘图和完整草稿仍可独立读取。

DrawProgram 和 JSON 蓝图创作路线保留；新世界包采用格式 5 的九模块，格式 3/4 仅按原身份读取恢复或重新封装；旧的未发布格式 1/2 需要重新生成。没有扩展结构世界生成范围、采样或写入预算，
没有在区块生成中加入源码执行，也没有新增地标实例保证。自动执行遵循当前宿主配置，默认值不变；
关闭自动执行时才出现会话确认。独立 worker 仍只是故障和资源隔离。

推荐实际创作顺序：**代表性构件 → 主建筑体量 → 屋顶/立面 → 通路 → 室内照明 → 装饰 → 配套变体**。
先让主建筑通过预检，再扩大产出；用工程检查保证正确性，用设计审视判断是否好看。
