# P3 改了什么（实现方静态交付）

基线：`.git/refs/heads/master` 当前读到 `716b24da08801228b7ac0f3d0d5829a2d7196834`。本环境 `git` 不在 PATH，已检查的便携 Git 路径不可访问；`.git` 写探针返回“访问被拒绝”，所以尚不能提交。未运行 Gradle、未生成 jar、未部署、未进游戏。

用 `.git/index` 和对应 blob 做只读内容比较（统一 CRLF/LF 后）：原有跟踪文件仅 `AE2Addon.java`、`ItemUnlimitedCell.java`、两份 lang 有内容差异；`build.gradle`、`gradlew.bat`、`NOTICE.txt` 只有行尾差异，本次没有改写。禁改的 `cell/`、`data/`、`util/`、自检断言文件均与基线内容相同。

## 文件

| 文件 | 改动 | 1.12.2 / AE2-UEL `javap` 证据（见 [P3-JAVAP.txt](P3-JAVAP.txt)） |
|---|---|---|
| `src/main/java/com/ae2addon/AE2Addon.java` | preInit 建网络通道、注册 GUI handler，init 原有 AE2 cell 注册不动 | `NetworkRegistry` 33–34、238–239；`SimpleNetworkWrapper` 41–44 |
| `src/main/java/com/ae2addon/item/ItemUnlimitedCell.java` | 右键手持元件进入配置 | `Item.onItemRightClick` 3；`ActionResult` 6；`EntityLivingBase.getHeldItem` 13；`World.isRemote` 26；`EnumHand` 225–229 |
| `src/main/java/com/ae2addon/gui/CellScreens.java` | GUI ID、服务端开界面与双侧 GUI/menu 工厂 | `EntityPlayer.openGui` 23；`IGuiHandler` 38–39；`NetworkRegistry.registerGuiHandler/INSTANCE` 34、238–239；`ItemStack` 175–181 |
| `src/main/java/com/ae2addon/gui/ModeSelectMenu.java` | 零槽位菜单、活栈身份检查 | `Container` 85–92；`EntityPlayer.openContainer` 18；`ItemStack.EMPTY` 223 |
| `src/main/java/com/ae2addon/gui/Mode2ConfigMenu.java` | 36 个玩家背包槽位，禁用 vanilla Shift 搬运 | `Container` 85–92；`Slot` 95–100 |
| `src/main/java/com/ae2addon/gui/ModeSelectScreen.java` | 模式按钮、拒收默认值、已存储拒收项预览与刷新；格式化控件禁用 | `GuiContainer` 101–113；`GuiScreen` 114–132；`GuiButton` 133–135、240–243；`GuiTextField` 136–146；`Gui/FontRenderer` 147–158；`ResourceLocation/TextureManager` 166–171；`I18n` 172–173；`Mouse.getDWheel` 230–231 |
| `src/main/java/com/ae2addon/gui/Mode2ConfigScreen.java` | 工作模式、阈值、搜索、列表分包、物品阈值、拒收页、背包 Shift 加白名单、取消无限；未收齐分包显示进度 | `GuiContainer` 101–113；`GuiScreen` 114–132；`GuiButton/GuiTextField` 133–146、240–243；`Gui/FontRenderer` 147–158；`ItemStack` 175–181；`IForgeRegistryEntry.getRegistryName` 195–196；`Mouse` 230–231 |
| `src/main/java/com/ae2addon/gui/ThresholdExpression.java` | Java 8 阈值算式与 K/M/G/T/P/E 单位解析 | 纯 JDK，无 Minecraft API |
| `src/main/java/com/ae2addon/gui/RejectListState.java` | 预留的 type 21 拒收分包收件箱及超时状态；当前服务端缺枚举接口，尚未接入收发 | `ItemStack` 175–181 |
| `src/main/java/com/ae2addon/network/CellNetwork.java` | Forge 单通道；C→S 号 0/1/2，S→C 号 50 | `NetworkRegistry` 33；`SimpleNetworkWrapper` 41–44 |
| `src/main/java/com/ae2addon/network/CellScreenTarget.java` | 验证当前菜单与活栈，阻止远程包编辑别的元件 | `EntityPlayer.openContainer` 18；`Container.canInteractWith` 92 |
| `src/main/java/com/ae2addon/network/SetCellModePacket.java` | C→S `umode`，切换后服务端重开对应界面 | `IMessage/IMessageHandler/MessageContext` 45–51；`PacketBuffer` 52–53 与继承的 `ByteBuf` 232–237；`MinecraftServer.addScheduledTask` 27–29 |
| `src/main/java/com/ae2addon/network/RequestCellScreenPacket.java` | C→S 导航到模式选择页 | 同上；`EntityPlayer.openGui` 23 |
| `src/main/java/com/ae2addon/network/Mode2ConfigPacket.java` | 一个多态包，已启用 0/1/3/4/5/6/7/8/16/18/22/23；面板 50 条分包，大数用 byte[] | `PacketBuffer` 52–82 与 `ByteBuf` 232–237；`IAEItemStack/IAEStack/IStorageChannel` 197–208；`AEItemStack` 209–216；`Minecraft.addScheduledTask` 160–165；`Minecraft.currentScreen/getMinecraft` 218–221 |
| `src/main/resources/assets/ae2addon/lang/{en_us,zh_cn}.lang` | 新增盘点 A 节所列 79 个展开后键与 1 个分包进度键；原有元件名称键未改 | `I18n.format` 172–173 |
| `src/main/resources/assets/ae2addon/textures/gui/cell_panel.png` | 256×256 RGBA 面板底纹；两界面实际绑定 | `ResourceLocation` 168–171；`TextureManager.bindTexture` 166–167；`Gui.drawTexturedModalRect` 147–152 |
| `P3-JAVAP.txt` | 已执行的 `javap -p` 签名摘录 | 原始签名与行号 |
| `P3-VERIFY.md` | 规格方可重放的构建、点击与服务端检查步骤 | 不适用 |

## 网络线程位置

- `SetCellModePacket.java:23`：`addScheduledTask` 包住 `setMode` (`:26`) 和重开 GUI (`:27`)。
- `RequestCellScreenPacket.java:22`：`addScheduledTask` 包住重开 GUI (`:24`)。
- `Mode2ConfigPacket.java:142`：`addScheduledTask` 包住 `handleServer`；设置写入在 `:151–166`，状态读取和发包在 `:168–190`。
- `Mode2ConfigPacket.java:195`：客户端 `Minecraft.addScheduledTask` 包住界面快照更新 (`:196–199`)。
- `ItemUnlimitedCell.java:41` 的右键开 GUI 来自原版服务端物品使用回调，不是 `IMessageHandler`；`CellScreens.java:35` 的另外两个调用来源是上述两个已调度的包处理器。

## 与规格不同、接口缺口和未验证项

1. 判定层没有规则、黑名单、数量锁、数量编辑、格式化及拒收显式名单枚举的公开接口。遵守边界没有摸 `CellStorageData` 字段，也没有修改 `cell/`、`data/`、`util/`。需要规格方增加 `listPanelItems()`（含零数量白名单/阈值条目）、`listRejectOn/Off()`、`listTag/ModRules()`、`listBlacklist()`、`getItemThresholdOverride()`、`add/removeRule()`、`setRuleMode()`、`toggleBlacklist()`、`setStoredQuantity()`、`wipeAllData()`，并确认各自返回值/持久化语义。`removeWhitelist(IAEItemStack)` 已存在，但没有与原 1.20 type 2 兼容的完整物品键编码；当前 GUI 只支持添加，缺移除 UI。
2. 仓库没有 1.12 驱动器、编辑器菜单或对应方块类，`CellEditorPickPacket` 与驱动器选槽打开路径没有实现。手持元件的主手/副手入口可用；驱动器入口尚不能验。
3. `Mode2ConfigPacket` 当前只实现 12 种 type；type 2/9–15/17/19–21 尚未接通。type 13（JEI 直加白名单）明确未使用。`RejectListState` 已准备 type 21 接收，但服务端不能枚举显式名单，因此没有发送 type 21。type 4/18 为当前 1.12 双端自有的字段布局，未保持盘点文件 C 节所有字段顺序；接通缺失接口时须统一调整双端编码。
4. 拒收页只列服务端面板返回的已存储物品，可切三态结果（左键翻转、右键清显式覆盖），看不到仅在显式拒收名单而未存储的物品。列表 type 4 收齐前显示进度，5 秒后转红，手动点刷新；没有重传机制。
5. 格式化按钮与输入框故意禁用：判定层无 `wipeAllData()`，不能用改 NBT 代替。规则、黑名单、数量编辑控件尚未做；非物品通道仍属 P4。1.20 英文 `reject_empty` 的 `?7` 按明显格式码笔误改为 `§7`；盘点表原文其余值从 1.20 JSON 提取。
6. 分类标签、规则横条/横向滚动和完整 1.20 tooltip 尚未实现；长提示在 1.12 GUI 宽度内裁剪显示。当前模式选择页没有完整三态显式名单。`ThresholdExpression` 支持数值算式和单位，但没有用构建或游戏输入验证。
7. 未运行 Gradle、构建/reobf、`/ae2addoncelltest`、游戏客户端或专用服务器；没有运行截图或日志。没有调用客户端 `displayGuiScreen`，因为目前没有独立的 S→C“打开纯客户端界面”场景；现有界面均由服务端 `openGui` 建容器。
