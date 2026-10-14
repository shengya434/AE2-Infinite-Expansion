# 规格：R4 —— 元件界面导航状态机修好（主模式 ↔ 子模式 ↔ 重开落点）

版本仍 2.0.1。**只修导航，不碰存储/拒收/配方逻辑。** 不要自己构建（爱丽丝跑）。

## 0. 现象（sensei 实测）

1. mode 2 的元件：**选完子模式后重新打开 GUI，又回到"子模式选择页"**；
   在**主模式界面**点 mode 2 会**直接退出界面**，要再开一次才进得去子模式页（返回主模式界面同样）。
2. 从**元件编辑器**（或驱动器）进配置时：**无法切换主模式**；点子模式页的「返回」会**退出界面**，
   重新进入**仍在子模式选择页**（模式根本没变）。

## 1. 根因（已读码取证）

1. `network/SetCellModePacket.handle` 找元件栈的顺序是：`player.containerMenu instanceof ModeSelectMenu` →
   手持主手 → 副手。**在 `Mode2ConfigMenu` / 元件编辑器（驱动器）路径下都找不到元件 → 静默 `return`** ⇒
   编辑器/驱动器路径下"改模式"其实**没生效**（现象 2 的直接原因）。
2. 改完模式后**什么都不做**：不换菜单、不关界面 ⇒ 客户端停在旧界面；而菜单类型与模式不匹配 ⇒
   玩家看到"退出界面 / 要再开一次"（现象 1）。
3. `Mode2ConfigScreen` 子模式页的「返回」= `SetCellModePacket(0)`（**0 = 循环到下一个模式**）+ `onClose()`
   ⇒ 既退界面、又偷偷改了模式（现象 2）。
4. 主模式按钮（mode 1/2/3）**只存在于 `ModeSelectScreen`**，而 mode 2 的元件**只会打开 `Mode2ConfigScreen`**
   ⇒ 主模式根本无从切换（死结）。

## 2. 要改成

### F1 找元件栈：抽一个共用解析器（与 `Mode2ConfigPacket` 对齐）
```java
/** 按当前打开的界面/手持情况解析"正在被配置的元件栈"；同时带回驱动器上下文（元件编辑器路径要）。 */
record CellTarget(ItemStack stack, InfiniteDriveBE drive, int driveSlot) {}
static @Nullable CellTarget resolveCellTarget(ServerPlayer player)
```
顺序：
1. `player.containerMenu instanceof ModeSelectMenu m` → `m.getCellStack()` + `m.drive/m.driveSlot`
2. `player.containerMenu instanceof Mode2ConfigMenu m` → 同上（这两个菜单都要有取 drive/slot 的读方法；缺就补）
3. `player.containerMenu instanceof InfiniteDriveMenu m && m.isEditorMode()` → `m.getDrive()` + `m.findCellSlot(<玩家正在配置的元件>)`
   （元件编辑器路径：**具体怎么定位那一格**照 `UniversalStorageCell.openConfigFor` 现有写法抄，别另发明）
4. 手持主手 → 副手（`drive = null`）
5. 都没有 → **不许静默 return**：打 warn 日志 + 给玩家聊天提示（语言键见 F8）

`SetCellModePacket` 与 F4 的新包**都用这一个解析器**（别抄两份）。

### F2 改完模式后「就地换菜单」
- `SetCellModePacket.handle` 在 `inv.setMode(newMode)` 之后：调用
  `UniversalStorageCell.openConfigFor(player, target.stack(), target.drive(), target.driveSlot())`
  （该方法已按新 `umode` 分流：mode 2 → Mode2 菜单；mode 1/3 → ModeSelect 菜单）✓
  ⇒ 客户端就地切屏，不再"退出 + 需要重开"。
- ⚠ **编辑器/驱动器路径的 drive+slot 必须一起传进去**，否则新开的菜单会丢掉"元件在哪个槽"的上下文
  （这就是现在那个方法签名里 `drive/driveSlot` 的用途）。

### F3 「返回」= 回主模式界面（不改模式、不关界面）
- `Mode2ConfigScreen` 子模式页的「返回」按钮：**删掉** `SetCellModePacket(0)` + `onClose()`，
  改发 F4 的新包（目标 = `SELECT`）。
- 服务端收到后：用 F1 的解析器拿元件 → 调**新增的** `UniversalStorageCell.openSelectMenuFor(player, stack, drive, driveSlot)`
  —— 它**强制**打开 `ModeSelectMenu`（不按 umode 分流）✓
- 这样 **mode 2 的元件也能进主模式界面** ⇒ 死结解开（可以在那里点 mode 1/3 切走）✓
- `ModeSelectScreen` **不加**返回按钮（它是顶层，玩家 Esc 关闭）。

### F4 新包 `RequestCellScreenPacket`（或复用现成 type；你定，但要复用菜单有效性校验的思路）
- 载荷：目标界面枚举 `SELECT` / `CONFIG`；
  > **⚠ 2026-10-14 实际落地：`CONFIG` 那条被删了。**
  > 选「自定义无限」当场就进配置页（`SetCellModePacket` → `openConfigFor(..., newMode == 2)`），
  > 「从选择页不改模式直接进配置页」既没接完也用不上；枚举现在只有 `SELECT`（载荷形状未变）。
- 服务端：F1 解析元件 → 分别打开 ModeSelect 菜单 / 按 `openConfigFor` 打开；
- 解析失败 → warn + 聊天提示；
- **必须校验发包者确实打开了这个元件的界面**（照 `Mode2ConfigPacket` 现有做法抄，别放宽）。

### F5 重开落点：mode 2 **直接进配置页**
- `Mode2ConfigScreen` 的初始 `uiState` 默认改成 **1（配置页）**；
- **只有**"从主模式界面选 mode 2"这一次要落在**子模式页（uiState 0）**：
  用 `openScreen(...)` 的 buffer 带一个 `showWorkMode` 标志，客户端在菜单的 `fromNetwork` 里读出来传给 Screen
  （构造函数或 setter 都行）。⚠ **不要**用静态变量跨界面传状态——会在别的路径漏进去。
- 其它路径（手持右键元件 / 元件编辑器 / 驱动器）打开 mode 2 → **直接配置页** ✓

### F6 诊断（下一局把"界面为什么自己关"钉死）
- F1 解析 + F2/F4 打开菜单处打一行 **info**：
  `[cellscreen] menu=<菜单类名> src=<menu|drive|hand|offhand> newMode=<n> open=<目标菜单>`
- 在 `ModeSelectMenu.stillValid` / `Mode2ConfigMenu.stillValid` 里，**返回 false 时**打一行 warn：
  `[cellscreen] stillValid=false menu=<类名> cellEmpty=<bool> present=<bool>`
  （这两行先常态化留着，定位完再收；很便宜。）

### F7 不许破坏
- `Mode2ConfigPacket` 的所有现有 type 行为、找元件逻辑不变；
- 拒收（R1/R3，含 `RejectListState` 与 type 7/8/18/21/22/23）、格式化、规则/黑名单/阈值面板、
  元件编辑器绑定流程、驱动器正常打开路径 **一个都不许变**；
- insert/extract 语义不动；Mode2 的 S11 布局坐标与 ModeSelect 的 `H=240` 及原按钮坐标不变
  （F3 只改「返回」的行为，位置不动）。

### F8 语言键（zh_cn + en_us 各一份）
- `gui.ae2addon.cell.target_missing`：`找不到目标元件（请重新打开界面）` / `Target cell not found`
- 其它新按钮/提示文案（如需要）一并补，**别写死中文进代码**。

## 3. 报告要求（中文）

1. 改动文件清单 + 摘要；
2. F1 的解析顺序表（含**元件编辑器路径怎么定位那一格**的依据行号）；
3. F5 的落地方式（怎么保证"只有从主模式界面过来才显示子模式页"）+ `showWorkMode` 走的是哪条 buffer；
4. **行为预期表**：三条路径（手持 / 驱动器 / 元件编辑器）× 三个动作（打开 / 切主模式 / 点子模式页的返回）
   = 9 格，逐格写"预期落在哪个界面、模式有无变化"；
5. 没确认的点一律列出来；`git diff --stat` 取不到就直说。
