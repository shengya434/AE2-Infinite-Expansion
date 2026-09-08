# 闭环循环样板（Closed-Loop Pattern）兼容设计方案

日期：2026-09-08 · 依据：ae2lt（AE2-Lightning-Tech 1.21.1 NeoForge）源码调研 +
我们 1.20.1 ae2-addon 现状梳理
性质：**学习 ae2lt 思想，用我们自己的 1.20.1 架构实现**，非抄袭移植

---

## 0. 目标与范围

支持"多成员闭环"：一组处理/合成配方首尾相接（A 产物→B 输入、B 产物→A 输入），
配比非 1:1，环内有内部中间物（种子）需要保留与泵送，净产出持续入网。
参考 ae2lt 闭环：memberPatterns + copiesPerCycle(最小整数比) + seeds + netOutputs。

### 我们现状（单样板自举增殖已通，ae2addon 9/6 完成）
- 判定：`isSelfReferentialPattern`(CraftingCpuLogicMixin 685) 只看单样板产物∩输入
- 计划：`tryModuleSettlePlan`(CraftingServiceMixin 387) selfKeys/seed=1 硬编码
- 执行：强制逐次 N=1 + batchLocked(558/1213) + pendingSettleSelfRef 单布尔回流
  (978/1009) → 本簇 crafting storage 滚雪球(1035)
- 防环：RequirementCalculator.expand(375) path.contains → leafNeeds 当外部叶子
- **缺口**（子代理梳理）：A→B→A 两样板各自不自指→判定盲区；无环级种子求解器；
  任务模型是树（单 job 单 finalOutput 扁平 tasks），无跨样板互喂；种子回流仅限本簇

---

## 1. 概念模型（学 ae2lt，改造成 1.20.1 语汇）

### 1.1 闭环 = 一个"收缩的超级样板"
用户在 GUI 选一组配方样板 + 指定目标产出 key → 系统做**质量平衡求解**：
- 求解每成员每轮的 copiesPerCycle（最小整数比，使环内中间物净流为 0）
- 沿环滚余额，deficit 累计 = 种子量（首轮启动预支）
- 环外消耗 = externalInputs；环外净产 = netOutputs
闭环整体作为一个"宏样板"参与下单/计划；CPU 内展开为成员级任务流水线。

### 1.2 执行：账本级闭环（核心思想）
物理物品只放 CPU 单一库存（inventory/waitingFor）滚动，**不外发网络**；
逻辑份额用**带符号分户账**表达（正=索取权/负=透支自由库存）：
- 派发成员时对消费者 debit 实际种子
- 成员产出种子时 credit 下游消费者（先还债，闭合处回共享池）
- **A 消耗、B 产出后余额自动回到 A 名下**——账本级闭环，无物理回运
（对应我们现有 pushedByCluster 记账思路的推广：从"取消回退账"升级为"环内所有权账"）

### 1.3 与普通合成的衔接
闭环宏样板对 CPU 就是普通 IPatternDetails：inputs=种子×倍率+外部输入，
outputs=netOutputs；优先级高于普通配方（ae2lt 用 dispatchPriority=1000）；
仅限集成 CPU（我们主簇/虚拟 lane）接受，普通 AE2 CPU 拒绝（requiredHost 门禁）。

---

## 2. 分阶段实施（每阶段可独立验证）

### Phase 1：环级分析与求解器（纯算法，先做）
新类 `LoopPatternAnalyzer`（模仿 ae2lt ClosedLoopPatternAnalyzer，自研）：
- 输入：成员 IPatternDetails 列表 + requestedOutput
- primitive cycle 校验（环判定：沿成员输入/输出图检查强连通且每成员被遍历）
- 质量平衡：逐成员 consumed/produced（按 copies 缩放）→ cycleKeys（有进有出）
  → net（产-消）→ 每成员 deficit 滚余额 → seedAmounts；minimal integer ratio 求解
- 输出：ClosedLoopAnalysis(seeds, external, netOutputs, memberFlows)
- 防爆：MAX_MEMBERS 上限 + 时间预算
验证：单元级/命令测试（/ae2addon info 命令扩展调试输出）

### Phase 2：闭环物品与作者工具
- 新物品 `ClosedLoopPatternItem`（编解码成员+种子+倍率，NBT 组件，参考我们
  ConfigCardItem/MemoryCardHelper 模式）——注意 ae2lt 用
  requiredHostKind=OVERLOADED_PATTERN_PROVIDER 门禁，我们改用
  "仅集成 CPU 接受"语义（CraftingCpuRestrictedPattern 等效）
- 编辑器 GUI（从选定成员+目标产出自动求解，预览种子/净产出）
- 校验器（成员可解码、结构可成环、净产出存在、每成员有输入种子）

### Phase 3：CPU 执行（最重）
- 计划：下单闭环宏样板 → 展开成员任务（ExpandedMember + 每轮 copies）
- 任务模型扩展：当前"单 job 扁平 tasks 计数"→ 需支持成员任务链。两条路：
  a) 轻量：闭环展开成多个普通 job 依序提交（串行轮次），种子在簇库存滚动
     —— 复用现有单样板自举滚雪球，但 A/B 交替需调度
  b) 完整：仿 TimeWheelJob 的任务表支持多样板 + 环调度优先级
  建议先 a 验证机制，再演进 b
- 种子账本：推广 pushedByCluster → LoopLedger（消费者 UUID 分户、credit/debit、
  变体债 variantDebts）；物理仍在簇库存不外发
- 回流：pendingSettleSelfRef 单布尔 → 按成员/消费者路由（多 key 回流）
- 取消：复用已加固的两段式思路（soft 阶段等 in-flight 全回）

### Phase 4：GUI 与打磨
- 合成终端看闭环 job 进度（net output 剩余）
- 种子仓库/备货提示（对应我们蓄水池补货语义：闭环种子也可由蓄水池供）
- Jade/工具提示

---

## 3. 风险与边界
- AE2 1.20.1 CPU 任务模型是 DAG/树：互转环本质无 DAG 位，展开为"流水线段"是关键
- 中间产物在簇库存滚动会被原版"任务完成退网"误退 → 需 pendingSettle 同类保护
- 变体/NBT：环内物品若模糊匹配（ID_ONLY）需变体债，Phase 3 先精确匹配
- 巨型订单 × 闭环 = 每批独立 lane：ae2lt 靠 CPU 池/账本解决，我们 lane 间互喂
  是开放问题（唯一汇合点=退网），Phase 3 建议先限制"闭环任务单 lane 串行"

## 4. 借鉴 vs 自研边界（sensei 要求：学习不抄袭）
- 借鉴概念：质量平衡求解、账本级闭环（debit/credit）、净产出入网、优先级抢占、
  requiredHost 门禁、软取消冻结
- 自研实现：全部 1.20.1 代码、AEKey/BigInteger 记账、我们 mixin 体系内挂接、
  我们蓄水池语义整合、中文注释规范

## 5. 参考文件索引
- ae2lt: logic/tianshu/loop/（Analyzer/Validator/Flattener/Payload/Details*）、
  timewheel/（LoopSeedLedgerBook/ExecuteLoopPattern/Ae2LtTimeWheelCraftingCpuLogic）
- 我们: mixin/CraftingCpuLogicMixin.java（selfRef 685/虚拟结算 946-1078）、
  mixin/CraftingServiceMixin.java（tryModuleSettlePlan 387-461）、
  crafting/RequirementCalculator.java（防环 375-382）、block/InfiniteInterfaceBE.java
  （pushedByCluster 记账 80/719/752 已加固 a9adcc4）
