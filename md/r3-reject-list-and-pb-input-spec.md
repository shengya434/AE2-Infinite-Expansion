# 规格：R3 —— ①拒收列表（两个界面）+ 默认拒收按钮移位；②Productive Bees 方块转换的消耗输入修好

目标版本仍是 2.0.1。**只修这两处，别顺手改别的。** 不要自己构建（构建由爱丽丝跑）。

---

## R3-A：Productive Bees `BlockConversionRecipe` 的输入/产物映射修好（sensei 实测报的）

**现象**：方块转方块的配方，输入只剩蜜蜂刷怪蛋（催化剂），**该有的方块物品消耗输入丢了**。

**根因（已取证）**：
- PB 的 `cy/jdkdigital/productivebees/common/recipe/BlockConversionRecipe`（javap 实证）字段是：
  `Ingredient input` / **`BlockState stateFrom`** / **`BlockState stateTo`** /
  **`Ingredient fromDisplay`** / **`Ingredient toDisplay`** / `int chance`。
- JSON 里输入字段写作 `"from": {"Name": "minecraft:chipped_anvil"}`（由 codec 映射到 `stateFrom`）——
  所以 `stateFrom` 是**方块状态对象**；
- 而我们的 `itemOptions(Object)` **只吃 `Ingredient`**（其他类型直接返回空）⇒ 传 `stateFrom` 恒为空 ⇒
  前面那版只能靠新加的流体映射兜底 ⇒ `chipped_anvil`/`blackstone` 这种**有物品形态**的方块也进不了输入槽。
- PB 自己还给了 **`fromDisplay` / `toDisplay`**（`Ingredient`）—— 正是"这个方块用哪些物品代表"，**优先读它**。

**要改成（输入侧，按顺序取第一个非空）**：
1. `field(recipe, "input")`（JSON 显式覆盖，多数配方没有）
2. **`field(recipe, "fromDisplay")`**（喂给 `itemOptions(...)`，它吃 Ingredient ✓）
3. `blockStateToItemKey(field(recipe, "stateFrom"))` ← **新增 helper**
4. `blockStateToFluidKey(field(recipe, "stateFrom"))`（R2 已加，保留）
5. 全空 → 保留现在的 `warnOnce("pb-block-from-noitem", ...)`（文案里带上"已试过 fromDisplay/物品/流体"）
   ——⚠ **空气**（`air_to_water` 的 from=air）就该走这里：没有输入槽是**正确**行为，日志是 info 级还是 warn 级你定，
   但**不许**因此丢掉蜜蜂那一槽。

**产物侧同理（按顺序）**：`stateTo` → `toGeneric(stateTo)` → 为 null 则 **`field(recipe, "toDisplay")`** →
仍 null 则 `blockStateToItemKey(stateTo)` → 仍 null 则 `blockStateToFluidKey(stateTo)` → 仍 null 才 warn。

**新增 helper**（放在 `blockStateToFluidKey` 旁边，风格一致）：
```java
/** 方块状态 → 物品形态（空气返回 null）。 */
@Nullable
private static GenericStack blockStateToItemKey(@Nullable Object value) {
    if (!(value instanceof BlockState state)) return null;
    Item item = state.getBlock().asItem();          // 方块 → 对应物品
    if (item == null || item == Items.AIR) return null;
    ...  // 用现成的 toGeneric(...) 路径把它变成 AEItemKey 的 GenericStack
}
```
- **空气必须返回 null**（`Items.AIR`）——否则 `air_to_water` 会多出一个"空气物品"输入槽。
- 成功后打一条 `info` 日志（说明是哪条配方、走的是哪条路径：`fromDisplay` / 方块物品 / 流体），
  方便实机一眼确认；**别静默**。

**禁止**：改其它配方类型的分支；改 `beeOptions`/`addBeeOptions` 的催化剂语义（蜜蜂仍然是催化剂，方块物品是**消耗**输入 —— 现状 `slots.add(blockInput)` 已经是消耗 ✓ 保持）。

---

## R3-B：拒收列表 + 默认拒收按钮移位（sensei 实测报的）

**现象（死锁）**：Ctrl+右键把某物品设为拒收后，一旦把该物品从元件里**抽空**，它就从界面列表里消失
（列表数据来自元件存量/触碰集合）⇒ 既存不进去、也不在列表里 ⇒ **永远切不回来**。

**修法核心**：**拒收列表的数据必须来自 `rejectOn` / `rejectOff` 这两个显式集合**，
**与元件存量/触碰列表完全解耦**（抽空也不消失）✓

### B1. Mode2 界面：列表区加「物品 / 拒收」两个页签
- 列表区顶部加两个小页签（或等效切换按钮）：`物品`（现状列表，默认）/ `拒收`（新列表）。
  **不要**新开辟大面板、**不要**压到 S11 定下的布局：面板 `72..150`、编辑控件 `151..169`、
  物品栏标题 `182`、背包槽 `192+r*18`、快捷栏 `250`（`H=276`）。页签放在列表标题那一行右侧即可。
- 「拒收」页内容 = `rejectOn ∪ rejectOff` 里**显式设置过**的 key：
  - 每行：物品图标 + 显示名 + 标记（`rejectOn` 的显红 **拒**，`rejectOff` 的显绿 **允**）；
  - 名称/图标从 **key** 取（`key.getDisplayName()` / `AEKey` 的图标路径，照现有列表怎么画就怎么画），
    **不许**依赖元件里是否还有这个物品；
  - 空集合时显示一行灰字（如 `§7（没有拒收物品）`）；
  - **超过一屏要能滚动**（复用现有列表的滚动条机制）。
- 交互（与 Ctrl+右键 等价，走同一个 packet 路径）：
  - **左键** = 切换该 key（拒 ↔ 允，写显式项）；
  - **Shift+左键** = 清除该 key 的显式设置（从两个集合里删掉，回到元件默认）；
  - 鼠标悬停显示 tooltip（物品名 + 当前状态 + "左键切换 / Shift+左键清除"）。

### B2. ModeSelect（模式 1/2/3 那个界面）
- 加一栏**紧凑的拒收列表**（同样的数据源）：限高 + 可滚动，标题带计数（如 `拒收物品 (3)`）；
  交互同 B1（左键切换 / Shift+左键清除）；
- **「默认拒收」勾选框放在这里**（从 Mode2 界面**移过来**，两处不要都有）。
- 这个界面可能因此需要加高：**只加高，不许改现有按钮/坐标的相对关系**（mode 1/2/3 三按钮、格式化按钮保持原样）。

### B3. 状态同步（客户端要能画出这个列表）
- 客户端必须知道服务端的 `rejectOn` / `rejectOff`：
  - **打开界面时**（`Menu` 构造/初始化）同步一次；
  - **每次切换/清除后**服务端回发一次（R1 已加的 type 18 界面状态同步可以扩展带上这两个集合，
    也可以新增一个 type —— 你定，但**必须复用现有"校验发包者确实打开了该元件菜单"的做法**）。
- 集合可能很大（几百条）→ 参考现有大列表的分块传输机制（`MAX_ITEMS_PER_CHUNK` 那套），**别一次塞爆包**。

### B4. 文案
- 新增/调整语言键（`zh_cn.json` + `en_us.json` 各一份）：
  `gui.ae2addon.mode2.tab_items`（物品 / Items）、`gui.ae2addon.mode2.tab_reject`（拒收 / No-In）、
  `gui.ae2addon.mode2.reject_empty`（（没有拒收物品） / (no No-In items)）、
  `gui.ae2addon.mode2.reject_allow_mark`（允 / Allow）、
  `gui.ae2addon.mode2.reject_tip`（悬停提示）；
  `gui.ae2addon.mode2.reject_default` 复用（按钮搬到 ModeSelect 后仍用它）。
- `hint_bar` 里补一句：「拒收页：左键切换 / Shift+左键清除」。

### B5. 不许破坏
- R1 的语义一个字不动：拒收只拦进不拦出、拒收优先于锁定、物质球内层被拒收整球不吞、旧的 `return amount`（锁定）保留；
- 存量列表/规则列表/黑名单/阈值这些现有功能一个都不能变（页签只是并排多一个视图）；
- Mode2 的 S11 布局坐标不许被压（见 B1）；
- `extract` 路径 0 改动。

---

## 报告要求（中文）

1. 改动文件清单 + 摘要；
2. **R3-A**：三条典型配方的预期走向（`anvil_repair`(chipped_anvil→anvil)、`blackstone_to_gilded_blackstone`、
   `air_to_water`）分别会读到哪条路径、加出什么槽（**逐条写清**）；
3. **R3-B**：页签/列表/勾选框的具体落点（含坐标）与同步方式（哪个 type、分块与否）；
4. 你自己**没能确认**的点（例如 `fromDisplay` 在实机上是否真的非空）—— 一律列出来，不许含糊。
5. `git diff --stat` 若取不到就直说取不到。
