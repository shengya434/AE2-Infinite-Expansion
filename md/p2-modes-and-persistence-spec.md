# P2 规格：三模式 + 阈值/白名单/臻藏 + 持久化（1.12.2 / AE2-UEL）

**状态：可实施（口径冻结）** — 2026-10-02 写。前置：`port-1.12.2-plan.md` §6、`p1-minimal-cell-spec.md`（P1 已验收 11/11）。
**口径来源**：1.20.1 版的 `D:\环境\ae2-addon\src\main\java\com\ae2addon\cell\UnlimitedCellInventory.java`（1,280 行）。
凡本文写「出处 Lxxx」的，都是我从那份代码**逐行读出来**的，不是回忆；实现时**以本文的口径为准，但可从出处抄算法**。

---

## 0. 一页结论

1. 判定层是 **`mode`(1/2/3) × `workMode`(1/2/3)** 的双轨模型；**mode 2 的三种工作方式**才是"阈值 / 存入无限 / 臻藏"。
2. **升级为无限只有两条路**：mode2+workMode2 存进来即无限；mode2+workMode1 累计到阈值才无限。
   升级时把真实数量搬进 **`ca`（承诺额度）** 并加进 **`ul`（已无限集合）**，同时从 `s2` 移除 —— **这三步是一个原子动作，不能只做一半**。
3. `ca` **必须是 `BigInteger`** —— 1.20 曾用 `Long` + `clampToLong`，第一刀就把真实数量截成 9.22e18（出处 L58-63 的注释就是这次事故）。
4. 持久化：元件物品 NBT 只存 `mode/workMode/thr/uuid`，**大数据放 `WorldSavedData`**（键 = uuid）。
5. ⚠ **P2 必须自带一个最小设置入口**（命令），否则模式/阈值/白名单在游戏里没法设，也就没法验收。GUI 是 P3。

---

## 1. 范围

**做**：`mode` 1/2/3、`workMode` 1/2/3、全局阈值 + 单物品阈值、白名单、`ul`/`ca`/`s1`/`s2`、`WorldSavedData` 持久化、最小设置命令、B 组验收断言。
**不做（明确排除，别顺手加）**：

| 排除项 | 为什么 |
|---|---|
| 按 tag/mod 的**规则**（`tags`/`mods`/`ruleModes`/`ruleTouched`） | 那是 1.20 后加的规则体系，P2 不碰 |
| 黑名单（`blacklist`） | 同上；阈值/存入无限的"拦截"逻辑一起押后 |
| 数量锁定（`qtyLocked`） | 依赖面板交互 |
| 拒收列表（`rejectOn`/`rejectOff`） | 属于 R3/R6 的界面特性 → P3 |
| 物质球（打包/解包） | 与"取消无限"的 UI 流程绑定 → P3 一起做；**P2 里 `insertBI` 的 depth 参数直接去掉** |
| 面板 / GUI / 网络包 | P3 |
| 非物品通道（流体/气体/魔力） | P4 |

---

## 2. ★ 判定层口径（逐条，带 1.20 出处行号）

### 2.1 数据字段

| 字段 | 类型 | 语义 | 出处 |
|---|---|---|---|
| `mode` | int 1..3 | 1=无限制存储；2=自定义无限；3=全类型无限 | L48 |
| `workMode` | int 1..3 | 仅 mode2 用：1=阈值；2=存入无限；3=臻藏 | L49 |
| `thr` | long，默认 **65536** | 全局阈值 | L50 |
| `itemThr` | `Map<Key,Long>` | 单物品阈值，**优先于全局** | L52 / L727 |
| `s1` | `Map<Key,BigInteger>` | mode1 的实体存储 | L54 |
| `s2` | `Map<Key,BigInteger>` | mode2/3 的实体存储 | L55 |
| `wl` | `Set<Key>` | 白名单 | L56 |
| `ul` | `Set<Key>` | 已升级为无限的物品 | L57 |
| `ca` | `Map<Key,BigInteger>` | **承诺额度**：升级为无限时记下的真实数量 | L66 |
| `m3` | `Set<Key>` | mode3 见过的物品（用于持久化/展示） | L67 |
| `INFINITE` | long = **`Long.MAX_VALUE`** | 对外报"无限"时的数量（1.20 走 config `infiniteItemAmount`，默认 `Long.MAX_VALUE`） | L40 / config L151 |
| `INFINITE_BYTES` | long = **300_000_000** | 无限物品在面板里显示的字节数（config `cellDisplayBytes`） | L43 / config L144 |

### 2.2 `insert(what, amount, act)` —— 出处 L362-476

按顺序判，**第一条命中就返回**：

| # | 条件 | 行为 | 出处 |
|---|---|---|---|
| 1 | `amount <= 0` | 收 0 | L364 |
| 2 | `act != MODULATE` | 返回"全收"（**不写任何状态**） | L415 |
| 3 | `mode == 3` | `m3.add(what)`，返回全收（**不占内部存储**） | L418-425 |
| 4 | `mode == 2 && workMode == 2` | `ca[what] += amount`；`ul.add(what)`；返回全收 ⇒ **存过一次即无限** | L444-452 |
| 5 | `mode == 2 && workMode == 3 && (wl.contains(what) \|\| ul.contains(what))` | 返回全收（已无限，无需记账） | L453-455 |
| 6 | 以上都不中 | `map = (mode==1 ? s1 : s2)`；`map[what] += amount` | L457-459 |
| 6.1 | 且 `mode == 2 && workMode == 1` | 若 `map[what] >= thresholdFor(what)` → **`ca[what] = 总数`；`ul.add(what)`；`map.remove(what)`**（升级） | L462-471 |
| 7 | — | 返回全收 | L475 |

**要点**：
- 「返回全收」在 1.12.2 上 = `injectItems` **返回 `null`**（P1 已确立的反方向语义）。
- 第 6.1 步的**三件事必须一起做**：少写 `ca` → 取消无限时吐不出东西（1.20 在 L445-446 记过一次同类事故）。
- `ca` 记的是**真实数量**，不是阈值，也不 clamp。

### 2.3 `extract(what, amount, act)` —— 出处 L496-527

| # | 条件 | 返回 |
|---|---|---|
| 1 | `amount <= 0` | 0（1.12.2：**`null`**） |
| 2 | `mode == 3` | `min(amount, INFINITE)` ⇒ 任意物品取之不尽 |
| 3 | `mode == 2 && workMode != 1 && (ul \|\| wl)` | `min(amount, INFINITE)` |
| 4 | `mode == 2 && workMode == 1 && (ul \|\| wl)` | `min(amount, INFINITE)` |
| 5 | 其余 | `extractFromMap(mode==1 ? s1 : s2, ...)` |

`extractFromMap`（出处 L479-494）：`avail = map[what]`；`ext = min(amount, clamp(avail))`；
MODULATE 时扣减、**扣到 0 就删键**；返回 `ext`。取不到（`avail <= 0`）→ 0（1.12.2：**`null`**）。

### 2.4 两条不变量（写进实现的自检）

1. **`ul` 与 `s1`/`s2` 对同一 key 互斥**：进了 `ul` 就不该再留在 `s2`；数量在 `ca` 里。
2. **`ca` ≥ 该物品升级时的真实数量**；`ca` 只能由"升级"和"取消无限"两条路径改写。

---

## 3. 1.12.2 适配（rv6 反方向语义映射）

P1 已确立的映射，**判定层全部沿用**：

| 1.20 写法 | 1.12.2 必须写成 |
|---|---|
| `insert(...) == amount`（全收） | `injectItems(...) == null` |
| `insert(...) == 0`（拒收） | `injectItems(...)` **返回原样 input** |
| `extract(...) > 0`（取到了） | `extractItems(...) != null` |
| `extract(...) == 0`（没取到） | `extractItems(...) == null`（**不是 0**） |
| `act != MODULATE` 时直接返回"全收" | **先算好剩余量再判 MODULATE**（AE2 自己的顺序，见 P1 规格 §3.4） |

⇒ 判定层内部一律用「**算出 accepted（BigInteger）→ 算 remainder → 再决定是否落状态**」的写法，
**不要**把 `Actionable` 判断提到最前面（1.20 的 L415 是那样写的，1.12.2 不能照抄）。

---

## 4. 持久化设计（`WorldSavedData`）

### 4.1 1.12.2 API（javap 实测，别按记忆写）

```
net.minecraft.world.storage.WorldSavedData:
  public WorldSavedData(String mapName)
  public abstract void readFromNBT(NBTTagCompound)
  public abstract NBTTagCompound writeToNBT(NBTTagCompound)
  public void markDirty() / setDirty(boolean) / isDirty()

net.minecraft.world.storage.MapStorage:
  public WorldSavedData getOrLoadData(Class<? extends WorldSavedData>, String)
  public void setData(String, WorldSavedData)

net.minecraft.world.World:  public MapStorage getMapStorage()
```
- 取用：`world.getMapStorage().getOrLoadData(CellWorldData.class, "ae2addon_cells")`；
  为 null 时 `new CellWorldData("ae2addon_cells")` + `setData(...)`。
- ⚠ `getOrLoadData` 内部用反射按 **`(String)` 构造**实例化 ⇒ **构造函数必须 `public`**。
- 只对**服务端世界**操作。客户端没有 `WorldServer`：读不到就**保持空**（1.20 同样如此）。

### 4.2 数据布局

元件物品 NBT（**小**）：
```
"ae2addon:cell": { v:2, mode:1..3, wm:1..3, thr:<long>, uuid:"<uuid>" }
```
`WorldSavedData` 根（键 = uuid 字符串）：
```
"cells": {
  "<uuid>": {
      "s1": [ {k:<AEItemStack NBT>, n:"<BigInteger 十进制>"} ... ],   // mode1 实体
      "s2": [ ... ],                                                  // mode2 实体
      "ca": [ ... ],                                                  // 承诺额度
      "w" : [ <AEItemStack NBT> ... ],                                // 白名单
      "u" : [ <AEItemStack NBT> ... ]                                 // 已无限
  }
}
```
- 数量一律**十进制字符串**（BigInteger 无原生 NBT 类型）。
- key 用 `AEItemStack.writeToNBT` / `fromNBT`（P1 已验证这对是互逆的）。
- **v1 → v2 迁移**：P1 的 `"items"` 数组（`{key,count}`）要能读进 `s1`（P1 的元件已经在实例里存过数了，别让 sensei 的测试元件变空）。
  迁移时把 `v` 写到 2，并在 `WorldSavedData` 里建好对应 uuid 的条目。

### 4.3 ⚠ 四个必须写进文档的危险点

1. **复制物品 = 共享数据**：`uuid` 在物品 NBT 里 ⇒ 创造中键复制 / NBT 复制出来的元件**指向同一份数据**。
   1.20 也是这样。**P2 接受这个行为，但要写进已知问题**；后续可以用"每个槽位/玩家维度检测重复 uuid"来修。
2. **数据是按世界的**：把元件带去另一个存档 → 读不到数据 → **看起来是空的**（数据没丢，回原世界还在）。要在 tooltip 或文档里说清，别让人以为坏了。
3. **P1 的 tooltip 在 P2 会失真**：`UnlimitedCellInventory.appendSummary` 现在直接读物品 NBT 的 `items`；
   P2 数据搬进 `WorldSavedData` 后，客户端读不到 ⇒ tooltip 会显示"空"。
   **P2 必须改 tooltip**：只显示模式/阈值/白名单条数（这些在物品 NBT 里），**不显示数量**。
4. **存档体积**：每个 key 一份 `AEItemStack` NBT；几十万种类会让存档膨胀。1.12.2 上要观察，必要时后续换紧凑编码（P2 先不做）。

---

## 5. 最小设置入口（P2 自带，P3 换 GUI）

没有入口就没法验收。加一条命令（`/ae2addoncell ...`，权限 2，作用于**手持元件**）：

```
/ae2addoncell mode <1|2|3>          设置 mode
/ae2addoncell wm <1|2|3>            设置 workMode
/ae2addoncell thr <long>            设置全局阈值
/ae2addoncell thr <物品> <long>     设置单物品阈值（0 = 清除）
/ae2addoncell wl add|remove <物品>   白名单增删
/ae2addoncell info                  打印当前 mode/wm/thr/白名单数/ul 数/s1·s2 条数
/ae2addoncell cancel                对某物品"取消无限"：ul 移除 + 按 ca 把数量写回 s2
```
`取消无限` 是**必须有的**：它是 `ul`→`s2` 的反向操作，也是 1.20 里踩过坑的地方（出处 L445-446）。

---

## 6. 验收（P2 的"完成"定义）

扩 `/ae2addoncelltest` 的命令，新增 **B 组**断言，全部**绝对量**断言、打印实际值：

| # | 操作 | 断言 |
|---|---|---|
| B1 | mode1：连存 2×`Long.MAX` | 对外存量夹到 `Long.MAX`（不为负）；连续两次各取 `Long.MAX` 后归零（=P1 的 A10） |
| B2 | mode2+wm1，阈值设 1000：存 999 | **不升级**（`ul` 里没有）且取 1000 **用 SIMULATE** 只返回 999、`s2` 仍留 999 ⚠ **必须 SIMULATE** —— 用 MODULATE 会把 999 取光，后面的 B3 就失去前提（2026-10-02 实机就这样误报过一次 FAIL） |
| B3 | 续 B2：再存 1（累计 1000） | **升级**：`ul` 命中、`s2` 里已移除、`ca` == 1000；此后**取 `Long.MAX` 返回 `Long.MAX`**（注意：无限路径返回的是 `min(请求量, INFINITE)`，取 10^7 只会返回 10^7） |
| B4 | mode2+wm2：存 1 个任意物品 | 立刻无限（取 1000 成功） |
| B5 | mode2+wm3：白名单外物品存 5 | 只能取 5（第 6 个取不到）；加入白名单后 → 无限 |
| B6 | 单物品阈值优先：全局 1000、该物品 5，存 6 | 升级（用单物品阈值，不是全局） |
| B7 | mode3：对一个**从未存过**的物品取 1000 | 成功返回 1000 |
| B8 | **三种主模式存储空间完全隔离**（2026-10-03 sensei 重申原始规格）：mode1 存 12345 → 切 mode2+wm1(阈值 10^6) → 该数字**看不到也取不出**（mode2 只认它自己的 `s2`）→ mode2 再存 7 个钻石 → 切回 mode1：12345 原样还在，钻石也不在 mode1 | `12345/7/null/12345/null`（两向隔离，各自切回原样） |
| B9 | **取消无限**：B3 之后执行 `cancel` | `ca` 的数量回到 `s2`；`ul` 移除；`info` 显示数量 = 原真实数量 |
| B10 | 持久化往返：`persist()` → 重新装载 | mode / wm / thr / 白名单 / 数量全部一致；`WorldSavedData` 里出现该 uuid |
| B11 | v1→v2 迁移：把 P1 格式（`v:1`+`items`）的元件装载 | 数量进 `s1`，不丢 |

**手动项**（sensei 点）：元件放进驱动器 → 终端里能看到数量 → 切模式后数量不变 → 存档重进还在。

> **2026-10-03 规格更正（隔离）**：P2 实现时 `setMode` 里加过「切换时把 s1/s2 互相合并」，
> 理由是「别让玩家以为丢东西」——**那是实现者自加的设计，与「不同主模式存储空间完全隔离」冲突**，
> sensei 当天复核时指出（切到 mode1 能看到 mode2 的普通存储 ⇒ 看起来共用一间）。
> 现已移除合并：`setMode` 只切开关，`s1`/`s2` 各自独立，`getStoredItemCount` / `storedAmount` /
> `summaryBytes` / `getStoredItemTypes` 一律**只读当前模式那一间**（否则对外数字与终端列表又会漂移）。
> mode3 本来就不存东西（全部无限），其存量读数恒为 0。

---

## 7. 文件清单与实施顺序

| 文件 | 动作 |
|---|---|
| `cell/CellStorageData.java` | 新增：一个 uuid 的数据容器（s1/s2/ca/wl/ul/m3）+ NBT 读写 |
| `data/CellWorldData.java` | 新增：`WorldSavedData`，持 `Map<UUID, CellStorageData>`，`get(World)` 静态取用 |
| `cell/UnlimitedCellInventory.java` | 重写：加 mode/wm/thr/wl/ul/ca/s1/s2 + §2 的判定；NBT 只读写小字段；接到 `CellWorldData` |
| `item/ItemUnlimitedCell.java` | 改 tooltip（按 §4.3-3） |
| `command/CommandCell.java` | 新增：§5 的设置入口 |
| `command/CommandCellSelfTest.java` | 扩 B 组断言 |
| `AE2Addon.java` | 注册新命令 |

**顺序**：① `CellStorageData` + `CellWorldData`（先能用 javap 验的纯数据层）→
② `UnlimitedCellInventory` 判定层（照 §2 抄，注意 §3 的反方向）→
③ 命令 + tooltip → ④ B 组断言 → ⑤ 构建、部署、跑自检。

**每一步都要能独立跑**：① 之后可以写个 `/ae2addoncelltest` 的小断言先验 `WorldSavedData` 存取；
② 之后立刻跑 B1~B9；③④ 只是入口与断言。
