# 万能无限盘（Universal Storage Cell）功能规格 — 2026-09-27

> 本文件是**实现规格**，由爱丽丝编写、Codex 实现、sensei 验收。
> 实现时**必须**遵守文末《本仓库硬约束》。

## 背景：现有结构（先读这些文件）

| 文件 | 作用 |
|---|---|
| `src/main/java/com/ae2addon/item/UniversalStorageCell.java` | 万能无限元件物品。`umode` NBT = 1(无限)/2(自定义无限)/3(全类型无限)。mode2 右键开 `Mode2ConfigMenu` |
| `src/main/java/com/ae2addon/cell/UnlimitedCellInventory.java` | 真正的存储逻辑。`load()`/`save()` 在 ItemStack NBT ⟷ `CellDataSavedData.CellData` 之间同步 |
| `src/main/java/com/ae2addon/data/CellDataSavedData.java` | 世界存档里的 `CellData`（按元件 UUID 索引）：`s1`/`s2`(BigInteger 存储)、`wl`/`ul`(无限集合)、`ca`(承诺额度)、`m3`、`tags`/`mods`(批量规则)、`ruleInstant`、`ruleTouched`、`blacklist` |
| `src/main/java/com/ae2addon/gui/Mode2ConfigMenu.java` | mode2 菜单（客户端/服务端各一份；`getCellStack()` 拿得到元件栈） |
| `src/main/java/com/ae2addon/gui/Mode2ConfigScreen.java` | mode2 界面（工作模式：1=阈值 / 2=存入∞ / 3=臻藏） |
| `src/main/java/com/ae2addon/network/Mode2ConfigPacket.java` | 客户端↔服务端协议。**已用 type 0–13**，新类型请从 **14** 起 |
| `src/main/java/com/ae2addon/block/InfiniteDriveBE.java` | 驱动器（无限级）方块实体（元件编辑器要扫它的槽） |

`CellData` 的读写样板见 `UnlimitedCellInventory.copyFromCellData()` / `save()`：
**加字段必须同时改这四处**（`CellDataSavedData.CellData` 的字段 + `save` + `load`，
以及 `UnlimitedCellInventory` 的字段 + `copyFromCellData` + `save` + 任何清空路径）。

---

## S1 · 一键格式化按钮（**已实现并已构建**，本轮按 S1b 修正）

---
# S1b · 按 sensei 实测反馈修正（**本回合只做这一项**）

sensei 试了 S1 后给了三条反馈，S1b 全部处理：

1. **格式化按钮位置不对**：要从 mode2 配置界面**移到「选择存储模式」界面**
   —— 就是 `gui/ModeSelectScreen.java`（mode 1/2/3 三按钮那个，标题 key `gui.ae2addon.mode_select`）。
2. **输入框里要有"该输入什么"的提示**：现在用 `EditBox.setHint` 是**看不见的** ——
   MC 只在输入框**未聚焦**时才画 hint，而这里一弹出就自动聚焦了。
   ⇒ **必须自己画一行常显提示文字**（`g.drawString`），不要依赖 `setHint`。
3. **回车好像没反应**：很可能就是第 2 条导致的（不知道要输入 `DELETE`，回车判不匹配），
   而**不匹配的反馈只闪在动作栏**，看不见。
   ⇒ 两条修：① `keyPressed` 里判断回车**不要再要求 `formatInput.isFocused()`**（只要求 `formatOpen`）；
   ② 反馈改成**聊天栏**（`displayClientMessage(msg, false)`，不是 `true` 的动作栏），并说清该输入什么。

### 改动清单

**A. `gui/Mode2ConfigScreen.java` —— 移除**
- 移除 `formatButton` / `formatInput` / `formatConfirmButton` / `formatCancelButton` 四个控件、
  `formatOpen` / `formatConfirmed` 两个状态，以及 `updateFormatVisibility()` /
  `showFormatConfirmation()` / `hideFormatConfirmation()` / `checkFormatText()` / `confirmFormat()`
  这几个方法，和 `buildFullConfigUI()` 里创建它们的那段、`keyPressed` 里 `formatOpen` 那段分支。
- 恢复搜索框原来的创建方式（`searchBox` 不再被 `updateFormatVisibility()` 控制可见性）。
- **不要**删语言文件里的 key（S1b 还要用）。

**B. `gui/ModeSelectScreen.java` —— 新增**（现在只有 44 行，`W=160, H=115`）

```java
private static final int W = 180, H = 158;   // 加高加宽，给确认栏腾地方
// 三个模式按钮保持原样（居中，宽 150，仍在 topPos + 10 / 36 / 62）
// 格式化按钮：居中、宽 150、高 20，位于 topPos + 90
// 确认栏（默认隐藏）：
//   formatInput        : (leftPos + 10,  topPos + 116, 118, 16)
//   formatConfirmButton: (leftPos + 130, topPos + 115, 20, 18)   "√"
//   formatCancelButton : (leftPos + 152, topPos + 115, 20, 18)   "✕"
```

- 格式化按钮文案 `gui.ae2addon.mode_select.format_btn`；点击 → `showFormat()`：清空输入、
  `formatConfirmed=false`、确认栏可见、`setFocused(formatInput)` + `formatInput.setFocused(true)`。
- **常显提示自己画**（`render` 里 `g.drawString`，不要用 `setHint`）：
  - 未展开时：`gui.ae2addon.mode_select.format_warn`（说明会清空 mode1/mode2）
  - 已展开时：`gui.ae2addon.mode_select.format_hint`（说明输入 DELETE + 回车 + 点 √）
  两行画在 `(leftPos + 10, topPos + 138)`。
- `keyPressed`：
  ```java
  if (formatOpen) {
      if (keyCode == 257 || keyCode == 335) { checkFormatText(); return true; }  // ⚠ 不要求 isFocused
      if (keyCode == 256) { hideFormat(); return true; }                          // Esc 收起确认栏
      if (formatInput.isFocused()) { formatInput.keyPressed(keyCode, scanCode, modifiers); return true; }
  }
  ```
- `checkFormatText()`：文本（trim、忽略大小写）为 `DELETE` / `确认` / `删除` 之一 → 确认态、
  `√` 文案变 `§a√`、聊天栏 `format_ok`；否则聊天栏 `format_bad`。
- `confirmFormat()`：未确认 → 聊天栏 `format_first`；已确认 → `hideFormat()` 后
  `AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(14, 0L, ""))`。
- `formatInput` 的 `setResponder`：把 `formatConfirmed` 打回 false、`√` 恢复 `§c√`。

**C. `network/Mode2ConfigPacket.java` —— type 14 找元件再加一条**
现在只认 `Mode2ConfigMenu`；按钮搬到 `ModeSelectScreen` 后还要认 `ModeSelectMenu`
（它已有 `getCellStack()`）：

```java
ItemStack stack = ItemStack.EMPTY;
if (p.type == 14) {
    if (player.containerMenu instanceof com.ae2addon.gui.Mode2ConfigMenu m2) {
        ItemStack s = m2.getCellStack();
        if (s != null && !s.isEmpty() && s.getItem() instanceof UniversalStorageCell) stack = s;
    } else if (player.containerMenu instanceof com.ae2addon.gui.ModeSelectMenu m1) {
        ItemStack s = m1.getCellStack();
        if (s != null && !s.isEmpty() && s.getItem() instanceof UniversalStorageCell) stack = s;
    }
}
if (stack.isEmpty()) stack = player.getMainHandItem();
if (!(stack.getItem() instanceof UniversalStorageCell)) {
    stack = player.getOffhandItem();
    if (!(stack.getItem() instanceof UniversalStorageCell)) return;
}
```

**D. 语言文件**（`zh_cn.json` 与 `en_us.json` **都要加**）
```
gui.ae2addon.mode_select.format_btn     = "§c格式化此元件"
gui.ae2addon.mode_select.format_warn    = "§7格式化会清空本元件 mode1/mode2 的全部内容与规则"
gui.ae2addon.mode_select.format_hint    = "§7输入 DELETE 后按回车确认，再点 √ 才会执行"
gui.ae2addon.mode_select.format_ok      = "§a已确认，请点击 √ 执行格式化"
gui.ae2addon.mode_select.format_bad     = "§c确认文字不匹配：请输入 DELETE（或 确认 / 删除）"
gui.ae2addon.mode_select.format_first   = "§c请先输入 DELETE 并按回车确认"
```

**E. 不要动**：`wipeAllData()`、type 14 的服务端清数据与刷新逻辑、`Mode2ConfigPacket` 的 encode/decode。

---
# S2 · tag/mod 每条规则**独立的无限模式**（**本回合只做这一项**）

sensei 原话：「对于 tag/mod 批量编辑**撤销全局化编辑**立即无限与触碰无限，
**每 tag/mod 给予单独无限模式标签，永保留，除非删除，支持后续右键更改模式**」。

## 现状（问题所在）

`CellData.ruleInstant` 是**一个全局布尔**：一个开关管所有 tag/mod 规则。
界面右上角一个「立即全量 / 触碰后」按钮全局切换（包 type 10）。
要改成**每条规则各自一个模式**。

## 语义定义

- 规则模式：`1 = 立即无限`（命中即无限）、`2 = 触碰无限`（命中且**存入过**才无限，判定靠 `ruleTouched`）
- **永保留，除非删除**：模式跟着规则条目共存亡；删规则才删模式；其它任何清理（如 `ruleTouched` 的清理）**不得**动它
- 一个 key 可能同时命中多条规则（tag + mod）：**只要有一条是按「立即」，就算无限**；
  全都只有「触碰」时才要求 `ruleTouched.contains(key)`

## 实现清单

### A. `data/CellDataSavedData.java` — `CellData` 加字段
```java
/** tag/mod 规则各自的无限模式：key = "tag:"+name 或 "mod:"+name，值 1=立即 2=触碰。
 *  没有条目时按「立即」处理（与旧默认 ruleInstant=true 一致）。 */
public final Map<String, Integer> ruleModes = new HashMap<>();
```
- `save()` 里 `putStringIntMap(tag, "rm", ruleModes)`；`load()` 里读回（自己照 `putStringSet`/`getStringSet`
  的风格写一对工具方法，别复用 `putStringSet`）。
- **旧存档迁移**：读到 `rm` 为空且旧字段 `ri`（= `ruleInstant`）为 `false` 时，
  给 `tags` / `mods` 里已有的每条规则补一个 `2`（触碰）。`ri` 字段**继续读**（兼容），
  但 S2 之后**不再写回、不再参与判定**（可以保留 `ruleInstant` 字段本身以免动到别处）。

### B. `cell/UnlimitedCellInventory.java`
- 加同名字段 `ruleModes`，并在 `copyFromCellData()` / `save()` 里同步（照 `tags` 的写法）。
- 加方法：
  ```java
  public static String ruleKey(boolean isTag, String name) { return (isTag ? "tag:" : "mod:") + name; }
  public int getRuleMode(String ruleKey) { return ruleModes.getOrDefault(ruleKey, 1); }
  public void setRuleMode(String ruleKey, int mode) { ruleModes.put(ruleKey, mode == 2 ? 2 : 1); dataDirty = true; }
  /** 该 key 是否被批量规则允许无限（取代原来 matchesRule(x) && (ruleInstant||ruleTouched) 的写法）。 */
  public boolean ruleAllowsInfinite(AEKey key) { ... }
  ```
  `ruleAllowsInfinite` 逻辑：遍历命中的 tag 规则与 mod 规则（用现有的 tag/mod 匹配工具，别重写匹配）；
  命中集合为空 → false；任一命中规则 `getRuleMode(...)==1` → true；否则 → `ruleTouched.contains(key)`。
- `addTagRule` / `addModRule`：成功添加时**默认写 `1`**（立即）。
- `removeTagRule` / `removeModRule`：**同时删掉对应的 `ruleModes` 条目**。
- **把原来所有 `matchesRule(x) && (ruleInstant || ruleTouched.contains(x))`（以及等价的
  `matchesRule(x) && !blacklist.contains(x)` 之类）改成走 `ruleAllowsInfinite(x)`**，
  黑名单判断保持在最外层（`!blacklist.contains(x)`）。
  ⚠ 先自己 grep 全库找出所有判定点（已知至少 `getTypeCount`、某处 `hasInfinite`/`isInfinite`、
  两处 `ruleCount` 统计），**逐个替换**，别漏。
- `wipeAllData()` 里补：`ruleModes.clear();`（`ruleInstant = true;` 保留）。

### C. `network/Mode2ConfigPacket.java`
- **type 9 载荷改形**（服务端→客户端规则数据）：
  `boolean isTag` → `varint count` → 每条 `String rule` + `byte mode`。
  构造器 `Mode2ConfigPacket(List<String> ruleList, boolean isTag, boolean ruleInstant)`
  改成 `Mode2ConfigPacket(List<String> ruleList, List<Integer> modeList, boolean isTag)`（仍是 type 9）。
  encode/decode **成对改**。
- **新增 type 15**（客户端→服务端：设置某条规则的模式）：
  新构造器 `Mode2ConfigPacket(int type, String rule, boolean isTag, int ruleMode)`；
  encode：`writeBoolean(isTag)` + `writeUtf(rule)` + `writeVarInt(ruleMode)`；
  decode：`if (type == 15) {...}` 读回三样。
- 服务端 `handle()` 加分支：
  ```java
  } else if (p.type == 15 && !p.rule.isEmpty()) {
      inv.setRuleMode(UnlimitedCellInventory.ruleKey(p.isTag, p.rule), p.ruleMode);
      sendRuleData(inv, player);
      sendPanelRefresh(inv, player);
      return;
  }
  ```
- `sendRuleData(inv, player)` 改成把模式列表一起发（两条包，tag 一条 mod 一条，各自带自己的模式）。
- type 10（旧的全局切换）**客户端不再发**；服务端分支可以留着（无害）或删掉，任选一个别留半截。

### D. `gui/Mode2ConfigMenu.java`
- `setRuleData(boolean isTag, List<String> rules)` → 加一个模式列表参数；两个缓存都存。
  加 getter：`getRuleMode(String ruleKey)`（没有则 1）。
- 加 `sendSetRuleMode(String rule, boolean isTag, int mode)` → `new Mode2ConfigPacket(15, rule, isTag, mode)`。
- 保留 `sendAddTagRule`/`sendAddModRule`/`sendRemoveTagRule`/`sendRemoveModRule`。

### E. `gui/Mode2ConfigScreen.java`
- **删掉全局「立即/触碰」按钮**（`buildFullConfigUI()` 里 `modeBtnX` 那个）以及它用的
  `ruleInstant` 客户端字段与 `menu.sendSetRuleInstant(...)` 调用；把让出来的横向空间给规则输入框。
- `handleRuleData(...)` 签名跟着 type 9 改（加模式列表），缓存进 `menu`。
- **规则行显示模式标签**：在 `renderRuleBar()` 里每行的名字后面画
  `§e[立即]`（mode 1）或 `§7[触碰]`（mode 2）。
- **右键规则行 = 切换该规则的模式**：在 `mouseClicked()` 里对规则行区域做
  `if (button == 1)` 命中判断 → `menu.sendSetRuleMode(rule, isTag, 当前==1 ? 2 : 1)`；
  左键维持原有行为（删除规则等），不要互相抢。
- 在规则区加一行小字说明：`gui.ae2addon.mode2.rule_mode_tip`
  （文案：`§7左键删除规则；右键切换该规则的无限模式（立即/触碰）`）。

### F. 语言文件（zh_cn / en_us 都要）
```
gui.ae2addon.mode2.rule_mode_instant = "§e[立即]"
gui.ae2addon.mode2.rule_mode_touch   = "§7[触碰]"
gui.ae2addon.mode2.rule_mode_tip     = "§7左键删除规则；右键切换该规则的无限模式（立即/触碰）"
gui.ae2addon.mode2.rule_mode_set     = "§a规则 %s 已设为%s"
```

### G. 不要动
`wipeAllData()` 的其余部分、type 14、S1b 在 `ModeSelectScreen` 里的格式化界面。
不要顺手重构不相关代码。

---
# S3 · 单物品阈值（优先于全局）+ 算式输入（**本回合只做这一项**）

sensei 原话：「对于 mode2 **阈值模式**，除全局设置阈值外，**增加单物品阈值设定（优先级大于全局）**」。

## 现状（已读代码，别重新猜）

- 阈值模式 = `workMode == 1`。物品先累加进 `s2`，**当某物品总量 ≥ `thr` 时升级为无限**
  （`ul.add(what)` + `ca.put(what, total)`，并从 `s2` 移除）—— 见
  `UnlimitedCellInventory.insert()` 里 `if (mode == 2 && workMode == 1)` 那一段。
- 全局阈值 `thr`：存在 ItemStack NBT 的 `thr` 键；`setThreshold()` 里
  `thr = Math.max(1, Math.min(t, INFINITE))`。
- 读 `thr` 的地方至少三处，**都要改成"单个物品优先"**：
  1. `insert()` 里的升级判定（上面那段）
  2. `setWorkMode()` 里那段按阈值决定升级的迁移逻辑
  3. `ca.getOrDefault(key, BigInteger.valueOf(thr))` 的回落值
- 列表手势现状（**本回合要加第四个，不要抢已有的**）：
  - 左键 = 切无限（`sendToggleInfinite`）
  - Shift+左键 = 切黑名单（`sendToggleBlacklist`）
  - 规则条上：右键 = 切该规则模式、Shift+左键 = 删规则
  ⇒ **右键（`button == 1`）点面板条目 = 设置该物品的单物品阈值**（面板条目上右键目前空着）

## 实现清单

### A. 新增 `util/NumberExpr.java`（公共算式解析器）
把 `client/gui/AE2AddonConfigScreen` 里私有的 `parseLong()` / `evalExpr()` / `ExprParser`
**整体搬**到新类 `com.ae2addon.util.NumberExpr`，暴露：
```java
public static long parse(String text) throws NumberFormatException;   // 先 Long.parseLong，失败才走表达式
```
然后：
- `AE2AddonConfigScreen` 改为调用 `NumberExpr.parse(...)`，删掉它自己那份（**行为不许变**：
  仍支持 `+ - * / ^` 括号、科学计数 `1e12`、`MAX`/`INF`、单位后缀 `K/M/G/T/P/E`，大小写通吃）。
- `Mode2ConfigScreen.saveThreshold()` 里的裸 `Long.parseLong` **也换成 `NumberExpr.parse`**（顺带满足 sensei 的"支持算式输入"）。

### B. 数据：`data/CellDataSavedData.java` 的 `CellData`
```java
/** 单物品阈值（优先于全局 thr；没有条目 = 用全局）。0 或缺失都表示"用全局"。 */
public final Map<AEKey, Long> itemThr = new HashMap<>();
```
- 用现成的 `putLongMap(t, "it", itemThr)` / `getLongMap(t, "it", ...)` 存取（这两个工具方法已存在）。
- ⚠ `AEKey` 作为 key 的 map 在 `CellData` 里已有先例（`ca`/`s1` 等），照它们的写法。

### C. `cell/UnlimitedCellInventory.java`
- 同名字段 + `copyFromCellData()` / `save()` 同步 + `wipeAllData()` 里 `itemThr.clear()`。
- 加：
  ```java
  /** 该物品实际生效的阈值：单物品优先，否则全局 thr。 */
  public long thresholdFor(AEKey key) {
      Long v = itemThr.get(key);
      return (v != null && v > 0) ? v : thr;
  }
  /** 设置/清除单物品阈值（value <= 0 = 清除，回落全局）。 */
  public void setItemThreshold(AEKey key, long value) { ... dataDirty = true; }
  public Map<AEKey, Long> itemThresholds() { return itemThr; }
  ```
- 把上面「现状」列出的三处 `thr` 读点换成 `thresholdFor(key)`。

### D. 协议：`network/Mode2ConfigPacket.java` 新增 **type 16**
- 客户端→服务端：设置单物品阈值。载荷：`CompoundTag keyTag` + `long threshold`（0 = 清除）。
- 新构造器 `Mode2ConfigPacket(CompoundTag itemThrKeyTag, long itemThrValue)`（type 16）；
  encode/decode **成对加**（照 type 11 的写法，多写一个 long）。
- 服务端 `handle()` 加分支：
  ```java
  } else if (p.type == 16 && p.itemThrKeyTag != null) {
      AEKey key = AEKey.fromTagGeneric(p.itemThrKeyTag);
      if (key != null) {
          inv.setItemThreshold(key, p.itemThrValue);
          player.sendSystemMessage(Component.translatable(p.itemThrValue > 0
                  ? "gui.ae2addon.mode2.item_thr_set" : "gui.ae2addon.mode2.item_thr_clear",
                  key.getDisplayName()));
      }
      sendPanelRefresh(inv, player);
      return;
  }
  ```
### E. `gui/Mode2ConfigMenu.java`
- 加 `public void sendSetItemThreshold(CompoundTag keyTag, long value)` → 发 type 16。

### F. `gui/Mode2ConfigScreen.java`
- **面板条目右键**（`mouseClicked` 里 panel 行区域，`button == 1`）→ 打开一个**单物品阈值输入框**：
  - 在面板下方或覆盖位置放 `itemThrInput`（`EditBox`，预填该物品当前的单物品阈值；没有则空）
  - 回车/确定 → `menu.sendSetItemThreshold(key, NumberExpr.parse(文本))`；文本为空 → 传 `0` 表示清除
  - 解析失败 → 聊天栏报错，不动数据
  - 需要一个"确定 / 取消"的小按钮或回车确认（沿用现有风格，别引入新框架）
- **列表行显示**：该物品有单物品阈值时，在数量旁边多画一个标记 + 数值，
  例如 `§b⚑1.2K`（用现成的 `formatAmount` 风格）；没有则不画。
- 保留左键=切无限、Shift+左键=切黑名单**不变**。
- 在面板底部或规则条附近补一行小字说明手势：
  `gui.ae2addon.mode2.item_thr_tip`（`§7条目上：左键切无限 / Shift+左键切黑名单 / 右键设单物品阈值`）。

### G. 语言文件（zh_cn / en_us 都要）
```
gui.ae2addon.mode2.item_thr_title  = "§e单物品阈值（留空=用全局）"
gui.ae2addon.mode2.item_thr_set    = "§a已设置 %s 的单物品阈值"
gui.ae2addon.mode2.item_thr_clear  = "§7已清除 %s 的单物品阈值（回落全局）"
gui.ae2addon.mode2.item_thr_bad    = "§c阈值算式无效"
gui.ae2addon.mode2.item_thr_tip    = "§7条目上：左键切无限 / Shift+左键切黑名单 / 右键设单物品阈值"
```

### H. 不要动
S1b 的格式化界面、S2 的逐规则模式、`wipeAllData()` 的其余部分。不要改 `NumberExpr` 的**行为**
（只是搬家 + 变 public），配置界面的算式行为必须与搬家前一字不差。

---
# S4 · 黑名单覆盖「阈值模式」与「存入无限模式」（**本回合只做这一项**）

sensei 原话：「对于 mode2 **阈值模式**与**存入无限模式**添加黑名单模式（**无法无限**）」。

## 现状（已读代码，三处入口只有一处查了黑名单）

`UnlimitedCellInventory.insert()` 里，物品**变成无限**的入口有三条：

| 入口 | 位置 | 现在查黑名单吗 |
|---|---|---|
| ① tag/mod 规则命中 | 规则段（`matchesRule(what)` 那段） | ✅ 已查 `!blacklist.contains(what)` |
| ② **存入无限模式**（`mode == 2 && workMode == 2`）| `if (!ul.contains(what)) ul.add(what);` | ❌ **没查** |
| ③ **阈值模式**（`mode == 2 && workMode == 1`）总量到阈值升级 | `ca.put(...) + ul.add(what)` | ❌ **没查** |

## 要做的（很小，别扩大）

### A. `cell/UnlimitedCellInventory.java`
1. **入口②**（存入无限模式）加黑名单闸门：命中黑名单的物品**不要**加入 `ul`，
   而是**照常走下面的非无限累加路径**（即它仍然能被存进 `s2`，只是永远不升级为无限）。
2. **入口③**（阈值模式升级）同样加闸门：命中黑名单的物品即使总量超过阈值，
   **也不升级**（留在 `s2` 继续累加）。
3. 两处都加一行注释说明：`// 黑名单：无法无限（2026-09-27 sensei：阈值/存入无限模式也要拦）`。

### B. 明确不做（写进代码注释，免得下次有人"顺手"改）
- **不**处理「已经在 `ul` 里的物品被加入黑名单」这种情况：黑名单只负责**不许变成无限**，
  已经在无限集合里的条目**本次不动**（避免加黑名单导致数量被搬来搬去）。
  在 `toggleBlacklist()` 上补一句注释说明这个边界，并留一行 TODO 描述可选后续行为。

### C. 不要动
`wipeAllData()`、S1b 的格式化界面、S2 的逐规则模式、S3 的单物品阈值。
`ruleAllowsInfinite()` 的逻辑也不要动（它已经正确处理了黑名单外的规则判定）。
**不要**新增界面控件、不要新增语言 key —— 黑名单的界面与手势（Shift+左键）已经存在。

---
# S5 · mode2 逐条目「元件内数量」自定义 + 锁定（**本回合只做这一项**）

sensei 需求：「对 mode2 添加**存储数量自定义**（可配置**是否锁定**，支持**算式输入**）
（**锁定时数值最大为 `Long.MAX_VALUE`，大于该数值自动回退**）」。
已确认语义（两条都问过 sensei）：

- **锁定 = 数值本身被钉死**：后续**存入不再改变它**；**取出允许，但数量也不减**
  ⇒ 锁定条目 = 一个"数量恒定、可无限取出"的源
- **自定义数量也能用在已经是 ∞ 的条目上**：给 ∞ 条目设一个具体数字 = **把它从 ∞ 改回有限**（并可选锁定）

## 关键设计决定（照这个做，别另起炉灶）

**"自定义数量"就是「直接编辑该条目的存储量」**，不需要再存一份数量表：
- 数据里只多一个**锁定集合** `qtyLocked`
- 设数量 = 把 `s2[key]` 直接写成该值（值 ≤ 0 就删条目）；若该 key 原先在 `ul`（∞），
  **先从 `ul` 移除、并清掉 `ca`**，再写进 `s2`
- 锁定条目"数量不变"天然成立：**存入一律拒收、取出不扣减**（见下）

## 实现清单

### A. `data/CellDataSavedData.java` 的 `CellData`
```java
/** 已锁定「元件内数量」的条目：存入拒收、取出不扣减（数量恒定）。 */
public final Set<AEKey> qtyLocked = new HashSet<>();
```
用现成的 `putSet(t, "ql", qtyLocked)` / `getSet(t, "ql", data.qtyLocked)` 存取。

### B. `cell/UnlimitedCellInventory.java`
1. 同名字段 + `copyFromCellData()` / `save()` 同步 + `wipeAllData()` 里 `qtyLocked.clear()`。
2. 新增：
   ```java
   /** 直接设定某条目的「元件内数量」；lock=true 同时锁定。
    *  ⚠ 若该条目原先在 ul（无限），先移出 ul 并清 ca —— 设了数字就等于不再无限（sensei 已确认）。 */
   public void setStoredQuantity(AEKey key, BigInteger q, boolean lock) { ... dataDirty=true; save(); }
   public boolean isQuantityLocked(AEKey key) { return qtyLocked.contains(key); }
   public Set<AEKey> quantityLockedKeys() { return qtyLocked; }
   ```
   实现要点：`ul.remove(key); wl.remove(key); ca.remove(key);` →
   `q <= 0 ? s2.remove(key) : s2.put(key, q)`（mode 1 的条目就写 `s1`，按 `mode` 选表）→
   `lock ? qtyLocked.add(key) : qtyLocked.remove(key)`。
3. **`insert()` 最前面加锁定闸门**：`if (mode == 2 && qtyLocked.contains(what)) return 0;`
   （拒收 = 返回 0，物品留在来源侧，**不销毁**）。
   ⚠ 必须在**所有**变无限的路径（规则 / 存入无限 / 阈值）**之前**，这样锁定条目永远不会被它们改。
4. **`extract` 路径加锁定处理**：锁定条目**允许取出但数量不变**，返回
   `Math.min(请求量, 当前量)`（`当前量` 用 `s2`/`s1` 里的值），**不要**修改任何 map。
   ⚠ 要加在 `extract` 的最外层（在 `extractFromMap` / 无限分支之前），并确认
   **所有对外抽取入口**都会经过它（自己 grep `extract` 的重载与调用方，逐个确认）。
5. 阈值升级那段与存入无限那段**不需要再加**锁定判断（因为第 3 条已经在入口拦掉了）。

### C. 协议：`network/Mode2ConfigPacket.java` 新增 **type 17**
- 客户端→服务端：设某条目的元件内数量 + 是否锁定。
- 载荷：`CompoundTag keyTag` + `String quantityText`（**原样传玩家输入的算式**，服务端解析，
  这样解析逻辑只有一份）+ `boolean lock`。
- 新构造器 + encode/decode **成对加**（照 type 11 的写法，多写 `writeUtf` 与 `writeBoolean`）。
- 服务端 `handle()` 分支：
  ```java
  } else if (p.type == 17 && p.quantityKeyTag != null) {
      AEKey key = AEKey.fromTagGeneric(p.quantityKeyTag);
      if (key != null) {
          com.ae2addon.util.NumberExpr.Quantity parsed =
                  com.ae2addon.util.NumberExpr.parseQuantity(p.quantityText, p.lock);
          inv.setStoredQuantity(key, parsed.value(), p.lock);
          player.sendSystemMessage(...);   // 成功/回退都要给回执
      }
      sendPanelRefresh(inv, player);
      return;
  }
  ```

### D. `util/NumberExpr.java` 增加一个数量解析入口
```java
/** 元件内数量解析：支持算式；lock=true 时上限 Long.MAX_VALUE，超出自动回退到 Long.MAX_VALUE。 */
public record Quantity(java.math.BigInteger value, boolean clamped) {}
public static Quantity parseQuantity(String text, boolean lock) throws NumberFormatException;
```
- `lock == true`：先按现有 `parse()` 解成 long；若玩家写的**纯数字**超过 `Long.MAX_VALUE`
  （例如 `99999999999999999999`），**回退到 `Long.MAX_VALUE` 并把 `clamped=true`**。
- `lock == false`：允许超过 long —— 若文本是纯数字就直接 `new BigInteger(文本)`（不夹取）；
  含算式的仍走 `parse()`（long 范围）。
- 空字符串 → 视为 `0`（= 清除该条目数量）。

### E. `gui/Mode2ConfigMenu.java`
- 加 `public void sendSetQuantity(CompoundTag keyTag, String quantityText, boolean lock)` → 发 type 17。

### F. `gui/Mode2ConfigScreen.java`
- **手势**：现有三个已占（左键=切无限 / Shift+左键=切黑名单 / 右键=单物品阈值），
  **新增第 4 个：`Ctrl + 左键` 点面板条目 = 打开「元件内数量」输入框**。
  ⚠ 别的三个手势一个都不许抢；`Ctrl+左键` 要在 `button == 0` 分支里**先判断 `hasControlDown()`**。
- 输入框（照 S3 单物品阈值输入框的做法，同一套风格）：
  - 预填该条目当前数量（用现成 `formatAmount` 风格或 `String.valueOf`）
  - 输入框旁一个**锁定勾选框**（用 `Button` 做开关也行，文案 `§e🔒` / `§7🔓`），
    状态随输入框一起出现在同一行
  - 回车 / 确定 → `menu.sendSetQuantity(key, 文本, lock)`
  - 解析失败 → 聊天栏报错，不动数据
- **列表行显示**：锁定条目在数量旁边画 `§b🔒` 标记；未锁定但有自定义数量不额外标记（数量本身就显示了）。
- 提示行补一句（加到现有的手势提示里）：
  `gui.ae2addon.mode2.qty_tip`（`§7条目上：左键切无限 / Shift+左键切黑名单 / 右键设单物品阈值 / Ctrl+左键设元件内数量`）

### G. 语言文件（zh_cn / en_us 都要）
```
gui.ae2addon.mode2.qty_title    = "§e元件内数量（支持算式，如 8K / 2^20；留空=清除）"
gui.ae2addon.mode2.qty_lock_on  = "§e🔒 已锁定"
gui.ae2addon.mode2.qty_lock_off = "§7🔓 未锁定"
gui.ae2addon.mode2.qty_set      = "§a已设置 %s 的元件内数量"
gui.ae2addon.mode2.qty_clamped  = "§e输入超过 Long.MAX，已回退到 9223372036854775807"
gui.ae2addon.mode2.qty_bad      = "§c数量算式无效"
gui.ae2addon.mode2.qty_tip      = "§7条目上：左键切无限 / Shift+左键切黑名单 / 右键设单物品阈值 / Ctrl+左键设元件内数量"
```

### H. 不要动
S1b/S2/S3/S4 的行为。`wipeAllData()` 除 `qtyLocked.clear()` 外不动。

---
# S7 · 修「面板取消无限取不出东西」（**本回合只做这一项**）

## 症状（sensei 实测）

mode2 面板里**左键点条目 = 取消无限**（把承诺额度吐回背包，量大就打包成物质球）。
现在**点了一点东西都不吐**，背包里既没有物品也没有物质球。

## 根因（已核实，不是这几天改出来的）

`network/Mode2ConfigPacket.handleToggleInfinite()`：
```java
BigInteger committed = inv.hasCommitedAmount(key) ? inv.getCommitedAmount(key) : ZERO;
inv.togglePanelInfinite(key);
if (wasInfinite && committed.signum() > 0 && key instanceof AEItemKey itemKey) {
    outputOrBall(player, itemKey, committed);     // ← committed == 0 ⇒ 什么都不吐
}
```
**吐多少完全取决于 `ca`（承诺额度）。** 而"变无限"的三条路径里只有一条会写 `ca`：

| 路径 | 现在写 `ca` 吗 |
|---|---|
| 阈值模式升级 | ✅ `ca.put(what, total)` |
| **存入无限模式** | ❌ 只 `ul.add(what)`，**从不记** |
| tag/mod 规则命中 | ⚠️ 只把「原先进 `s2` 的量」以 `max` 并入；**这次存进来的量没记** |

⇒ 用「存入无限」模式存进去的东西，取消无限时 `committed=0` ⇒ 一个都不吐。

## 修法（sensei 已确认口径：**吐回"真存进来过"的量**）

只改 `cell/UnlimitedCellInventory.java` 的 `insert()`：

1. **存入无限模式**那条分支：加 `ul.add(what)` **之前**，把**本次存入量**累加进承诺额度：
   ```java
   ca.merge(what, BigInteger.valueOf(amount), BigInteger::add);
   ```
   并加注释说明：`// ⚠ 2026-09-27 修复（sensei：面板取消无限时取不出东西）：原来只 ul.add、不记 ca
   //   ⇒ togglePanelInfinite 算出 committed=0 就什么都不吐。存入无限模式下"确实存进来的量"
   //   应该记进承诺额度，取消无限时原数吐回。`
2. **tag/mod 规则命中**那条分支：保留原有的「把原先进 `s2` 的量以 max 并入 `ca`」，
   **另外**把**本次存入量**累加进 `ca`（`merge(what, BigInteger.valueOf(amount), BigInteger::add)`）。
   注释说明同样的理由。
3. **不要**改 `outputOrBall()`、不要改 `togglePanelInfinite()`、不要改 `handleToggleInfinite()` 的判断条件
   （`committed > 0` 依然成立 —— 立即无限且从未存过东西的条目，取消无限时本来就该什么都不吐，这是正确行为）。

## 顺带加一条诊断（便于验收）

在 `handleToggleInfinite()` 的 `outputOrBall(...)` 调用前，加一条**只在 `debugLogs` 打开时**打的日志：
```
[ae2addon][mode2] 取消无限: key={} 承诺额度={} → 输出
```
以及 `committed` 为 0 时：
```
[ae2addon][mode2] 取消无限: key={} 承诺额度=0 → 无可输出（立即无限且从未存入）
```
（用现成的 `CraftingCompat.debugLogs` 判断，别新造开关。）

## 不要动
S1b/S2/S3/S4/S5 的行为与界面。这条修复**不新增任何界面控件、不新增语言 key**。

---
# S6 · 新物品「元件编辑器」（**本回合只做这一项**）

sensei 需求：「**元件编辑器**：`shift+右键` 驱动器（无限级）**绑定**，
对**空气**右键打开 GUI，**选择驱动器中的一个万能无限元件**可打开该元件配置界面」。

## 设计选择（已定，照此实现，别另造一个 512 格选择界面）

驱动器**自己**已经有一块 512 格分页面板（`gui/InfiniteDriveMenu` + `gui/InfiniteDriveScreen`）。
**复用这块面板**，只在"由编辑器打开"时进入**选择模式**：
点某一格的万能无限元件 → 打开**那个元件**的配置界面；
点空槽或非本模组元件 → 提示一句，不做事。

## 实现清单

### A. 新物品 `item/CellEditorItem.java`
- 继承 `Item`；`stacksTo(1)`，稀有度随意（建议 `RARE`）。
- **绑定**：`useOn(UseOnContext)`
  - 条件：`player.isShiftKeyDown()` 且目标方块是**驱动器（无限级）**
    （读 `level.getBlockEntity(pos)` 判断是不是 `com.ae2addon.block.InfiniteDriveBE`；
    **不要**只看方块类名，多方块成型与否自行决定怎么判，拿不准就要求已成型：`isFormed()`）
  - 动作：把 `pos`（三个 int）与 `level.dimension().location().toString()` 写进该物品 NBT
    （键名 `edpos` / `eddim`），聊天栏提示"已绑定驱动器 @ x,y,z"
- **打开**：`use(Level, Player, InteractionHand)`
  - 若 `player.isShiftKeyDown()` → 不处理（Shift+右键是绑定）
  - 若未绑定 → 提示"先用 Shift+右键 驱动器绑定"
  - 若绑定维度 ≠ 当前维度 → 提示"绑定的驱动器不在这个维度"
  - 否则**服务端**：取出该位置的 `InfiniteDriveBE`，
    `NetworkHooks.openScreen(serverPlayer, driveBE, buf -> buf.writeBoolean(true))`
    （末尾那个 `true` = "编辑器模式"标志；**普通打开驱动器时写 `false`**）
  - ⚠ 方块实体可能已被拆/未加载 → 都要给提示，别静默失败

### B. 面板进入「选择模式」：`gui/InfiniteDriveMenu` + `gui/InfiniteDriveScreen`
- `InfiniteDriveMenu.fromNetwork(...)` 里多读一个 `boolean editorMode`（跟 A 里的写入**成对**），
  存成字段并暴露 `isEditorMode()`。
- `InfiniteDriveScreen`：
  - `editorMode == true` 时，在面板顶部（或底部）加一行常显说明文字：
    `gui.ae2addon.cell_editor.pick_hint`（`§7点一个万能无限元件即可打开它的配置界面`）
  - **点格子**的分支里（找到现有的"点击某格"处理处）：
    若 `editorMode` → 发新包 `CellEditorPickPacket(drivePos, slotIndex)` 并 `return true`；
    **不要**破坏编辑器的普通模式（`editorMode == false` 时行为一字不变）
  - 顺手把"编辑器模式"下不属于万能无限元件的格子画个淡灰底（可选，不做也行）

### C. 新包 `network/CellEditorPickPacket.java`
- 字段：`BlockPos pos` + `int slot`；encode/decode 成对。
- 服务端 `handle`：
  - 拿 `ServerPlayer.level().getBlockEntity(pos)` → 必须是 `InfiniteDriveBE`，否则返回
  - `var inv = drive.getInternalInventory();` → `ItemStack s = inv.getStackInSlot(slot);`
  - 若 `s.getItem()` 不是 `UniversalStorageCell` → 聊天栏提示 `gui.ae2addon.cell_editor.not_a_cell`，返回
  - 否则按那个元件的 `umode` 打开它自己的配置界面：
    - `umode == 2` → `Mode2ConfigMenu`（用**这个 `s`** 作为 `cellStack`）
    - 否则 → `ModeSelectMenu`
  - **实现提示**：`UniversalStorageCell` 里那两个 `MenuProvider` 是私有静态类，
    建议在 `UniversalStorageCell` 上加一个公开静态工具方法
    `public static void openConfigFor(ServerPlayer player, ItemStack stack)`
    （把 mode2 / modeSelect 的分流收在这一处，物品自己的 `use()` 和这个包都调它，**避免两份分流逻辑**）
  - ⚠ **必须保证改动落回驱动器**：先确认 `InfiniteDriveBE.getInternalInventory().getStackInSlot(i)`
    返回的是不是**活对象**（AE2 的驱动器库存一般是活引用，但**必须自己读代码确认**，
    不要假设）。若不是活引用，就用 `setStackInSlot` 在菜单关闭时写回。
    **在报告里明确说明你选了哪种、依据是什么。**

### D. 注册与资源（照本模组现有物品的做法）
- `init/ModItems`：注册 `cell_editor`，**并加进创造标签页** `TAB_AE2ADDON`
- 物品模型：`assets/ae2addon/models/item/cell_editor.json`（**贴图可先复用现有的**，
  例如某个已存在的 16×16 贴图，别去新画）
- 语言文件（`zh_cn.json` + `en_us.json` 都要）：
```
item.ae2addon.cell_editor             = "元件编辑器"
gui.ae2addon.cell_editor.bound        = "§a已绑定驱动器 @ %s"
gui.ae2addon.cell_editor.unbound      = "§c先用 Shift+右键 驱动器（无限级）绑定"
gui.ae2addon.cell_editor.wrong_dim    = "§c绑定的驱动器不在这个维度"
gui.ae2addon.cell_editor.missing      = "§c绑定的驱动器不存在或未加载"
gui.ae2addon.cell_editor.pick_hint    = "§7点一个万能无限元件即可打开它的配置界面"
gui.ae2addon.cell_editor.not_a_cell   = "§c该格不是万能无限元件"
```

### E. 不要动
S1b～S5、S7 的行为。**普通打开驱动器（非编辑器）时的一切行为必须一字不变** ——
这条改动最容易在 `InfiniteDriveMenu/Screen` 里把普通路径带坏，改完自己把普通路径过一遍。

---
# S5b · 修正锁定条目的 insert 语义：拒收 → **收下但不上账**（**本回合只做这一项**）

## 背景

S5 里我把「锁定」的 insert 语义定成了**拒收**（`return 0`）—— 那是我自己的决定，不是 sensei 的要求。
sensei 2026-09-27 澄清：**锁定 = 收下但不上账（进黑洞）**：
- 存进来的东西**照收**（调用方认为收下了、来源侧正常扣除）
- **但数量不变**（这个条目被钉死）
- 取出仍然允许、数量也仍然不减（S5 已实现，**不要动**）
- （"禁止存入"那种拒收行为，sensei 说以后可以**单开一个通用设置**，不跟锁定绑一起 ——
  本回合不做，只在代码里留一句 TODO）

## 改动（很小）

`cell/UnlimitedCellInventory.java` 里 S5 加的那几处 `qtyLocked` 闸门：

```java
// 现在（错）
if (mode == 2 && qtyLocked.contains(what)) return 0;

// 改成（对）：收下但不上账
if (mode == 2 && qtyLocked.contains(what)) return amount;   // 进黑洞：数字不变
```

- 三处都要改：`public insert(...)`、内部 `insert(..., depth)`、以及 `insertBI(...)` 里那一处
  （自己 grep `qtyLocked` 把 insert 侧的闸门**逐处**核对，抽取侧的不要动）。
- 每处都补注释：
  `// 锁定条目：收下但不上账（2026-09-27 sensei）—— 来源侧正常扣除，本条目数量被钉死不变。`
  `// TODO: “禁止存入（拒收）”以后单开一个通用设置，不与锁定绑定。`
- ⚠ **返回 `amount` 而不是 `0`**：返回 0 会让调用方认为"拒收"、物品退回来源；
  返回 `amount` 才是"收下了"。注意 `amount` 可能是 0 或负数（函数开头已有 `amount <= 0` 的处理，
  别把那个分支改坏）。
- **抽取出料一律不动**（仍是 `min(请求, 当前量)` 且不改 map）。

## 不要动
S5 的界面（Ctrl+左键、锁定开关、🔒 标记）、S7 的 `ca` 累加、S1b～S4。

---
# S8 · 修大数显示：单位上限太低，超出后拼出一长串数字（**本回合只做这一项**）

sensei 原话：「元件的 tooltip 中的**字节数显示单位上限也得提高了**，
而且**没法用正常的计量单位了**」。

## 现状（已读代码）

`item/UniversalStorageCell.formatBytes(BigInteger)`：
```java
String[] units = {"B","K","M","G","T","P","E","Z","Y","R","Q"};   // ← 到 Q（10^30）就没了
while (u < units.length - 1 && v.compareTo(base) >= 0) { v = v.divide(base); u++; }
```
⇒ 数量超过 10^33 之后**不再降级**，直接 `bd.toPlainString() + "Q"` 拼出一长串数字，
tooltip 被撑爆、完全读不出量级。**SI 前缀本身就到 Q 为止**，所以"提高上限"的正确做法是
**超出 Q 之后改用科学计数**，而不是继续造前缀。

同一个仓库里**已经有正确做法**可照抄（`gui/Mode2ConfigScreen.formatAmount(BigInteger)`）：
```java
if (amount.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) return formatAmount(amount.longValue());
String s = amount.toString();
return s.charAt(0) + "." + s.substring(1, Math.min(3, s.length())) + "e" + (s.length() - 1);
```

## 实现清单

### A. 新建 `util/SizeFormat.java`（把这件事收成一份，避免第 3 处再踩）
```java
public final class SizeFormat {
    /** 字节/数量的可读格式化：B/K/M/G/T/P/E/Z/Y/R/Q（1000 进制），超出 Q 后转科学计数。 */
    public static String bytes(java.math.BigInteger v);      // 例：1.2K / 3.4Q / 1.23e45
    public static String bytes(long v);
}
```
- 单位表沿用 `{"B","K","M","G","T","P","E","Z","Y","R","Q"}`，**1000 进制**（与原实现一致）。
- 保留原实现的**观感**：`u > 0` 时保留 **1 位小数**、向下取整（`RoundingMode.DOWN`）；
  `u == 0` 时直接输出整数 + `B`。
- **新增**：当已经用到 `Q` 且余量仍 ≥ 1000 时 → 输出**科学计数**：
  取十进制字符串前 3 位拼成 `d.dd` + `e` + (位数-1)，例如 `1234...（46 位）` → `1.23e45`。
- 负数/零按原实现（`< 0` 返回 `"0B"`）。

### B. 改用它
1. `item/UniversalStorageCell.formatBytes` → 直接调 `SizeFormat.bytes(...)`，
   并把原来那份私有实现删掉（**输出在上述范围内必须与原观感一致**，不要顺手改格式）。
2. `block/InfiniteInterfaceBE` 里那个自带的单位格式化（`units = {"","K","M","G","T","P","E"}`，
   `d.toPlainString() + units[unit]`）**同一个毛病，一起换**成 `SizeFormat.bytes(...)`
   —— 它在蓄水池摘要里显示超大宗数量，超 10^18 就会拼长串。
   ⚠ 换之前先看它输出的**场景与现有文案**，换完保持可读性不下降。

### C. 不要动
`client/IntegratedCpuStatusHud.formatBytes(long)`（入参是 long，本来就够用）、
`Mode2ConfigScreen.formatAmount`（面板观感是 K/M/G/T/P/E + 科学计数，已符合预期，**不要动**）、
以及 S1b～S7 的任何行为。

---
# S9 · 修「元件内数量」的算式结果被饱和到 9.2E（**本回合只做这一项**）

sensei 实测：「自定义物品存储数量这里**运算结果被锁死最大 9.2E** 了」。

## 根因（已定位到具体一行）

`util/NumberExpr.parseQuantity(text, lock)`：
```java
if (t.matches("[+-]?[0-9]+")) { ... BigInteger ... }          // 纯数字：OK，能超 Long.MAX
return new Quantity(BigInteger.valueOf(parse(t)), false);     // ← 算式：走 parse() → double → 截成 long
```
`parse()` 内部是 `double` 运算后 `(long) v`。**double 结果一旦超过 9.22e18，转 long 就饱和在 `Long.MAX_VALUE`**
⇒ `1e30` / `10^30` / `2^100` 这类算式全被钉死成 9223372036854775807。

## 修法

**新增一条 BigDecimal/BigInteger 版的算式求值**，`parseQuantity` 的算式分支改走它（**不要动 `parse()` 本身**，
配置界面与阈值的 long 行为必须一字不变）：

### A. `util/NumberExpr.java`
1. 新增私有求值器 `private static BigDecimal evalExprBig(String text)`，**语法与现有 `evalExpr` 完全一致**：
   - 预处理：trim → `toUpperCase` → `LONG.MAX/LONGMAX/INFINITE/INF → MAX` → `MAX` 用哨兵字符保护
     → `×`/`X` → `*`、`÷` → `/`
   - 末尾单位后缀 `K/M/G/T/P/E`（1e3 … 1e18）
   - 文法：`+ - * /`、右结合 `^`、括号、一元 `+/-`、`MAX` 常量、数字（含科学计数 `1e30`）
   - **原子用 `BigDecimal`**（`new BigDecimal(字面量)` 能吃 `1e30`）
   - `^`：若指数是**非负整数且 ≤ 100000** → `base.pow(n)`；
     否则（分数指数或超大指数）→ 退回 `BigDecimal.valueOf(Math.pow(base.doubleValue(), exp))`
   - `/`：`divide(rhs, 0, RoundingMode.DOWN)`（截断，与旧的"double 截 long"同口径）
   - 结果为负或非有限 → 抛 `NumberFormatException`（沿用现有文案）
2. `parseQuantity(text, lock)` 的算式分支改成：
   ```java
   BigDecimal v = evalExprBig(t);
   BigInteger value = v.toBigInteger();      // 截断取整
   if (value.signum() < 0) throw new NumberFormatException("数量不能为负");
   if (lock) {
       BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
       if (value.compareTo(max) > 0) return new Quantity(max, true);   // ← 锁定才夹（sensei 原始要求）
   }
   return new Quantity(value, false);        // ← 不锁定时允许超过 Long.MAX
   ```
   ⚠ **行为要求**：**不锁定**时算式结果可以远超 `Long.MAX`（这是本次要修的）；
   **锁定**时仍按 sensei 原始要求夹到 `Long.MAX_VALUE` 并置 `clamped=true`（界面会提示回退）。
3. 纯数字分支**保持不变**（已经是 BigInteger，本来就能超）。

### B. 不要动
`parse(String)` / `evalExpr(String)`（double 版）—— 配置界面、阈值、其它调用方继续用它。
S5/S5b 的锁定与黑洞语义、S3 的单物品阈值（它本来就是 long 字段，本次不动）。
不新增界面控件、不新增语言 key（现有的 `qty_clamped` 提示继续用）。

---
# S10 · 三类改动：非物品取消无限要能弹 + 物质球灌容器 + 列表手势重排（**本回合只做这三件**）

## 背景（已读代码）

- `network/Mode2ConfigPacket.handleToggleInfinite()`：
  `if (wasInfinite && committed.signum() > 0 && key instanceof AEItemKey itemKey)` —— **只处理物品**，
  流体/气体/化学物取消无限时**什么都不吐**（sensei 实测）
- `item/MatterBallItem`：`makeBall(AEItemKey, BigInteger)`、`getKey() → AEItemKey`、
  只有 `use()`（右键进玩家背包）**没有 `useOn()`** ⇒ 非物品既打不了包也送不出去

---

## A. 物质球支持**任意 AEKey**

`item/MatterBallItem.java`：
1. `makeBall(AEItemKey itemKey, BigInteger amount)` → **`makeBall(AEKey key, BigInteger amount)`**
   （NBT 里本来存的就是 `AEKey.toTagGeneric()`，没有格式变化，**旧球仍然能读**）。
2. `getKey(ItemStack ball)` → **返回 `AEKey`**（不再是 `AEItemKey`）。
   需要物品专用的地方另加 `public static @Nullable AEItemKey getItemKey(ItemStack ball)`
   （内部 `getKey(...) instanceof AEItemKey k ? k : null`）。
3. **改完把所有调用方一起改**（自己 grep `MatterBallItem.`）：
   - `network/Mode2ConfigPacket.outputOrBall(...)`
   - `cell/UnlimitedCellInventory` 里"存入物质球自动解包"那段（它用 `innerKey` 当 AEKey 用，天然通用）
   - `MatterBallItem.use()`（进背包那段是物品专用，用 `getItemKey`）
4. tooltip 照旧用 `key.getDisplayName()`（流体/气体也有显示名）。

## B. 取消无限：**非物品也要弹**（打包成物质球）

`network/Mode2ConfigPacket.handleToggleInfinite()` 与 `outputOrBall()`：
1. 去掉 `key instanceof AEItemKey` 的限制：任何 `key` 只要 `committed > 0` 就走输出。
2. 把 `outputOrBall(player, itemKey, committed)` 改成 `outputOrBall(player, key, committed)`（收 `AEKey`）：
   - **物品**：保持现有逻辑（数量 ≤ 背包容量 → 原样进背包/溢出掉落；超过 → 打包成物质球）
   - **非物品（流体/气体/化学物）**：**一律打包成物质球**交给玩家
     （背包塞不进就给实体掉落，与现有"背包满 → 掉 1 个球"一致）
3. 调试日志（现有的两条）保持可用，key 用 `AEKey` 打印。

## C. 物质球：`Shift+右键容器` → 把内容物**全部灌进去**，灌不下的报失败

`item/MatterBallItem.java` 新增 `useOn(UseOnContext)`：
1. 条件：`player.isShiftKeyDown()`（**普通右键保留原行为：物品进背包** —— 别删，物品还用得上；
   如果 sensei 之后说要删，再删）。
2. 目标方块按 key 类型取能力（**三种都要试，能用的就用**）：
   - 物品 → `net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER`
   - 流体 → `ForgeCapabilities.FLUID_HANDLER`
   - 气体/化学物 → 复用本模组已有的 `com.ae2addon.compat.MekanismGasCompat`
     （**先读它现有 API**，别自己另写一套；拿不到能力就报"该容器不支持这种物质"）
3. 灌入：循环把球内数量送到目标（每次取一个合理的块，注意各能力的 int 限制与返回值），
   累计"实际灌入量"；`BigInteger` 全程不要截断。
4. 结果分三种，全部走聊天栏 + 语言 key：
   - **全部灌入** → 消耗球 + 成功提示
   - **部分灌入** → 更新球内剩余数量 + **失败提示写清"还有 X 没灌进去"**
   - **一点都没灌进去** → 提示失败（球不动）
5. ⚠ 只在**服务端**做，客户端 `return InteractionResult.SUCCESS` 即可；
   不要产生海量掉落物（本模组的既有约束）。

## D. mode2 列表手势重排（防误触）

`gui/Mode2ConfigScreen.java` 面板条目区域：
| 手势 | 新行为 |
|---|---|
| **普通左键** | **什么都不做**（原来是"切无限"，容易误触 —— sensei 要求挪走） |
| **Shift + 左键** | **切无限**（原"普通左键"的行为） |
| **Shift + 右键** | **切黑名单**（原"Shift+左键"的行为） |
| 右键 | 单物品阈值（不变） |
| Ctrl + 左键 | 元件内数量（不变） |

- 判断顺序：`Ctrl+左键` → `Shift+右键` → `Shift+左键` → `右键` → 左键无操作。
  ⚠ 记得 `mouseClicked` 的 `button` 参数：右键是 `1`。
- **规则条（tag/mod chips）的手势不要动**（右键=切规则模式、Shift+左键=删规则）。
- 更新界面上的手势提示文案（现有的 `qty_tip` / 面板底部小字），
  改成：`§7条目上：Shift+左键切无限 / Shift+右键切黑名单 / 右键设单物品阈值 / Ctrl+左键设元件内数量`
  （`zh_cn` 与 `en_us` 都要改）。

## E. 语言文件（zh_cn / en_us 都要）
```
gui.ae2addon.matter_ball.pour_ok      = "§a已把 %s ×%s 全部灌入容器"
gui.ae2addon.matter_ball.pour_partial = "§e容器只收下了 %s ×%s，还有 %s 留在球里"
gui.ae2addon.matter_ball.pour_none    = "§c该容器一点都收不下（或不是对应的容器）"
gui.ae2addon.matter_ball.pour_unsup   = "§c该容器不支持这种物质"
gui.ae2addon.matter_ball.hint         = "§7右键：物品进背包　·　Shift+右键容器：全部灌入容器"
```

## F. 不要动
S1～S9 的行为（尤其 S5b 锁定语义、S7 的 `ca` 累加、S8 的格式化、S9 的大数算式）。

---
# S11 · mode2 界面文案与遮挡收尾（**本回合只做这四件**）

## 1. 提示行合并：删「条目上：」那条，内容并到列表下方那条

- 列表下方那条 = `gui.ae2addon.mode2.hint_bar`
  现在：`§7Shift+左键切无限  |  Shift+右键切黑名单  |  滚轮滚动`
  **改成**（即原 `qty_tip` 里"条目上："**之后**的那段文字）：
  ```
  §7Shift+左键切无限  |  Shift+右键切黑名单  |  右键设单物品阈值  |  Ctrl+左键设元件内数量
  ```
- **删掉**单独画 `gui.ae2addon.mode2.qty_tip` 的那一段（`Mode2ConfigScreen` 里取 `tip` 并按
  `W-20` 缩放再 `g.drawString` 的那几行，约 L716-721），连它的布局计算一起删干净。
- `qty_tip` / `item_thr_tip` 这两个语言 key 可以留着不用（别删 key 也行，删了也行，**只要界面上不再出现"条目上："**）。

## 2. 设置数量/阈值的输入框移到**物品栏上方**（现在压在物品栏槽位上）

现状：`itemThrInput` 与 `quantityInput` 都在 `topPos + 184`，而玩家物品栏槽位在 `166..242`
⇒ **遮挡**。改法（**扩高 GUI 让出空档**）：

1. `H`：`250` → **`276`**
2. `gui/Mode2ConfigMenu`：玩家背包槽 `166 + r*18` → **`192 + r*18`**；快捷栏 `224` → **`250`**
   （菜单是客户端服务端共用的同一个类，改一处两边一致 ✓）
3. **两个输入框（单物品阈值 `itemThrInput`、元件内数量 `quantityInput`）都移到
   `topPos + 152`**（高 16 → 占 152..168），按钮（√/✕、锁定开关）跟它们同一行、放在右側。
   152..168 这段正好在面板底（`PANEL_Y+PANEL_H = 150`）之下、物品栏标题（`H-94 = 182`）之上 ✓
4. 改完**自己核一遍**：输入框/按钮与面板（`.0..150`）、物品栏标题（182）、槽位（192+）都**不许重叠**；
   小窗口下（GUI 缩放较大、可用高度不足）也不要越界。

## 3. 「批量无限」标题带上删除手势

- `gui.ae2addon.mode2.batch_header`：`§6批量无限:` → **`§6批量无限（Shift+左键删除）:`**
  （括号在"批量无限"之后、冒号之前）

## 4. 删掉规则模式那行提示文字

- **删掉**画 `gui.ae2addon.mode2.rule_mode_tip`
  （`§7Shift+左键删除规则；右键切换该规则的无限模式（立即/触碰）`）的那一行
  （`Mode2ConfigScreen` 里 `g.drawString(... rule_mode_tip ..., topPos + 39, ...)`）。
  其中"Shift+左键删除"已经按第 3 条并进标题，**"右键切换该规则无限模式"这部分按 sensei 要求直接删掉**。
- 规则条上的 `[立即]` / `[触碰]` 标签**保留**（它们仍然表示模式）。

## 不要动
规则条与列表的**手势行为**（本轮只改文案与布局）、S1～S10 的逻辑。

---
# S12 · 收尾：元件编辑器配方 + 撤诊断 + 清僵尸 key（**本回合只做这三件**）

## A. 新配方：元件编辑器

新建 `src/main/resources/data/ae2addon/recipes/cell_editor.json`，**内容照抄下面这份**（格式与本模组
`config_card.json` 一致：`minecraft:crafting_shapeless` + `group: ae2addon`）：

```json
{
  "type": "minecraft:crafting_shapeless",
  "group": "ae2addon",
  "ingredients": [
    {
      "item": "ae2:memory_card"
    },
    {
      "item": "ae2addon:config_card"
    },
    {
      "item": "minecraft:ender_pearl"
    }
  ],
  "result": {
    "item": "ae2addon:cell_editor"
  }
}
```

⚠ 先确认 `ae2addon:config_card` 这个物品 id 真的存在（本模组 `ModItems` 里注册的是 `config_card`
对应的那个物品），`ae2:memory_card` 是 AE2 原版物品。**id 写错配方会静默失效**，务必核对。

## B. 撤掉诊断代码（**保留所有正式逻辑**）

要撤的（都是这几天为了定位问题临时加的诊断）：
1. **删文件** `src/main/java/com/ae2addon/mixin/ListCraftingInventoryMixin.java`
2. **删文件** `src/main/java/com/ae2addon/crafting/CpuStorageLedger.java`（只被上面那个 mixin 与
   `CraftingCpuLogicMixin` 的 `dumpSummary()` 调用用）
3. `src/main/resources/ae2addon.mixins.json`：删掉 `"ListCraftingInventoryMixin"` 这一行
4. `mixin/CraftingCpuLogicMixin.java` 里删掉：
   - `CpuStorageLedger.dumpSummary()` 的调用（连那段 try/catch）
   - `@Inject(method = "storeItems", at = @At("RETURN"))` 的 `ae2addon$diagStoreItemsAfter` 整个方法
   - `@Unique private java.util.Map<AEKey, Long> ae2addon$dumpNetBefore;` 字段
   - `ae2addon$diagStoreItemsBefore` 里"⓪ 倒出前记录网络存量"那一段（填 `ae2addon$dumpNetBefore` 的）
   - `storeItems 倒出前` 那条诊断日志（那一段 try/catch）
   - `@Unique` 的 `ae2addon$suppressReinject*` 里那条"第N次"日志可以留（它 debug 才打）；
     **`ae2addon$guardSpuriousReinject` 这个 redirect 本身必须保留**（它是正式修复）
5. ❗ **绝对不能删的正式逻辑**（删了就是回退 bug）：
   - `ae2addon$diagStoreItemsBefore` 里「**① 按『合成已消耗』清理预提取的输入**」那一整段
     （`ae2addon$preExtractedInputs` 的清理）—— 这是 v345/v346 的修复
   - `ae2addon$captureInitialExtract`（`tryExtractInitialItems` 的 redirect + 台账）
   - `@Unique private KeyCounter[] ae2addon$cachedInputsHandedOut;` 与
     `ae2addon$guardSpuriousReinject`（v352 修复）
   - `mixin/CraftingCpuHelperMixin.java` + `crafting/SeedConsumptionLedger.java`（v355 净交付补偿）
   - `ae2addon$seedEatenCompensation` 与 `ae2addon$seedEatenAccounted`
   删完后**自己 grep 一遍** `ListCraftingInventoryMixin|CpuStorageLedger|dumpNetBefore|diagStoreItemsAfter`
   确认没有残留引用。

## C. 清理僵尸语言 key

`assets/ae2addon/lang/zh_cn.json` 与 `en_us.json` 里删掉这三个**已无任何代码引用**的 key
（删之前自己 grep 全库确认确实没人用）：
- `gui.ae2addon.mode2.qty_tip`
- `gui.ae2addon.mode2.item_thr_tip`
- `gui.ae2addon.mode2.rule_mode_tip`

⚠ 删完两个 JSON 仍然必须是合法 JSON（末尾逗号别留），并**保持其余 key 与顺序不变**。

## D. 不要动
S1～S11 的行为与界面。

---
# S13 · 物质球灌装支持魔力/魔源 + 版本号升 2.0.0（**本回合只做这两件**）

## A. 物质球「Shift+右键容器」支持魔力池与魔源罐

sensei 实测：**物质球灌不进魔力池（Botania）和魔源罐（Ars Nouveau）**，报"该容器不支持这种物质"。
原因：现在 `MatterBallItem.supports()/pour()` 只分三条路 —— 物品 → `ITEM_HANDLER`、
流体 → `FLUID_HANDLER`、其余全丢给 Mekanism 化学物。**魔力/魔源走不进去**。

### A0. 已取证的接口（**读 jar 字节码拿到的，照这个写**）

```
vazkii.botania.api.mana.ManaReceiver            (interface)
    int  getCurrentMana()
    boolean isFull()
    void receiveMana(int)          ← 返回 void！收了多少只能靠差值
    boolean canReceiveManaFromBursts()
vazkii.botania.api.mana.ManaPool extends ManaReceiver
    int  getMaxMana()
    boolean isOutputtingPower()
vazkii.botania.common.block.block_entity.mana.ManaPoolBlockEntity
    extends BotaniaBlockEntity implements ManaPool, ...     ← 所以魔力池的 BE 就是 ManaPool ✓

com.hollingsworth.arsnouveau.api.source.ISourceTile   (interface)
    int  getSource()
    int  getMaxSource()
    int  addSource(int)            ← 返回语义不明，**不许依赖返回值**
    int  removeSource(int)
    boolean canAcceptSource()
    int  getTransferRate()
com.hollingsworth.arsnouveau.api.source.AbstractSourceMachine implements ISourceTile
com.hollingsworth.arsnouveau.common.block.tile.SourceJarTile extends AbstractSourceMachine  ← 魔源罐 ✓
```

### A1. `compat/AeResourceKeys` 加两个公开判定（**必须用单例相等，不能用 instanceof**）

类都是反射加载的，所以判断"这个 AEKey 是不是魔力/魔源"只能拿**单例比较**：
```java
/** 这个 key 是不是「魔力」（Applied Botanics）；没装桥 → false */
public static boolean isManaKey(AEKey key)   { AEKey k = key(MANA_KEY_CLASS);   return k != null && k.equals(key); }
/** 这个 key 是不是「魔源」（Ars Énergistique）；没装桥 → false */
public static boolean isSourceKey(AEKey key) { AEKey k = key(SOURCE_KEY_CLASS); return k != null && k.equals(key); }
```
（`key(String)` 已存在且带缓存，直接用。）

### A2. `MatterBallItem.supports(...)`

在 Mekanism 那条之前插入两条判定（**全部反射，类/lookup 失败就返回 false，绝不抛**）：
- `AeResourceKeys.isManaKey(key)` → target 是不是 `vazkii.botania.api.mana.ManaReceiver` 的实例
  （**BE 判定不到时，再试方块** `target.getBlockState().getBlock()` —— Botania 有些接收器实现挂在 block 上）
- `AeResourceKeys.isSourceKey(key)` → target 是不是 `com.hollingsworth.arsnouveau.api.source.ISourceTile` 的实例

### A3. `MatterBallItem.pour(...)`

新增两条分支（与物品/流体分支平级）。**两条都必须"插前读一次、插后读一次，用差值算实际接收量"**
—— 不要相信方法的返回值（Botania 是 void，Ars 的 `addSource` 语义没取证）。

- **魔力**：
  - 先 `isFull()` → true 直接算作收不下
  - 上限：是 `ManaPool` 就 `room = getMaxMana() - getCurrentMana()`；不是 pool（比如魔力散布器）
    就没有 `getMaxMana` → 退化成"直接试给"，靠差值判定
  - 单次最多给 `Integer.MAX_VALUE`（接口只吃 int），循环给；**每次给完重读 `getCurrentMana()` 算差值**，
    差值为 0 就停（不然会空转 4096 次）
- **魔源**：
  - 先 `canAcceptSource()` → false 直接算作收不下
  - `room = getMaxSource() - getSource()`；单次最多 `Integer.MAX_VALUE`；每次用 `getSource()` 差值确认
- 两者都要**受现有的"单次最多 4096 次调用"上限约束**（那是防卡服的，别动）
- 数量是 `BigInteger`，接口是 `int` ⇒ 内部一律**转成"本次能给的 int 块"**再调；**不许把 BigInteger 截断后
  当作总量**（截断会让"还有多少没灌进去"算错）
- 结果仍走现有三条文案（全灌入 / 部分灌入 / 一点没进）+ 不支持那条，**不新增语言 key**

### A4. 顺手把 tooltip 提示补一句（可选，做了更好）
`gui.ae2addon.matter_ball.hint` 现在是
`§7右键：物品进背包　·　Shift+右键容器：全部灌入容器`
→ 改成 `§7右键：物品进背包　·　Shift+右键容器：全部灌入（含流体/气体/魔力/魔源）`
（`zh_cn` + `en_us` 都要改；en 对应英文。）

## B. 版本号 1.3.0 → 2.0.0（sensei：这一版定稿是 2.0.0）

- `src/main/resources/META-INF/mods.toml`：`version="1.3.0"` → **`version="2.0.0"`**
- `build.gradle` manifest：`Implementation-Version: "1.2.2"` → **`"2.0.0"`**
  （`Specification-Version: "1"` **别动**，那是 spec 版本不是 mod 版本）
- `README.md`：`build/libs/ae2-addon-1.3.0.jar` → **`ae2-addon-2.0.0.jar`**
  （README 里若还有别处写 1.3.0/1.2.2 也一并改；**README 的正文内容不要重写**）
- `RELEASE_NOTE.md` **本轮不要动**（由我手写，别让 codex 改）

⚠ 改完自己 grep 一遍 `1\.3\.0|1\.2\.2`，确认该改的都改了、不该改的没动。

## C. 不要动
S1～S12 的行为与界面；`BotaniaCompat` / `ArsNouveauCompat`（配方兼容层）一行都不要碰。




























**需求（sensei 原话）**：「添加一键格式化按钮，按一次后弹出输入栏，需输入确认（或 enter）后再按 √ 按钮，
后销毁 mode1、mode2 所有数据」。已确认范围：**只清当前这只元件**（不是整张存档）。

**交互**（在 `Mode2ConfigScreen` 里，仅 mode2 界面用）：
1. 界面上加一个按钮 `§c格式化`。
2. 点击后**原地弹出一行确认栏**：一个 `EditBox` + `√` 按钮 + `✕` 取消按钮。
   输入框 hint 提示：`输入 DELETE 后按回车确认`。
3. 在输入框里按 **回车**：若文本（去空白、忽略大小写）等于 `DELETE`（也接受中文 `确认` 或 `删除`）
   → 进入「已确认」态（`√` 按钮变绿提示可点）；否则提示不匹配、保持未确认态。
4. **再按 `√`** 才真正执行；未确认时点 `√` 只提示先确认。
5. `✕` 收起确认栏、不发包。

**协议**：复用现有管线，**不要改 encode/decode**。
用 `new Mode2ConfigPacket(14, 0L, "")`（构造器 `Mode2ConfigPacket(int type, long threshold, String itemId)`
会把 type 设成 14；encode 的 else 分支会写 long+Utf，decode 的末尾兜底分支正好读回，无需改动）。
在 `Mode2ConfigPacket.handle()` 的服务端分支里加：

```java
} else if (p.type == 14) {
    // 一键格式化：销毁本元件 mode1 + mode2 全部数据
    // ⚠ 不要只用 player.getMainHandItem() 找元件：将来界面可能由「元件编辑器」
    //   从驱动器里打开，那时元件不在手上。优先用当前打开的 Mode2ConfigMenu 里那只栈。
    ItemStack target = stack;
    if (player.containerMenu instanceof com.ae2addon.gui.Mode2ConfigMenu m) {
        ItemStack s = m.getCellStack();
        if (s != null && !s.isEmpty() && s.getItem() instanceof UniversalStorageCell) target = s;
    }
    UnlimitedCellInventory finv = (target == stack) ? inv : new UnlimitedCellInventory(target, null);
    finv.wipeAllData();
    player.sendSystemMessage(Component.translatable("gui.ae2addon.mode2.format_done"));
    sendRuleData(finv, player);
    sendBlacklistData(finv, player);
    sendPanelRefresh(finv, player);
    return;
}
```

**`UnlimitedCellInventory` 新增方法**：

```java
/**
 * 一键格式化：销毁本元件 mode1 + mode2 的全部数据。
 * ⚠ 保留 mode 3 的数据（m3）—— sensei 只说了 mode1/mode2。
 * ⚠ uuid 保留（否则元件变成新身份，旧 CellData 会留在存档里发霉）。
 */
public void wipeAllData() {
    s1.clear(); s2.clear(); wl.clear(); ul.clear(); ca.clear();
    tags.clear(); mods.clear(); ruleTouched.clear(); blacklist.clear();
    // 注意：m3 不清
    ruleInstant = true;
    workMode = 1;
    thr = 65536L;
    CompoundTag t = cellItem.getOrCreateTag();
    t.putLong("thr", thr);
    t.putInt("wm", workMode);
    // 摘要计数也回到初始（save() 会调 updateSummary 重算）
    t.remove("_b"); t.remove("_b2"); t.remove("_t");
    dataDirty = true;
    save();
}
```
（若 S5 已实现逐条目「数量自定义 + 锁定」，这里也要一并清掉那些新字段。）

**语言文件**：`gui.ae2addon.mode2.format_done` 需要加进
`src/main/resources/assets/ae2addon/lang/zh_cn.json` 与 `en_us.json`
（现有 mode2 相关 key 在同一个文件里，照抄风格；**两个文件都要加**）。

**不要做**：不要动 `Mode1`/`Mode3` 的行为、不要改 `encode`/`decode`、
不要在格式化后删除 `uuid` 或 `CellDataSavedData` 里的条目（`save()` 覆盖即等于清空）。

---

## 后续切片（**除 S2 外不要实现**，仅作上下文）

- **S2** tag/mod **每条规则独立的无限模式**：现在是 `CellData.ruleInstant` 一个**全局布尔**管所有规则。
  要改成逐规则的「立即无限 / 触碰无限」标签（`Map<String,Integer>`，key 用 `"tag:"+name` / `"mod:"+name`），
  列表里右键规则行可切换，**永久保留除非删除**；旧存档要能按旧的全局布尔迁移。
- **S3** **单物品阈值**：`CellData` 加逐 key 阈值表，判定时**优先于全局 `thr`**（仅阈值工作模式）。
- **S4** **黑名单覆盖阈值模式 + 存入无限模式**（现在只在部分判定点生效）。
- **S5** **mode2 逐条目「存储数量自定义」+ 锁定**：逐 key 自定义「元件内数量」，
  支持算式输入；**锁定 = 数值本身被钉死**（后续存入/取出都不改变它），锁定时上限 `Long.MAX_VALUE`，超出自动回退。
- **S6** 新物品 **元件编辑器**：`shift+右键` 驱动器（无限级）绑定 → 对**空气**右键开 GUI →
  选驱动器里的一个万能无限元件 → 打开该元件的配置界面。

---

## 本仓库硬约束（违反会炸）

1. **不要跑 `gradle build` / 不要碰部署**。自检编译用：
   ```
   robocopy "D:\环境\ae2-addon\src" "D:\ae2addon-build\src" /MIR
   cd /d D:\ae2addon-build && gradlew.bat compileJava --offline --console=plain
   ```
   （`D:\ae2addon-build` 已加为可写目录；**编译通过即可**，jar/reobf/部署由爱丽丝做。）
2. **Mixin 的 `@At` 描述符逐字符匹配**：数组参数必须写 `[` 前缀，返回值/参数一个字符都不能差。
   改 mixin 后若不确定，先 `javap` 核对我们依赖的 AE2 方法签名。
3. **不要删除任何现有的诊断/自检日志**（尤其 `IntegratedCpuRingRenderer` 的 `logMarkerChecks()`）。
4. 中文注释保留、风格照抄邻近代吗；**新增可配项**（若有）必须同步改
   `client/gui/AE2AddonConfigScreen`（该界面是**手写登记**的，不会自动读 config）。
5. 尽量**小改动**：不要顺手重构无关代码、不要改公共 API 签名。
6. 不要在源码里写密钥/token/URL。
