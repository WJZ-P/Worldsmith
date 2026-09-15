# 灯火旧庭：可导入的交互探索样板

这是一个刻意缩小的验证关卡：**读刻文 → 从箱子取材 → 看日志构型补阵 → 召唤并击败守卫 → 拾取铜钥 → 开路**。
不是完整随机世界的质量展示，也不是完成了复杂招式的 Boss。贴图为可复现的测试素材。

## 玩家路线

1. 在创建世界的 Worldsmith 资源库中导入 `lantern-vault.wspack`，选择“灯火旧庭 · 交互探索样板”创建**新世界**。
   详细导入入口见[资源包使用说明](../../resource-packs.md)。导入本身不激活世界。
2. 用普通生存模式、非和平难度游玩。寻找原点附近的石砖灯柱庭院，中心为 **X=0、Z=0**；入口是没有横墙的一端。
   这是固定地形锚点，不是强制出生点。此包的气候出生目标覆盖全部气候，让搜索从原点开始；原生落脚点搜索仍会选择周边合适的位置。
   日志给出位置提示，按坐标接近后寻找海晶灯柱。没有传送／给予／触发机关指令作为路线步骤。
3. 读入口告示。按 **J** 打开旅程日志，点击机关目标的说明入口；祭台尚未摆对也能打开。
   “残缺灯台”和“守灯石门”是同一根任务的两个目标，因此入场即可查看两张说明。
4. 打开入口左侧的箱子：取 **1 铁块、1 铁剑、8 面包**。磁石两侧已有西侧铜块；按图在东侧补铁块。
   构型的上下两格必须为空。确认准备好战斗再放置最后一格：这是放置事件，摆完便召唤。
5. 击败出现的**守灯石偶**，拾起其实际掉落的**余烬铜钥**。钥匙不是日志奖励，也不在补给箱中；
   不要把它当提交物品交掉。守卫要求玩家击杀才掉钥匙。
6. 到庭院另一端，主手持钥匙使用**铁门左侧的錾制石砖基座**，不是点铁栏杆。
   一把钥匙打开两格高的通路，随后从通路走进小庭院。
7. 日志两个目标完成后领取馈赠。机关本身是一次性的，重新摆回相同位置不会再次召唤或开门。

遇到没有反应的情况，打开对应说明并手动检查准星指向的锚点；提示区分构型缺格、主手错误、
数量不足、空间被挡、冷却与已经使用。图纸可在任意位置阅读，现场检查才要求距离和已加载区域。
普通生存允许挖掘和绕行，本样板不声称具有防拆门或多人防抢钥匙规则。

## 生成与校验

源码位于 `core/src/main/kotlin/com/wjz/worldsmith/core/examples/MechanicDiscoveryExample.kt`。
没有复制第二份指南 schema：`MechanicGuides.describe` 投影包内的同一份 `WorldMechanicDefinition`，
原生执行也读取它。固定的结构蓝图内含真实 sign/container 交互，不靠正文假装现场存在建筑。

在项目开发环境运行（Java 25）：

```powershell
.\gradlew.bat :core:writeAuthoringClasspath --console=plain
$classpath = (Get-Content build/authoring-runtime-classpath.txt -Raw).Trim()
java -cp $classpath com.wjz.worldsmith.core.examples.MechanicDiscoveryExample build/mechanic-discovery
.\gradlew.bat :core:resourcePack --args='inspect "build/mechanic-discovery/lantern-vault.wspack"' --console=plain
```

输出：

- `build/mechanic-discovery/lantern-vault.wspack`：实际归档；写入后经正常导入读取器检查 ZIP、SHA-256、PNG、模块与引用。
- `build/mechanic-discovery/lantern-vault-bundle/`：同一 bundle 的展开目录，供目标原生编译使用。
- 控制台输出 bundle ID、archive SHA-256 和几何单元数；重复生成相同内容得到相同身份，不覆盖不同归档。

已有隔离构建可使用 `-p build/structure-efficiency-validation` 并把输出参数写成明确的绝对路径。
本例不会扫描、修改玩家的 `run/`、配置、资源库或存档。

## 回归验证的范围

- Core 测试检查：包校验／归档复现／读取回环、图纸与真实规则一致、只有一格缺材、箱子实际供应、
  明确空气与未声明单元的区别、守卫掉落与机关成本引用一致。
- 原生 GameTest 中的选址辅助检查使用例包的实际种子、多群系与噪声生成器，检查气候出生候选位于原点、原点结构起点有效，并走完整原生导出。
- 独立原生 GameTest 环境在另一批次执行，避免不同 fixture 的全局内容绑定重叠。
  它使用例包自己的几何，经过 Minecraft 模板编码／加载／放置后，读取真实告示与箱子；
  通过原版放置事件补阵、真实玩家伤害／死亡／掉落拾取、主手使用开门，并核对玩家持久任务目标。
- 检查准星锚点前后，账本、dirty 标记、物品、方块和实体数保持不变；陈旧世界请求没有现场信息。
- 原生 GameTest 覆盖机制和结构模板，**不等于验证所有种子的自然出生／结构选址，也不等于人工游玩时长或画面质量验收**。
  若要改成发行关卡，还应实际从新世界出生走到庭院，检查指引距离、地形适配和战斗节奏。

运行方式见[原生测试说明](../../../src/gametest/README.md)。
