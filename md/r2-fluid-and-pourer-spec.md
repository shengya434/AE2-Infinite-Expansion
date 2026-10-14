# 规格：B2 流体映射 + C1 灌入分派抽注册表（目标版本 2.0.1 / 任务代号 R2）

两条互不相干的小活，一起做、一起构建。**都不许改语义**（B2 只新增映射分支，C1 只搬家）。

---

## B2：Productive Bees「方块转换」配方 —— 无物品形态的方块映射成流体

**文件**：`src/main/java/com/ae2addon/compat/ProductiveBeesCompat.java`

**背景**：`BlockConversionRecipe` 的输入是 `stateFrom`、产物是 `stateTo`，两者都是**方块状态**。
水/岩浆/空气这类方块**没有物品形态**，现在的代码在两侧各打一条 `warnOnce` 就丢掉了（输入槽缺失 / 产物缺失）。
sensei 2026-10-02 决定：**能映射成流体的就映射**（水/岩浆有对应流体；空气没有 → 仍排除 + 保留警告日志）。

**做法**：
1. 加一个私有 helper（名字自取，建议 `blockStateToFluidKey(Object state)`）：
   把方块状态解析成**流体 key**（`GenericStack`，用现成的流体构造路径，例如 `AEFluidKey.of(...)` 再转 `GenericStack`）：
   - `minecraft:water` → `minecraft:water` 流体
   - `minecraft:lava` → `minecraft:lava` 流体
   - 其它方块 → `null`
   解析要**先取方块 id**（`stateFrom`/`stateTo` 的字段结构照现有 `describeBlock(...)` 的读法抄，别新发明一套读法）。
2. **输入侧**（现 L347-358）：`itemOptions(input)` → 空则 `itemOptions(stateFrom)` → **仍空则试 `blockStateToFluidKey(stateFrom)`**：
   - 拿到流体 → 把**流体**作为该槽的选项加进去（一个 `List.of(fluidStack)` 的槽，照 L332 流体输入的加槽写法）；
   - 拿不到 → 保持现在的 `warnOnce("pb-block-from-noitem", ...)` 行为，日志文案里补一句"（也不是已知流体）"。
3. **产物侧**（现 L422-433）：`toGeneric(stateTo)` 为 null 时 → **试 `blockStateToFluidKey(stateTo)`**：
   - 拿到流体 → 用流体作为该条配方的产物（照现有流体产物的写法）；
   - 拿不到 → 保持现在的 `warnOnce(...)`（空气等）。
4. **日志**：映射成功也要**打一条 info 日志**（例如 `block_conversion 产物方块无物品形态 → 映射为流体 water`），
   别静默；这样实机日志里能一眼看出哪几条走了流体路径。
5. **不许**改别的配方类型的分支（`bee_produce` / `bee_breeding` / `ItemConversionRecipe` /
   `BeeConversionRecipe` / `BeeSpawningRecipe` …）—— 一个字符都不要动。

---

## C1：把「物质球灌入」的分派抽成小注册表

**文件**：`src/main/java/com/ae2addon/item/MatterBallItem.java`（可新增一个同包小文件放接口/条目，别放 util 外面）

**背景**：`supports(BlockEntity, Direction, AEKey)`（现 L180-187）与 `pour(...)`（现 L190 起）现在是两条**同序**的
if-else 链，共 6 家：**物品 → 流体 → 魔力 → 魔源 → FE → Mekanism 化学物**。
教训（2026-09-28）：每加一家桥模组都要动同一个方法的同一段 —— 抽成注册表，以后加第 7 家只加一个条目。

**做法**：
```java
/** 一种「物质球能灌进的目标类型」：判定能不能灌 + 实际灌入（返回剩余量）。 */
private interface Pourer {
    boolean supports(BlockEntity target, Direction side, AEKey key);
    /** 返回剩余量（已灌入的部分不要了）。 */
    BigInteger pour(BlockEntity target, Direction side, AEKey key, BigInteger remaining);
}

/** 顺序即优先级，与重构前的 if-else 顺序**必须逐一对应**。 */
private static final List<Pourer> POURERS = List.of(
        new ItemPourer(), new FluidPourer(), new ManaPourer(),
        new SourcePourer(), new FluxPourer(), new MekanismPourer());
```
1. 每一家的 `supports` 判定与 `pour` 主体**从原代码原样搬过来**（判定条件用的是现成 compat 类：
   `ManaReceiverAccess.find` / `SourceTileAccess.find` / `AppFluxPowerCompat.canReceiveEnergy` /
   `MekanismGasCompat.supports`），**不改常量、不改 4096 次上限、不改 chunk 取法**。
2. 顶层 `supports` = `POURERS.stream().anyMatch(p -> p.supports(target, side, key))`
   （与原来「6 个条件任一个成立」等价；顺序无关，但要保持 List 顺序以便 `pour` 取第一个匹配）。
3. 顶层 `pour` = 找到**第一个** `supports(...)` 为真的 Pourer → 调它的 `pour`，其余不管；
   一个都不匹配 → 返回 `amount`（剩余全部，等价于原 if-else 全不命中时的行为 —— **先看原文确认**）。
4. **Mekanism 那家**要保留 `MekanismGasCompat.supports` 里对化学物的转发（原 L186 直接 return 它，
   等价于"化学物也归 Mekanism 这一家"）。
5. 不动 `useOn(...)` 的交互流程（消息、`ball.shrink`、`keepRemainder` 全都不许改）。
6. 文件里现在没有的 import 不要乱加；新增文件要带 `package com.ae2addon.item;`。

---

## 共同要求

1. **不要自己构建**（构建由爱丽丝在 `D:\ae2addon-build` 跑）。
2. 静态自检并写进报告：
   - `git diff --stat`；
   - C1：**逐个 Pourer 列出"原代码行号 → 新位置"对照表**，证明每一家的判定与灌入逻辑逐字搬过来；
   - C1：**说明顶层 `pour` 在不匹配时返回什么，以及原代码在这种情况下实际返回什么**（必须一致）；
   - B2：**水/岩浆/空气三条的预期走向**（映射成哪个流体 / 仍排除）+ 你实测过字段读法（`describeBlock` 同源）的证据；
   - B2：确认没有碰其它配方类型（`grep` 或 diff 都行）。
3. 中文报告；不确定的地方**列出来**，不许含糊过去。
