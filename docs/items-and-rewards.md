# 普通物品与奖励获取链路

本层把世界定义中的普通物品、物种死亡掉落和建筑容器奖励接到同一套物品身份上。
目标是能实际获得的资源／遗物，不是完整合成树、装备、任务或成就系统。

## 物品定义

`items` 是格式 4 的第八个模块。`CustomItemDefinition` 包含 id、displayName、textureAsset、
kind（RESOURCE/RELIC）、maxStackSize（1..64）、rarity、description 和 themeRole。
每个图标使用真实 PNG 的 SHA-256；图标为 16..256、二次幂正方形，最多 1 MiB，支持透明。
分类只描述用途，不让遗物自动获得脚本行为、绑定任务或特殊战斗能力。

MCP 使用现有 `worldsmith_put_content_modules` 提交完整 items 文档，素材上传与其余内容共用
同一个 expectedRevision。`worldsmith_get_content_contract` 的 module=items 返回实际字段与奖励示例。
主题叙事节点可以使用 `ContentKey("item", "local_id")` 引用真实物品。

## 身份、保存与创造模式

逻辑别名 `worldsmith:item/<id>` 与自定义 BlockItem 的 `worldsmith:content/<id>` 分开。
原生普通物品使用一个固定宿主，但每个 ItemStack 保存自己的 bundle/id 身份与名称、图标模型、
稀有度、叠加限制和说明。构造奖励必须复制完整堆栈，不能只保留共同的 Item 类型。
同名物品来自不同世界时不相互解释；丢失／失配的定义不会套用当前世界另一份定义。

物品库和图标随完整世界包嵌入原生数据包。资源与服务端／客户端快照沿既有生命周期一起绑定和清理。
进入世界后，“Worldsmith：当前世界”创造分类自动列出普通物品，不再只有扩展接口。

## 两种获取来源

**生物掉落**：`CreatureDefinition.drops` 支持最多 16 条独立规则，字段为 item、minCount、maxCount、
chance 和 requirePlayerKill。每条成功规则产生一堆，数量应适合真实物品的 stack limit。
服务端使用冻结奖励原型、正常死亡流程与服务端随机数发放；尊重原生生物掉落规则，不依赖客户端状态。
当前不含抢夺附魔倍率、装备规则或任意条件脚本。

**建筑奖励**：既有容器的固定 items 和 inline loot 都接受上述普通物品别名。
固定物品保留完整组件写入容器；随机池把组件写入原生 loot-table JSON，再按已有规则生成数量。
仍然检查容器类型、槽位和实际堆叠限制，也不把外部 lootTable 引用伪装成内置可用表。

这两条链路可以赋予同一种“紫晶核心”不同获取途径，但不会自动推进剧情、解锁技能或消耗该物品。
那些机制仍由未来合成／任务层负责。

## 格式边界

新创建／保存使用格式 4，包含 items；格式 3 只读恢复其原有七模块与原始身份，items 为空且没有新掉落语义。
旧格式 3 的内容哈希不能因为新增默认字段发生变化；带新语义的内容必须重新冻结为格式 4。
格式 1/2 继续不作为读取目标。现有文件不自动迁移、不改写用户存档。
