# v0.3 M1 技术设计：CPU 虚拟结算模式（无限级装配处理器核心）

> 2026-09-04 · 基于对 CraftingCpuLogicMixin(1014行) 与 AE2 执行链的现状分析
> 配套：docs/v03-virtual-crafting-multiblock-design.md（整体方案）

## 1. AE2 合成树执行模型（现状梳理）

- 任务树节点 = pattern 的一次执行需求（量 = 任务值 taskValue）
- executeCrafting 每 tick：遍历任务 → 找 provider → extractPatternInputs（从 crafting
  storage 提取 pattern 输入）→ provider.pushPattern(inputs) → 成功则任务值递减
- crafting pattern 的产物：装配器真实合成 → 产物回网络/crafting storage →
  watcher 通知 → 上层节点 extract 中间产物继续
- **我们 mixin 已接管**：extractBatch（N× 提取 + ScaledPattern + 批量翻倍/减半）、
  pushBatch（N× 推送 + currentPushingCluster 归属）、时间片预算、provider/任务迭代限制、
  卡死退避、任务值按 N 递减记账
- 合成样板（AECraftingPattern）现状：**强制 1×**（等产物回来才能推进，无法批量）

## 2. 虚拟结算核心洞察

装配处理器模式 = 中间层产物**不真实装配**。最优雅插入点不是照抄 extendedae 的
manual-waiting 状态机，而是**在提取阶段拦截**：

```
节点 A（由装配处理器声明其 pattern）：
  正常：extract 基层 → push 装配器 → 等 A 回 crafting storage → 任务完成
  虚拟：extract 基层（记账消耗）→ 直接把 A 注入 crafting storage（量=任务值）
        → 任务值清零 → 节点完成 → 上层 extract A 直接命中
```

- 中间层 A 虚拟注入 **crafting storage**（驱动上层继续）✓
- 根产物 X 注入 **网络主存储**（sensei：产物返回网络）——根节点特殊处理
- 不需要改 AE2 节点状态机/watcher——任务值清零后 AE2 自然认为节点完成
  （等价于真实装配完成后 extract 命中，但零等待）

## 3. 关键语义（sensei 定稿）

- 中间产物抵扣：网络已有 A → 上层直接用（AE2 原生 extract 行为已覆盖——crafting
  storage 没有才发起合成；网络有 A 时…待验证：AE2 是否会用网络存量 A？8/29
  「getEmitableItems 隐藏副作用」已碰过此区域——M1 验证点）
- 基层材料：从网络抽取销毁（虚拟结算的「消耗」）；无足够材料时按 AE2 下单语义
  （下单能过 = 计划成立，批次挂起等料沿用 mega order 行为）
- 与巨型订单/lane 配合：extractBatch 的 N× 机制直接复用——虚拟模式没有装配器
  吞吐限制，N× 可直达任务值上限（Long.MAX 级一次性结算）

## 4. 装配处理器（宿主，M3 多方块，M1 先用单方块/接口代理）

- ICraftingProvider：声明样板槽（5×9×200）内全部 crafting pattern
- 虚拟标志：isVirtualSettle()（后续升级卡控制：批次处理速度/线程）
- pushPattern：收下 inputs（真实从 crafting storage 扣走——注意：虚拟模式若在
  提取阶段拦截，pushPattern 甚至不用走——材料销毁由 extract 记账完成）

## 5. 注入点设计（CraftingCpuLogicMixin 扩展）

A. **判定**：pattern 是否由「虚拟结算 provider」声明（同网格查装配处理器样板表，
   仿 ae2addon$hasFeederFor 的 ACTIVE 注册表模式）
B. **中间层虚拟结算**（extractBatch/extractPatternInputs redirect 内，pattern 虚拟时）：
   1. inputs = 正常提取（从 crafting storage 拿基层/中间材料）
   2. 材料「销毁」记账：真实消耗发生在网络侧——见 C
   3. 产物注入：inventory/crafting storage insert 任务值数量的 A；任务值清零
C. **基层材料来源**：crafting storage 没有的基层 → 从网络主存储提取（storageService）
   ——mixin 有 cluster/grid 访问（extractBatch 已有 inventory 参数）
D. **根产物 X**：任务树根节点 → 产物注入网络主存储而非 crafting storage
   （需要识别根节点：任务树深度或 ExecutingCraftingJob 根标记——待侦察）
E. **批量/lane**：虚拟结算按 extractBatch 既有 N× 走（任务值大 → N× 直达全量），
   lane 分批语义由 CPU 调度层（CraftingCompat/BatchedCraftingOrder）已有机制承担

## 6. 与 extendedae_plus manual-waiting 的关系

- extendedae 的路径：ForcedCraftingPlan（提交时强制展开）+ ManualWaiting（CPU 记录
  「已发未回」产物，finishJob 时容忍缺失）——解决「材料已发但产物永不回导致卡死」
- 我们的路径：**根本不发装配器、直接注入产物** → 不需要容忍缺失（没有缺失）
- 更简单、无 CPU 状态机残留风险；若未来做「材料发出即完成、产物不要」的消耗型
  模式（extendedae 场景），再引入 manual-waiting 变体

## 7. 分步实施（M1a → M1c）

- **M1a 侦察验证**：AE2 对「crafting storage 无 A、网络有 A」时上层 extract 的行为；
  根节点识别（ExecutingCraftingJob 结构）；inventory.insert 的可行注入点
  （可用测试代码/临时日志验证）
- **M1b 中间层虚拟结算**：B+C 逻辑落地（单方块代理 provider 声明 pattern），
  合成树（2-3 层）端到端虚拟完成，产物正确驱动上层
- **M1c 根产物注入网络 + 巨型订单**：D+E；Long.MAX 级合成类订单分批结算
- **M2/M3**：材料抵扣细化、多方块成型、升级卡（见整体方案）

## 8. M1a 侦察结论（2026-09-04 · javap 反编译 AE2 15.4.10 mapped jar）

### 8.1 关键 API 契约（已确认）

- **`ICraftingInventory.insert(AEKey, long, Actionable)`**：crafting storage（ListCraftingInventory）
  实现 = `list.add` + 通知 ChangeListener（→ postChange → 上游 waitingFor 匹配、任务树推进）。
  **产物注入通道就是它**。
- **`CraftingCpuLogic.inventory`** 字段 = cluster 的 ListCraftingInventory（crafting storage，
  KeyCounter 真实存储中间产物）——mixins 可 @Accessor 拿。
- **`TaskProgress.value` 只在 pushPattern 成功分支递减**（-1；我们的 pushBatch 补 N-1）。
- push 成功 → 外层把 `expectedOutputs/expectedContainerItems` 插入 `job.waitingFor`（等产物记账）。
- **`reinjectPatternInputs(inventory, inputs)` null 安全**（字节码 ifnull 跳过）——虚拟结算
  可返回 null/空 inputs，push 失败路径不会 NPE。

### 8.2 设计修正：push 阶段 settle（原「提取阶段拦截」废弃）

在已接管的 **pushBatch redirect**（provider.pushPattern 调用点）里拦截，pattern 虚拟时：

```
settle（provider 是装配处理器 且 pattern 虚拟）:
  1. inventory.insert(每个产物, 任务值对应量, MODULATE)   // 产物立即可见 + postChange 驱动上游
  2. expectedOutputs.reset(); expectedContainerItems.reset()  // 防外层 waitingFor 记账污染
  3. 任务值递减到 0（沿用 ae2addon$decrementTaskValue）
  4. return true   // 外层 break provider 循环；无污染记账；扣电（0.01 阈值）无害
```

为什么返回 true 而不是 false：false 会让外层继续 hasNext 尝试**后续真实 provider**，
用同一份 inputs 再 push → 材料重复扣。true 则外层 break。

### 8.3 待实测行为（M1b 最小闭环验证，文字侦察无法回答）

1. **基层材料来源**：CPU 执行时基层材料在 crafting storage 还是网络？extract 不到时
   AE2 是否会自行从网络 pull（决定「销毁」发生在哪一层）
2. **同 tick 注入命中**：settle insert 后同 tick 内上游节点 extract 能否立即命中
   （postChange 同步性）
3. **网络存量中间产物抵扣**：计划期 AE2 是否已用网络存量折算（决定要不要额外处理）
4. **根节点识别**：任务树根（产物应注入网络而非 crafting storage）的判定方式

## 9. 风险与待验证

1. crafting storage 注入的正确 API（CraftingCpuHelper? inventory 类型 ICraftingInventory）
2. 任务值清零 vs AE2 内部 waitingFor 记账（pushBatch 成功后 AE2 会减 1——虚拟模式
   需对齐记账避免卡死/负数）
3. 网络存量中间产物的抵扣行为（AE2 原生 vs 需 mixin）
4. 产物注入时序（同 tick 上层 extract 能否立即命中——同 tick 缓存/快照问题）
5. ScaledPattern 对虚拟注入的适配（N× 产物注入量）

## 10. M1 实施结果（2026-09-04 v28 验证通过）

- **M1a**：字节码侦察完成（详见 §8）——四个待实测行为点全部验证：
  1. 基层材料：提交时 tryExtractInitialItems 从网络预提进 crafting storage（销毁=extract 扣减）✓
  2. 同 tick 注入命中：pendingSettle flush 于任务循环 hasNext（记账后），insert 立即驱动上层 ✓
  3. 网络存量抵扣：AE2 计划期已处理（下单语义），执行期无需额外处理 ✓
  4. 根节点识别：反射 job.finalOutput（字段名候选+GenericStack 类型兜底）✓
- **M1b**：settle 闭环（commit 26aca2b/756508d）——产物交割两段式：物理入网 + 账务 insert
- **M1c**：N× 批量结算（commit c82c5e9）——extractBatch 虚拟模式放开 1× 限制，
  ScaledPattern 一次提取全部任务材料，settle 按 N 注入，任务值一次归零
- **现状形态**：config 全局开关（virtualSettleCraftingPatterns）+ 集成 CPU 全部合成类 pattern
  虚拟化——调试/验证形态。正式形态（M3）：装配处理器宿主声明样板槽，按 pattern 归属判定
