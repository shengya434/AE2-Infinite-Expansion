# P3 收尾实现记录

基线按交接指定为 `a085a2f`。本轮没有调用 git、没有碰 `.git`、没有运行 Gradle、没有制作或部署 jar。前两轮的 38/0 和实机验收属于交接事实，本轮未重新执行。

## 修改文件（当前总行数）

| 文件 | 行数 | 本轮内容 |
| --- | ---: | --- |
| `src/main/java/com/ae2addon/gui/Mode2ConfigScreen.java` | 622 | 白名单移除手势、全规则切换、面板/黑名单/拒收分块重请求与提示 |
| `src/main/java/com/ae2addon/gui/ModeSelectScreen.java` | 176 | 拒收分块重请求与提示 |
| `src/main/java/com/ae2addon/gui/RejectListState.java` | 58 | 拒收分块保留、重请求状态 |
| `src/main/java/com/ae2addon/gui/ChunkRetry.java` | 30 | 4 秒超时、最多 3 次重请求的共用计数器 |
| `src/main/java/com/ae2addon/network/Mode2ConfigPacket.java` | 353 | type 4 白名单标记、type 18 全规则状态、空物品 type 11 拉黑名单 |
| `src/main/resources/assets/ae2addon/lang/en_us.lang` | 96 | 新增 6 个键 |
| `src/main/resources/assets/ae2addon/lang/zh_cn.lang` | 96 | 同上 |
| `P3-JAVAP.txt` | 314 | 本轮 API 签名摘录 |
| `P3-CLOSEOUT.md` | 31 | 本轮交接 |

## 三项实现

1. **type 2 白名单移除**：type 4 每个面板条目增加 `whitelisted` 位，由服务端 `isWhitelisted` 填写。物品行以 `W` 和 tooltip 标明白名单；仅对白名单行，Ctrl+右键发送注册 ID 的 type 2。服务端沿用既有按 ID 遍历并调用 `removeWhitelist(IAEItemStack)` 的处理，同一注册 ID 下的所有白名单变体会一并移除。服务端回送面板，刷新 `W` 标记。
2. **type 10 全规则模式**：规则栏新增“全立即/全触碰”按钮，点击发送 type 10。type 18 回传 `isRuleInstant()`，用于按钮状态。服务端沿用 `setRuleInstant(boolean)`，随后 `sendState` 下发两类 type 9；界面清空并重填规则模式表，逐条 `[I]/[T]` 显示随之更新。
3. **分块超时**：type 4/12/21 从首个收到的块开始计时，即使第 0 块丢失也能接收其余块；4 秒无新块则分别发送 type 3/空物品 type 11/type 23，最多 3 次。空物品 type 11 只回传黑名单，不执行切换。相同总块数的重发块合并到已有进度；收齐后保留完整块集，避免迟到的重发尾包重新制造半截列表。第 3 次重发后再过 4 秒仍不齐，显示红色“自动重试已停止，点 R 刷新”；现有 R 可恢复重试预算。ModeSelect 的拒收列表也执行同一策略。

## 边界与验证

- type 13 **未使用/保留**：服务端 JEI 直加白名单语义仍在，没有 JEI 联动。GUI 与 lang 中没有 JEI 入口或提示。
- 新增列表元数据只随网络 type 4 下发；没有向物品 NBT 写列表。没有改动 `cell/`、`data/`、`util/`、自检断言或其他禁止文件；lang 仅增加新键。服务端所有容器定位、判定层修改和状态回送继续位于 `Mode2ConfigPacket.java:226` 的服务端 `addScheduledTask` 内；客户端界面收包位于同文件 `:345` 的客户端 `addScheduledTask` 内。
- 本轮使用的 1.12.2 GUI/NBT API 签名见 `P3-JAVAP.txt:300-314`：`GuiScreen.drawHoveringText(String,int,int)`、`isCtrlKeyDown()`、`GuiButton` 构造器与 `displayString`、`NBTTagCompound.setBoolean/getBoolean`、`ResourceLocation.toString()`。当前 `javap` 对缓存映射 jar 打印了这些签名，但进程末尾另报 `AccessDeniedException`，因此没有将它记作完整成功的命令；同文件较早的证据也包含这些接口。
- 静态核对：所有 `PanelEntry` 构造点已补齐新布尔位；两份 lang 新键均存在且各自无重复键；GUI/lang 搜索无 JEI 字样。未运行 Gradle、reobf、38 条自检、实机 GUI 或模拟丢包，因此本轮代码的编译及运行表现未验证。
- 分块包没有传输代号。同总块数的列表在服务端变化期间若跨两次响应交错，客户端可能短暂合并新旧块；手动 R 会对已收齐的列表重新开始一次完整拉取。这一边界未做实机验证。
