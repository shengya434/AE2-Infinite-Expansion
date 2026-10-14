# 规格：R6 —— 收取页只留「左键切拒/允」，删掉多余热键

版本仍 2.0.1。**只改收取页的手势分派与文案，别碰别的。** 不要自己构建。

## 0. 需求（sensei 2026-10-02 实测后拍板）

「`Shift+左键` 清除显式设置」与「`Ctrl+右键` 切拒收/允许」这两个热键**没必要，删掉**。
收取页**只保留 `左键` = 切换 拒 ↔ 允**。

## 1. 要改

文件：`src/main/java/com/ae2addon/gui/Mode2ConfigScreen.java`（+ 两份 lang 的值）

1. 收取页列表的点击分支（现在约 L1257-1258）：
   ```java
   if ((button == 0 || (button == 1 && hasControlDown())) && row >= 0 && row < entries.size()) {
       AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(hasShiftDown() ? 22 : 7, ...));
   ```
   → 收窄成**只有 `button == 0 && !hasShiftDown()` 且不带任何 Ctrl/Shift 组合**时才发 **type 7**；
   `Ctrl+右键` 与 `Shift+左键` 两条分支**删掉**（在该区域上按它们应当什么都不做）。
   ⚠ 这条路径同时服务**物品视图**与**分类视图**，两个视图都要按新规则（别只改一个）。
2. **文案同步**：
   - `gui.ae2addon.mode2.hint_reject`：只留「左键切拒/允」（把"Shift+左键清除"那句删掉）；
   - 收取页的 tooltip（`renderRejectTooltip` 那条）里如果提了这两个手势，**同步删掉**；
   - 两份 lang（zh_cn / en_us）都要改。
3. **`type 22`（清除显式设置）**：**保留服务端分支与 packet 编号**（避免动编号引发别的风险），
   但界面上**不再有入口**。报告里明确写一句"type 22 现在没有界面入口"。
4. 存储页**一个字都不许动**（它本来就不认这两个键）。

## 2. 不许破坏

1. R5/R5b 已定的：两页共用存储面板与规则分类列表、收取页并上显式集合（抽空不消失）、
   `物品`→`存储`、配置页「返回」→子模式页、hint_bar 按页签分两套、分类行两页可点可滚 —— 一条都不许退化；
2. R3 死锁修复、R4 导航语义、拒收语义（只拦进不拦出 / 拒收优先于锁定 / 物质球内层拒收不吞球 / 锁定 `return amount`）不动；
3. S11 布局坐标、`H=276`、`extract`/insert 路径、`Mode2ConfigPacket` 旧 type 编号 全不动；
4. 两份 lang JSON 可解析。

## 3. 报告（中文）

1. 改动文件清单 + 摘要；
2. **收取页点击分派的最终条件**（代码原文一行）+ 说明 `Ctrl+右键`/`Shift+左键` 现在确实无动作（给出代码位置）；
3. `hint_reject` 与 tooltip 改前/改后的文案；
4. `type 22` 的现状说明；
5. 没确认的点列出来；`git diff --stat` 取不到就直说。
