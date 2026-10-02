# P3 第二轮实现记录

基线：只读 `.git/refs/heads/master` = `1cfa08ddd5d430e2c70f65ced1a442274cc8a27b`。用户给出的 OpenClaw 便携版 `git.exe` 在本沙箱中访问被拒绝，且 `.git` 只有读权限；本轮未提交。未运行 Gradle、未构建 jar、未部署、未运行游戏或 38 条自检。

## 改动

- `network/Mode2ConfigPacket.java`：24 种 type 的编解码；服务端对接白名单、规则、黑名单、数量、格式化等公开判定层方法；type 4 只使用 `getPanelItems()`；type 12/21 按 50 条分包；type 18 下发拒收状态和无限承诺额度。type 13 保留 JEI 直加语义，但没有 JEI 联动调用方。
- 格式化直接调用当前判定层 `wipeAllData()`。已逐行复核它会清理 `touched`、规则、黑名单、数量锁定和拒收，且保留 mode3 数据。
- `network/QuantityExpression.java`：type 17 数量文本在服务端解析，支持算术、括号、幂、科学计数、K/M/G/T/P/E 和 MAX；锁定时高于 `Long.MAX_VALUE` 的数会截到上限。
- `gui/RejectListState.java`：type 21 显式名单分块收件箱，提供已收/应收数量和超时状态。
- `gui/ModeSelectScreen.java`：显示显式拒收 on/off 条目；格式化须先输入 `DELETE`、回车或点 ✓ 确认，再点格式化按钮。
- `gui/Mode2ConfigScreen.java`：拒收页读 type 21；规则增删及每条 Instant/Touch，物品/规则/黑名单分类与 Shift 滚轮横向滚动；行内黑名单、数量及锁定、无限切换；组合原版物品 tooltip 与面板状态。
- `lang/en_us.lang`、`lang/zh_cn.lang`：只新增 9 个键，既有键未改。
- `P3-JAVAP.txt`：第 245 行以后追加第二轮实际 `javap -p` 签名证据。

## 24 type 对照

| type | 方向 | 当前处理 | UI 调用 |
|---|---|---|---|
| 0 | C→S | 全局阈值 | 有 |
| 1 | C→S | 加白名单 | 有 |
| 2 | C→S | 按 ID 移除白名单 | 旧版兼容，无 UI 调用 |
| 3 | C→S | 请求面板、规则、黑名单、拒收状态 | 有 |
| 4 | S→C | `getPanelItems()`，50 条分块 | 接收 |
| 5 | C→S | `togglePanelInfinite` | 有 |
| 6 | C→S | 工作模式 | 有 |
| 7 | C→S | 拒收切换 | 有 |
| 8 | C→S | 默认拒收 | 有 |
| 9 | S→C | ore/mod 两包规则及各自模式 | 接收 |
| 10 | C→S | `setRuleInstant` | 兼容，无 UI 调用 |
| 11 | C→S | 黑名单切换 | 有 |
| 12 | S→C | 黑名单，50 条分块 | 接收 |
| 13 | C→S | JEI 直加白名单 | JEI 未联动，无 UI 调用 |
| 14 | C→S | 格式化 | DELETE 确认后发送 |
| 15 | C→S | 单条规则模式 | 有 |
| 16 | C→S | 单物品阈值 | 有 |
| 17 | C→S | 数量文本及锁定 | 有 |
| 18 | S→C | 拒收状态及承诺额度 | 接收 |
| 19 | C→S | 加 ore/mod 规则 | 有 |
| 20 | C→S | 删 ore/mod 规则 | 有 |
| 21 | S→C | 显式 rejectOn/rejectOff，50 条分块 | 接收 |
| 22 | C→S | 清显式拒收覆盖 | 有 |
| 23 | C→S | 请求拒收状态 | 有 |

## 网络线程

- `network/SetCellModePacket.java:23`：服务端调度后改 `umode` 并重开界面。
- `network/RequestCellScreenPacket.java:22`：服务端调度后重开界面。
- `network/Mode2ConfigPacket.java:221`：服务端调度后执行所有判定层写入及状态下发。
- `network/Mode2ConfigPacket.java:335`：客户端调度后更新当前界面。

## 静态证据与验收

`P3-JAVAP.txt:245-298` 包含本轮新增的 `PacketBuffer` var-long/条目读写、`OreDictionary`、`Loader`、`GuiButton`、`GuiScreen.getItemToolTip`、`ItemStack` NBT/比较、`NBTTagCompound/NBTTagList` 和 `ResourceLocation.getNamespace` 签名。静态枚举检查确认 type 0–23 都有 encode/decode 分支。当前 38 条门（A11 + B12 + C5 + D10）没有运行；禁改的 `cell/`、`data/`、`util/`、`command/CommandCellSelfTest.java` 没有编辑，故本轮没有改动其断言或判定实现，但这不等于运行通过。

规格方仍需运行构建/reobf、`/ae2addoncelltest`（目标 PASS 38 / FAIL 0）、专用服务器及游戏内点击回归。建议逐项核对：抽空后拒收名单仍出现、50 条以上三类列表分块、零数量带阈值的条目、规则增删/模式、黑名单、数量锁定、DELETE 格式化、断线重连。

## 未完成或未验证

- JEI 联动未做，type 13 无界面发送方；type 2/10 也是协议兼容入口，无界面发送方。
- 没有构建、reobf、游戏画面、专用服务器日志或 38 条自检结果，编译和运行行为尚未实证。
- 缺包只显示已收数量和刷新入口，没有重传机制，符合移植规格的分包决定。
- `.git` 写入受限，未生成本轮 commit。
