# C2 方案：退役「批量通道」（侦察报告 + 实施与验收计划）

**状态：仅侦察，未动代码。** 目标版本 2.0.1（或退役后升 2.1.0，待定）。
**执行人约定：这条由爱丽丝自己写，不交 codex**（CPU 派发/结算层，传话容易失真）。

---

## 1. 现状（已取证）

- 主战场：`src/main/java/com/ae2addon/mixin/CraftingCpuLogicMixin.java`（**177 KB**，Mixin 注入点：
  `trySubmitJob` / `storeItems` / `tickCraftingLogic`（HEAD+TAIL）/ `executeCrafting`（5 处 Redirect）/ `cancel`）。
- 批量通道的标识符：`ae2addon$batchNext`（L343，`Map<AEKey, Long>`）、
  `BATCH_MAX_MULTIPLIER`（`config/AE2AddonConfig.java` L46-48，注释明写"**「批量通道」计划退役 —— 本项属于批量通道，
  暂时仍被引用，退役后即失效**"），以及另一条同属批量通道/推送路径的限流（同文件 L54-55 注释）。
- **闸门是共用的（关键）**：`ae2addon$budgetActive`（L228 定义、L984/L989 赋值、L636/L683/L991/L1121/L1149 使用）
  同时管三件事：**批量通道**、**虚拟结算**、**派发预算**。
  L986 注释原文："把 budgetActive 压成 false，批量通道 / 虚拟结算 / 派发预算全部不再工作"。
  ⇒ **退役批量通道绝不能顺手把 `budgetActive` 或另外两套一起删掉**（那会连带砍掉虚拟结算）。
- 入口条件（L386-388 注释）：批量通道原来只对"我们的集成型 CPU"开放（`budgetActive`），
  后来放宽成"**我们的集成 CPU 或 千机样板**都能进"；另有 `qianjiBatch = !budgetActive && isQianJiPattern`
  这条分支（L1185-1186）——**千机样板有自己的批量路径**，与批量通道不是同一条，退役时要分清。
- 已有结论（2026-09-19 注释）：**千机样板改走虚拟结算后不再经过批量通道的提取**（L1243）；
  虚拟结算有自己的批量爬坡状态、与批量通道的 `batchNext` **完全解耦**（L588）。
- `AE2AddonConfig` 的界面是**自记添加**（不会自动同步 config 定义）⇒ 退役配置项时，
  **要同时删掉 `AE2AddonConfigScreen.entries` 里的对应条目**（否则界面里留一条无效项）。

## 2. 「退役」具体指什么（待确认口径）

1. 删除批量通道的运行时代码：`ae2addon$batchNext` 及其爬坡/继承（`carryOverBatchNext`）、
   进通道的那些 `@Redirect` 分支、以及只服务它的诊断计数；
2. 删除两条配置项（`BATCH_MAX_MULTIPLIER` 与同属批量通道的那条限流）+ 界面条目 + 两份 lang 文案；
3. **保留**：`budgetActive` 闸门本身、虚拟结算（含它自己的爬坡）、派发预算、千机样板的 `qianjiBatch` 路径；
4. 行为变化：我们的集成型 CPU 不再有"批量通道"这一层 —— **要确认虚拟结算能否完全覆盖它的作用**
   （这是唯一真正需要读透的地方，见 §4）。

## 3. 风险

- `batch`/`Batch` 在 mixin 里 253 处提及（含 `BatchedCraftingOrder` 巨型订单等**无关**同名概念）；
  删错一处就会碰到巨型订单/结算链 —— **必须逐处判定"这条属于批量通道还是属于巨型订单"**。
- `budgetActive` 共用 ⇒ 误删会静默关掉虚拟结算（症状：合成能跑但慢/不结算，很难归因）。
- reobf：删除方法/字段后要重跑 `check_reobf_consistency.py`。
- 存档兼容：批量通道的经验累积（`sharedBatchExp` 之类）若写在存档/NBT 里，退役后要有"读到旧数据不炸"的处理。

## 4. 动手前必须先读透的三件事

1. `ae2addon$batchNext` 的**全部读写点**（31 处）→ 判定每一处是"批量通道"还是"虚拟结算/巨型订单"；
2. 我们的集成型 CPU 在下单到结算的完整链路上，**哪一步现在依赖批量通道**（若答案是没有，退役就是纯删除）；
3. `carryOverBatchNext()` 与 `sharedBatchExp` 的持久化范围（有没有 NBT 落盘）。

## 5. 验收计划（分三段）

**A. 离线**：`BUILD SUCCESSFUL` 含 `:reobfJar` + `check_reobf_consistency.py` ✅ +
`git diff` 逐处核对（每删一处都要能说出它属于批量通道）。

**B. 实机（sensei，必要）**：
| # | 场景 | 期望 |
|---|---|---|
| 1 | 普通下单（小批量） | 正常完成、速度不退化 |
| 2 | **巨型订单**（溢出拆批） | 与退役前同表现（它走 `BatchedCraftingOrder`，不该受影响） |
| 3 | 自指配方 | 不滚雪球、正常完成 |
| 4 | 有副产的配方 | 副产照给 |
| 5 | 千机样板（虚拟结算） | 正常、爬坡行为不变 |
| 6 | 取消任务 / 断网回退 | 材料退回、不复制 |
| 7 | 日志：`CPU调度诊断` 行 | 成功/失败计数正常（若诊断行本身属于批量通道，退役后应当**减少**，别当成故障） |

**C. 回退**：部署前留旧 jar（`.disabled`），出问题一键回 2.0.1。

## 6. 建议的分阶段顺序

1. **P0**：只删配置项 + 界面条目（零运行时风险，先验证"删配置不影响编译/游戏启动"）；
2. **P1**：把 `batchNext` 的 31 处读写点逐个标注归属（只加注释与日志，不改行为），跑一局确认日志能区分"批量通道 vs 虚拟结算"；
3. **P2**：在没有行为依赖的前提下删掉批量通道分支（一次只删一组，构建 + 实机各验一轮）；
4. **P3**：清理遗留（诊断计数、lang、注释），更新 `md/` 与账本。

**⚠ 上下文提醒（2026-10-02 记录）**：本条开始时主会话上下文已接近上限，故只做到 §1 侦察。
**新会话开工请从 §1 读起，先做 §4 的三件事，再按 §6 的 P0 动第一刀。**

---

# §4 三件事的答案（2026-10-02 第二会话补，已逐处读码取证）

## 4.1 `batchNext` 读写点归属 → **全在"真实 provider 推送"这条路上**

- 写：L1280/L1282（提取失败收敛）、L3021 `setBatchMultiplier`、L3048 `onBatchAccepted` 翻倍、
  L3070/L3072 `onBatchRejected` 减半、L2953（`pendingSeed` 转正）。
- 读：L1357 `n = min(getBatchMultiplier, selfRemaining)` —— **爬坡唯一入口**；L1215（同 tick 缓存作废判据）、
  L1312/L1325/L1442/L1483/L1493/L3054 全是诊断打印。
- **虚拟结算不读它**：L2112-2117 原注释明写「本批份数由这里自己定，**不再借用批量通道的 batchMultiplier**
  —— 那套是为"真实 provider 推送"设计的，绑着它会让"退役批量通道"和"保住性能"互相打架」；
  千机用自有的 `settleScale`（L593 定义、L2116 取用、L1841-1844 翻倍）。
- **但**：L1357 的爬坡是**通用处理样板**（既非合成族、也非 feeder 声明）与**无归属机器的千机样板**的唯一 N 来源；
  L1373-1379 无限接口全量推送、L1431 合成族虚拟结算全量，也都**复用同一套 N× 缩放管道**（`ScaledPattern`）。
  ⇒ 「批量通道」= **爬坡** + **N× 缩放管道**，两者捆在一条代码路径里。

## 4.2 集成型 CPU 现在靠批量通道做什么 → **真实 provider 的缩放派发**

- 走**虚拟结算**的样板：不依赖（已解耦；非千机那支的 N 取 `min(taskRemaining, batchMaxMultiplier)`）。
- 走**真实 provider** 的样板：依赖。删掉批量通道 = 这些样板退回 **1×/tick** 推送（吞吐掉几个数量级）。

## 4.3 持久化 → **没有**（零存档风险）

mixin 内 `writeNBT|readNBT|CompoundTag|saveAdditional` **0 命中**；`batchNext`/`pendingSeed`/`batchLocked`/
`sharedBatchExp` 全是内存态。（`settleScale` 同。）

## 4.4 追加发现（原方案漏掉的）

1. `BATCH_MAX_MULTIPLIER` 被 **4 处**当 N 上限用：L1374（无限接口全量）、L1420（自指增殖）、
   L1431（合成族虚拟结算全量）、L106（`effectiveBatchMax`）；默认 `Long.MAX_VALUE`
   （实机 `ae2addon-common.toml` L16 确认 = 9223372036854775807）⇒ 内联成常量是**行为中性**的，
   但"删配置 = 删功能"是错的。
2. **★ 爬坡/经验层正在被当成加速旋钮用**：实机 `sharedExpCap = 1125899906842624`（2^50，**远非默认 65536**），
   注释写着「**巨型订单提速关键**：拆成 N 批时，每批从该 N 起步就不用每批重新翻倍」。
   且千机虚拟结算成功时**仍调 `onBatchAccepted`**（L1846）→ 它同时写 `batchNext` 与 `sharedBatchExp`
   ⇒ 删掉这批字段，千机跨任务/跨 lane 的起步 N 退化成"每单从 1 爬"，巨型订单变慢。

# §7 修订口径（替代 §2/§6，等 sensei 拍板）

**结论：§2 的「整体退役」不能做。** 断真实 provider 的缩放派发 + 砍掉正在用的提速旋钮。

| 口径 | 内容 | 风险 | 收益 |
|---|---|---|---|
| **A. 安全瘦身（推荐先做）** | ① 删 `BATCH_MAX_MULTIPLIER` 配置项 + 界面条目（4 处内联 `Long.MAX_VALUE`，行为中性）；② 把**无条件**诊断日志收进 `debugLogs` 门控（L1322-1326 `[夹取]`、L1456 `[夹取]末批`、L1479-1485 `[提取]`、L1351 `[提取]已交付足够`、L2952 `[种子]`、L2995 —— 这些现在**不看 debugLogs 也每次都打**，是刷屏与写盘来源）；③ 清死注释/无用计数 | 低（①③ 零行为，② 只影响日志） | 界面少一条无效项、日志干净、代码瘦一圈 |
| **B. 先取证再决定** | 在 `executeCrafting` 提取侧加**分支计数**（feeder 全量 / 合成族虚拟 / 合成族 1× / 爬坡 / 空数组短路），随现有 `CPU调度诊断` 汇总行按 `debugLogs` 输出 | 极低（只加日志） | 用实机数据回答"爬坡分支到底有没有被走"，A 之后是否值得动爬坡层 |
| **C. 原口径（不推荐）** | 连 N× 缩放管道一起删 | 高：真实 provider 退回 1×/tick；且要先造替代机制 | 只有确认实机链路里"真实 provider 推送"完全不用时才可行 |

**执行建议**：A + B 合成一个版本（一次构建部署，sensei 一轮就能同时验"界面少一项 + 日志变干净 + 有分支计数"）。
C 等 B 的数据出来再谈。

---

# §8 执行记录（2026-10-02，口径 A+B 已落地）

**sensei 拍板**：选 A+B。

## 8.1 改动清单（4 个文件）

| 文件 | 改动 |
|---|---|
| `config/AE2AddonConfig.java` | 删 `BATCH_MAX_MULTIPLIER` 规格项 + `batchMaxMultiplier()`；原位留退役说明；订正 `DISPATCH_BUDGET_PER_TICK` 的"退役时一并处理"旧注释为"决定保留" |
| `crafting/CraftingCompat.java` | `batchMaxMultiplier` 由 `volatile long`（读配置）→ `static final long = Long.MAX_VALUE`；`applyConfig()` 同步行删除 |
| `client/gui/AE2AddonConfigScreen.java` | 删界面条目（标签是硬编码，无 lang 键 ⇒ 无需动 lang） |
| `mixin/CraftingCpuLogicMixin.java` | ① 8 处无条件诊断日志加 `debugLogs && logHot()` 门控（含**原先每 tick 一条**的 `[上限]`）；② 新增 9 个 `diagBranch*` 计数 + `CPU分支取证` 汇总行 |

**调用点未动**：`ae2addon$batchMaxMultiplier()` 保留为单点入口（返回常量），
无限接口全量 / 自指增殖 / 合成族虚拟结算全量 / 有效上限 四处一行未改。

## 8.2 分支取证的读法（★ 决定 C 能不能做）

`debugLogs = true` 后，每 600 tick 会多打一行：

```
[ae2addon] CPU分支取证: 爬坡=N1 无限接口全量=N2 合成族虚拟=N3 合成族1×=N4 千机短路=N5 同tick缓存=N6 原版透传=N7 | 实提取 1×=N8 N×=N9
```

- **`爬坡=0`**（且 N2/N3/N5 在涨）⇒ 批量通道的自适应层在实机没被走 ⇒ **口径 C 可以做**（纯删除）。
- **`爬坡>0`** ⇒ 还有通用处理样板在走真实 provider 缩放 ⇒ **C 必须先造替代机制**（否则这些样板退回 1×/tick）。
- `无限接口全量` 涨得多 ⇒ 无限接口这条线是主力，N× 缩放管道**必须保留**。

## 8.3 构建与部署

- `BUILD SUCCESSFUL in 8s` 含 `:reobfJar` ✓；jar 1,620,067 B；
  SHA256 `1C339F1DDDCFF52C351ED46C97278A9610E007C5ABD31A589A82D41F619A0335`；reobf 自检 ✅
- 活跃 jar = 本次构建（SHA 逐位一致 ✓）、活跃数恰 1 ✓、回退仍 2.0.0 ✓；
  归档 `logs\ae2-addon-2.0.1-c2ab.jar`（+ `.sha256`）。

## 8.4 实机验收要点（sensei）

1. 配置界面 CPU 分区**少一行** `batchMaxMultiplier — 批量翻倍上限`（其余项位置/数值不变）；
2. 旧 toml 里那行会被 Forge 自动丢掉 —— **预期行为**，不是故障；
3. 默认 `debugLogs=false` 时 `logs/latest.log` 应**明显变干净**（那 8 类行不再出现）；
4. 要看分支数据：`config/ae2addon-common.toml` 里 `debugLogs = true`（热加载生效，无需重启）→
   跑一单（建议还是 1e6）→ 把 `CPU分支取证:` 那几行发我。
