# P3 外包交接包：Mode2 配置界面 + 网络包（1.12.2 / AE2-UEL）

**状态：进行中** — 2026-10-02 起草。§1/§4/§5/§6 由爱丽丝写（含 javap 实测证据）；
§2/§3 等 1.20 侧界面/网络盘点回来后填入。
**前置**：`port-1.12.2-plan.md` §6、`p1-minimal-cell-spec.md`（已验收）、`p2-modes-and-persistence-spec.md`（已验收）。

> **给外包方的第一句话**：这份文档里的「平台适配表」和「交付纪律」是**硬约束**，
> 不是建议。1.12.2 与 1.20 的 GUI/网络 API 是两套东西，照 1.20 的写法搬一定编译不过或运行期静默失效。

---

## 0. 范围

**做**：
- Mode2 配置界面（页签、分类列表、阈值/白名单/臻藏/拒收相关控件）与模式选择界面
- 从 1.20 版移植对应的网络包（`SimpleNetworkWrapper` + `IMessage`）
- 客户端界面状态快照类（如 `RejectListState`）
- 让界面能**完整设置** P2 已有的全部设置项（mode / workMode / 全局阈值 / 单物品阈值 / 白名单 / 取消无限）

**不做**：
- **判定层**（`UnlimitedCellInventory`/`CellStorageData`/`CellWorldData`）—— 归爱丽丝，外包不许动
- 非物品通道（流体/气体/魔力）→ P4
- 1.12.2 的 AE2 配置界面（`AE2AddonConfigScreen`）—— 单独评估，不在本包强制范围

⚠ **一个前置依赖（已在 2026-10-02 完成，外包方可以直接调）**：P3 的界面里有**拒收列表**。
判定层的拒收支持**已经补好**并进了自检（C 组 5 条），接口在
`UnlimitedCellInventory`：
```java
boolean isRejecting(IAEItemStack key);      // 三态：显式拒收 > 显式不拒收 > 全局默认
boolean toggleReject(IAEItemStack key);     // 翻转；返回翻转后是否处于"拒收"
boolean clearReject(IAEItemStack key);      // 清除显式设置，回到跟随默认
boolean toggleRejectDefault();              // 翻转全局默认
boolean setRejectDefault(boolean value);
boolean rejectDefault();
int rejectOnCount();  int rejectOffCount();
```
口径：**只在 insert 生效**（extract 不受影响，已存进去的永远能取出）；拒收时 `injectItems` **原样返回 input**
（不是 null），且 **SIMULATE 与 MODULATE 都拒**（它是过滤器，不是动作）。
界面照 1.20 版的三态 UI 做（每个物品三种状态：跟随默认 / 强制拒收 / 强制不拒收）。

---

## 1. ★ 平台适配表（1.20.1 → 1.12.2）—— 全部 javap 实测，不是回忆

> 核对环境：`forge-1.12.2-14.23.5.2859_mapped_stable_39-1.12.jar`（MCP `stable_39`），
> 路径 `%USERPROFILE%\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.12.2-14.23.5.2859_mapped_stable_39-1.12\`。
> **每一条都请在动手前用 `javap` 复核一次**（下面给了命令），`stable_39` 里有几个名字和直觉不一样
> （例如 `Item.setUnlocalizedName` 在这套映射里叫 **`setTranslationKey`**）。

### 1.1 界面基类

| 1.20 写法 | 1.12.2 必须写成 | 证据 |
|---|---|---|
| `extends Screen` | `extends GuiScreen`；`public void initGui()` 建控件；控件放 `protected List<GuiButton> buttonList` | javap `GuiScreen`：`width/height/buttonList/fontRenderer` |
| `extends AbstractContainerScreen<T>` | `extends GuiContainer`（**没有泛型**）；必须实现 `protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY)`（**抽象**） | javap `GuiContainer` |
| 覆盖 `render(GuiGraphics, int, int, float)` | 覆盖 `public void drawScreen(int mouseX, int mouseY, float partialTicks)` | javap `GuiScreen` |
| `mouseClicked(double,double,int)` | `protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException`（**int 不是 double**） | javap `GuiScreen` |
| `keyPressed(int,int,int)` | `protected void keyTyped(char typedChar, int keyCode) throws IOException`；ESC 判断 `keyCode == 1` | javap `GuiScreen` |
| `isPauseScreen()` | `public boolean doesGuiPauseGame()` → 我们返回 `false` | javap `GuiScreen` |
| 控件基类 `AbstractWidget` | `GuiButton`：`public int id/x/y/width/height; public String displayString; public boolean enabled/visible;` 构造 `GuiButton(int id,int x,int y,String text)` 或 `(int id,int x,int y,int w,int h,String text)`；覆写 `drawButton(Minecraft, int mouseX, int mouseY, float partialTicks)` / `mousePressed(Minecraft,int,int)` / `mouseReleased(int,int)` | javap `GuiButton` |
| `EditBox` | `GuiTextField(int id, FontRenderer font, int x, int y, int w, int h)` + `setText/getText/drawTextBox/mouseClicked/textboxKeyTyped/setMaxStringLength/setFocused` | javap `GuiTextField` |
| `AbstractScrollWidget` | **不存在**：自己实现——覆写 `public void handleMouseInput()`，读 `org.lwjgl.input.Mouse.getDWheel()`，自己做裁剪 | javap 无该类 |

### 1.2 2D 绘制（1.20 的 `GuiGraphics` 在 1.12.2 **完全没有对应物**）

| 1.20 | 1.12.2 |
|---|---|
| `guiGraphics.blit(ResourceLocation, x,y,u,v,w,h)` | `mc.getTextureManager().bindTexture(rl)` 然后 `drawTexturedModalRect(x, y, u, v, w, h)`（还有 `(float,float,int,int,int,int)` 与 sprite 版） |
| `guiGraphics.fill(x1,y1,x2,y2,argb)` | `Gui.drawRect(x1, y1, x2, y2, argb)`（**static**） |
| `guiGraphics.drawString(font, text, x, y, color, shadow)` | `fontRenderer.drawString(text, x, y, color)` / `drawStringWithShadow(text, x, y, color)`；居中用 `drawCenteredString(font, text, x, y, color)` |
| `font.width(text)` | `fontRenderer.getStringWidth(text)` |
| `guiGraphics.renderTooltip(...)` | `drawHoveringText(List<String> lines, int x, int y, FontRenderer font)`；物品用 `renderToolTip(ItemStack, int, int)` |
| 分隔线 | `drawHorizontalLine(int x1,int x2,int y,int color)` / `drawVerticalLine(...)` |
| 文字换行 | `fontRenderer.listFormattedStringToWidth(text, width)` |

**证据**：javap `net.minecraft.client.gui.Gui` 与 `FontRenderer`（见 §1.6 的命令）。

### 1.3 文本与本地化

| 1.20 | 1.12.2 |
|---|---|
| `Component.translatable("k", args)` | `I18n.format("k", args)` 得到 **String**；确实需要组件时 `new TextComponentTranslation("k", args)` / `new TextComponentString("s")` |
| lang 文件 `zh_cn.json` | **`zh_cn.lang`**（`.lang` 不是 json），键 `item.<modid>:<name>.name`、界面键照 1.20 原样搬 |
| `setTooltip` / `Component` 列表 | `List<String>` + `drawHoveringText` |

**证据**：javap `I18n.format(String, Object...)`、`TextComponentTranslation(String, Object...)`；
`.lang` 约定来自同代附属 Mekanism Energistics 的 `assets/mekeng/lang/en_us.lang` 实证。

### 1.4 容器与槽位

| 1.20 | 1.12.2 |
|---|---|
| `extends AbstractContainerMenu` | `extends net.minecraft.inventory.Container`；`public boolean canInteractWith(EntityPlayer)` |
| `addSlot(new Slot(...))` | `addSlotToContainer(net.minecraft.inventory.Slot)`（返回该槽）；槽位坐标字段是 **`xPos`/`yPos`**，序号 `slotNumber` |
| `Slot#mayPlace` | `boolean isItemValid(ItemStack)` |
| `quickMoveStack` | `ItemStack transferStackInSlot(EntityPlayer, int index)` |
| `ContainerData` / `addDataSlots` | **没有**：要么 `detectAndSendChanges()` + `IContainerListener`，要么**自己发网络包同步**（我们的界面不是常驻容器，推荐后者） |
| `stillValid` | `canInteractWith` |
| 客户端/服务端同开 | 服务端 `player.openGui(modInstance, guiId, world, x, y, z)`，并在 preInit/preInit 之后 `NetworkRegistry.INSTANCE.registerGuiHandler(modInstance, new IGuiHandler(){ getServerGuiElement/getClientGuiElement })` |

**证据**：javap `Container`（`addSlotToContainer/inventorySlots/inventoryItemStacks/detectAndSendChanges/onContainerClosed/transferStackInSlot/canInteractWith`）、
`Slot(IInventory,int,int,int)`、`EntityPlayer.openGui(Object,int,World,int,int,int)`、`NetworkRegistry.INSTANCE` 与 `IGuiHandler`。

### 1.5 网络

| 1.20 | 1.12.2 |
|---|---|
| `CustomPacketPayload` + `StreamCodec` + `RegisterPayloadHandlersEvent` | `SimpleNetworkWrapper`：`NetworkRegistry.INSTANCE.newSimpleChannel(String)`（**1 个 mod 一个 channel**，建议在 preInit 建） |
| 注册 payload | `channel.registerMessage(HandlerClass.class, MsgClass.class, id, Side)`；`id` 从 0 递增、**C→S 与 S→C 各自独立编号区间**要自己保证不冲突 |
| `handle(...)` | `implements IMessageHandler<REQ, REPLY>`，`public REPLY onMessage(REQ msg, MessageContext ctx)`（**返回 null 表示不回包**） |
| `FriendlyByteBuf` | `net.minecraft.network.PacketBuffer`：`writeVarInt/readVarInt`、`writeInt/readInt`、`writeLong/readLong`、`writeBoolean/readBoolean`、`writeString/readString(maxLen)`、`writeCompoundTag/readCompoundTag`、`writeItemStack/readItemStack`、`writeUniqueId/readUniqueId`、`writeResourceLocation/readResourceLocation`、`writeByteArray/readByteArray` |
| `PacketDistributor.sendToServer/SendToPlayer` | `channel.sendToServer(msg)` / `channel.sendTo(msg, EntityPlayerMP)` / `sendToAll` / `sendToAllAround` / `sendToDimension` |
| 服务端线程安全 | 1.12.2 的 `onMessage` 在**网络线程**执行：改世界/容器必须 `ctx.getServerHandler().player.getServer().addScheduledTask(() -> ...)` 或 `IThreadListener` 调度（**这条不做就会随机崩**） |
| 客户端开界面 | `Minecraft.getMinecraft().displayGuiScreen(new XxxScreen(...))`（在**客户端线程**里调；从包触发时同样要 `addScheduledTask`） |

**证据**：javap `SimpleNetworkWrapper`（`registerMessage(Class,Class,int,Side)`/`sendToServer`/`sendTo`/`sendToAll`…）、
`IMessageHandler.onMessage(REQ, MessageContext)`、`MessageContext.getServerHandler/getClientHandler/side`、
`PacketBuffer`（上述每个读写方法逐个确认过）、`Minecraft.displayGuiScreen`。

### 1.6 复核命令（外包方动手前请自己跑一遍）

```powershell
$javap = 'C:\Program Files\Eclipse Adoptium\jdk8u504-b01\bin\javap.exe'
$j = "$env:USERPROFILE\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.12.2-14.23.5.2859_mapped_stable_39-1.12\forge-1.12.2-14.23.5.2859_mapped_stable_39-1.12.jar"
& $javap -p -cp $j net.minecraft.client.gui.GuiContainer
& $javap -p -cp $j net.minecraft.network.PacketBuffer
& $javap -p -cp $j net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper
```
**规则：凡是本文没写、而你要用的 API，先 `javap` 确认签名再写。** 凭记忆写 1.12.2 代码是本项目已经付过代价的坑。

---

## 2. 界面清单

> **细节以盘点文件为准**：`D:\大爱丽丝工作区\reports\p3-ui-inventory-2026-10-02.md`（532 行，含每个控件的行号、
> 77 个 lang 键的中英值、鼠标/键盘交互逐条）。本节只给**外包必须按此执行的口径与决定**，不重复细节。

**一个 Screen + 两个 Menu**（行数为实测，不是估算）：

| 文件 | 行数 | 作用 |
|---|---|---|
| `gui/ModeSelectScreen.java` | 219 | 选存储模式（umode 1/2/3），并带**拒收默认值**的 UI |
| `gui/ModeSelectMenu.java` | 89 | 上者的容器 |
| `gui/Mode2ConfigScreen.java` | 1645 | mode2 的主配置界面（列表 + 阈值 + 白名单 + 拒收） |
| `gui/Mode2ConfigMenu.java` | 256 | 上者的容器 |
| `gui/RejectListState.java` | 33 | **纯客户端**快照（拒收名单的本地视图），可直接搬 |
| `client/gui/AE2AddonConfigScreen.java` | 456 | 模组自身配置界面（**不在本包强制范围**，单独评估） |

**★ 决定 1：打开方式必须换**
1.20 走 `NetworkHooks.openScreen` + `MenuScreens.register`（`ClientSetup.java:33-34`）。
1.12.2 换成：
- 服务端：`player.openGui(modInstance, guiId, world, x, y, z)`
- 在 preInit 之后：`NetworkRegistry.INSTANCE.registerGuiHandler(modInstance, new IGuiHandler(){...})`
  —— `getServerGuiElement` 返回 `Container`，`getClientGuiElement` 返回 `GuiScreen`
- **纯客户端**（由包触发的场景，如"打开某元件的配置"）：`Minecraft.getMinecraft().displayGuiScreen(...)`，
  且**必须在客户端线程**里调（从包触发时要 `addScheduledTask`）

**★ 决定 2：不需要 `LightStackWriter` 的等价物**
1.20 有 `item/UniversalStorageCell.java:114-128` 的 `LightStackWriter` 裁剪客户端可见的元件 NBT
（移除 `s1/s2/sa/u/w`）。**1.12.2 上不需要** —— 我们的元件物品 NBT 本来就只存
`mode/wm/thr/uuid`（大数据在世界存档里，见 P2 规格 §4），所以直接同步即可。
**但要守住这条**：外包方**不许**往物品 NBT 里塞列表数据（那会把每个槽位的同步包撑爆）。

**★ 决定 3：1.20 侧的两个界面缺陷 —— 不复刻**
盘点文件 F 节用原文+行号记了两个 1.20 的疑似 bug，1.12.2 **按"修好的行为"实现**：
1. `Mode2ConfigScreen` 的 `rejectDefaultButton` 字段**在 1.20 里从未初始化（恒 null）**，
   所以拒收默认值实际只有 `ModeSelectScreen` 上有 UI。⇒ 1.12.2 要在**该有的地方正确初始化**，
   拒收默认值的 UI 可以两处都有，但**必须真的能改**（改完 `/ae2addoncell info` 的 `rej=…@…` 要跟着变）。
2. `Mode2ConfigScreen` 的 `rejectTab` 与 `renderPanel` 的 `entry.isInfinite` 分支存在互斥逻辑纠缠
   （`Mode2ConfigScreen:1179` 的 `isBlocked` 还依赖 `currentRuleFilter`）。⇒ 1.12.2 **不要**照抄这套纠缠：
   页签归页签、条目状态归条目状态，分开判。

**★ 决定 4：与判定层的接缝**
界面**只能**通过 `UnlimitedCellInventory` 的公开方法改设置（`setMode/setWorkMode/setThreshold/setItemThreshold/
addWhitelist/removeWhitelist/cancelInfinite/isRejecting/toggleReject/clearReject/toggleRejectDefault/setRejectDefault`），
**不许**直接摸 `CellStorageData` 的字段，也不许改判定层。

---

## 3. 网络包清单

> 细节见盘点文件 C 节（每个 type 的字段顺序逐条列出）。

**1.20 的形态**：`network/Mode2ConfigPacket.java`（1091 行）是**一个包内 24 个 type 的多态大包**
（注册号 1，字段随 type 变），另有 4 个小包：`SetCellModePacket`（63）、`RequestCellScreenPacket`（51）、
`CellEditorPickPacket`（56）、`CellScreenTarget`（50，纯解析工具类）。
分包阈值 `MAX_ITEMS_PER_CHUNK = 50`，只覆盖 type 4/12/21，**没有重传/超时**。

**★ 决定 5：1.12.2 上"一个大包 + 24 个 type"照搬**
理由：`SimpleNetworkWrapper` 只能按 `(handlerClass, msgClass, id, Side)` 注册，
一个多态包正好用**一个注册号**，改动面最小；拆成 24 个类反而要维护 24 个 id 与 24 个 handler。
- 注册在 preInit：`NetworkRegistry.INSTANCE.newSimpleChannel("ae2addon")`，
  然后逐条 `registerMessage(...)`；**C→S 与 S→C 的 id 必须各自不重复**（用两个号段：0~49 给 C→S，50~99 给 S→C）。
- 序列化用 `PacketBuffer`（可用方法见 §1.5）。**`readString` 必须给上限**，否则超长包会把客户端拖死。
- **分包阈值 50 保留**（`MAX_ITEMS_PER_CHUNK`），type 4/12/21 照旧分包。
- ⚠ 1.20 侧没有重传机制 ⇒ 1.12.2 也**不做**，但**必须在界面里对"收到一半"有明确表现**（否则分包丢一个就永久卡在旧列表）。

**★ 决定 6：网络线程调度（这一条不做就会随机崩）**
`onMessage(REQ, MessageContext)` 在**网络线程**执行。所有"改世界/改容器/开界面"的动作
**必须** `ctx.getServerHandler().player.getServer().addScheduledTask(() -> {...})`
（客户端侧同理用 `Minecraft.getMinecraft().addScheduledTask`）。
**验收时会要求外包方在代码里逐个指出这些位置**（见 §4-D13），不接受"应该没事"。

**★ 决定 7：两个字段是两套东西，别混**
- **存储模式 `umode`**（1/2/3）走 `SetCellModePacket`，且**服务端切完立刻重开界面**；
- **工作模式 `wm`**（1/2/3 = 阈值/存入无限/臻藏）走 `Mode2ConfigPacket` 的 **type 6**。
这两者在 1.20 是独立字段、独立包路径，1.12.2 照旧。

**★ 决定 9：搜索栏的拼音支持 —— 采用「只搬数据 + 自写解析」，零第三方依赖（已实现并验证）**
1.20 的搜索栏支持**拼音全拼 + 首字母**，靠 **pinyin4j**。原计划（sensei 选的）是**用 Shadow 把 pinyin4j 打进 jar**
—— **这条路在当前环境走不通**，已实测：
- `com.github.johnrengelman:shadow:4.0.4` 在 Maven Central 上**不存在**（`repo1.maven.org/.../shadow/` 直接 404）；
- Gradle 插件门户该路径下**只留 8.1.0/8.1.1**（需要 Gradle 8，我们是 4.9）；
- 阿里云的 `gradle-plugin` 镜像**没有**它（404）。
⇒ 除非把 Gradle 从 4.9 升到 8（会动整个 1.12.2 工具链），否则拿不到 Shadow。

**实际采用的做法**（更好，且已落地）：
- 只把 pinyin4j 的**数据表**搬进我们的 jar：`src/main/resources/ae2addon/pinyin/unicode_to_hanyu_pinyin.txt`
  （265,545 B，SHA256 `F41A310AE6874351A4ABF65D6D7AA4B80BEE13C88A71D5EE601981D422D1CCA6`）；
  许可是 **BSD**（pinyin4j 的 pom 里写明），署名与原因写在同目录 `NOTICE.txt`。
- 解析自己写：`com.ae2addon.util.PinyinIndex`（约 30 行解析 + 扁平数组按码点索引，首次搜索时惰性载入）。
- **产物证据**：jar 140,130 B；`ae2addon/pinyin/unicode_to_hanyu_pinyin.txt` 压后 93,699 B 在包内；
  **`net/sourceforge/**` 条目 0、`com/hp/**` 条目 0**（连 pinyin4j 拖来的 sparta XML 解析器也一并没有）。
- **解析正确性证据**（用 JDK 8 单独跑同一份解析逻辑，非"应该能跑"）：
  `parsed=20378 skipped=525`，且 `skipped` **恰好等于**数据里 `(none0)`（无读音）的行数
  ⇒ **没有任何一行因为别的原因被丢掉**；
  行数账：20,903 = 525 + 20,378 ✓。
  样例：石头→`shitou`/`st`、铁锭→`tieding`/`td`、钻石→`zuanshi`/`zs`、木棍→`mugun`/`mg`、
  煤炭→`meitan`/`mt`、下界石英→`xiajieshiying`/`xjsy`、ASCII 原样透传。

**外包方只需调用这个 API**（不许直接碰数据文件，也不许自己再引拼音库）：
```java
boolean PinyinIndex.matches(String query, String displayName, String registryId); // 主入口
String  PinyinIndex.full(String text);      // 全拼
String  PinyinIndex.initials(String text);  // 首字母
```
匹配顺序：注册 ID 子串 → 显示名子串（含直接打中文）→ ASCII 查询再试首字母/全拼。

**★ 决定 8：一处 1.20 的注释与实现不符（照实现，不照注释）**
盘点文件指出 `Mode2ConfigPacket:440` 的 type 13 注释写的是"取消无限确认弹窗接收"，
但实现其实是"JEI 直加白名单"。⇒ 1.12.2 **以实现为准**；如果最终不做 JEI 联动，就把 type 13 明确标为"未使用"，
不要留一个语义不明的包类型。

**★ 决定 8.1（2026-10-02 追加，同日更正）：JEI 联动「做」—— 挂点 AE2-UEL 已经给好了**

> ⚠ **本条之前写的是"不做"，那是我凭印象下的判断，没查证。sensei 提醒后我查了两个 jar，事实如下：**

**证据（`javap` + jar 条目枚举，1.12.2 实测）**：
- **JEI 4.16.5 本身就有 ghost-ingredient 这套 API**：`mezz/jei/api/gui/IGhostIngredientHandler`（内嵌 `Target<I> extends Consumer<I>`）、
  `mezz/jei/gui/ghost/**`。签名：
  ```java
  interface IGhostIngredientHandler<T extends GuiScreen> {
      <I> List<Target<I>> getTargets(T gui, I ingredient, boolean doStart);
      void onComplete();
      boolean shouldHighlightTargets();      // default
      <I> boolean quickMove(T gui, I ingredient);  // default
  }
  interface Target<I> extends Consumer<I> { Rectangle getArea(); void accept(I); }
  ```
- **AE2-UEL 已经接好 JEI**：`appeng/integration/modules/jei/JEIPlugin` 里有
  `mezz/jei/api/IModRegistry.addGhostIngredientHandler(Class, IGhostIngredientHandler)`；
  并且给附属留了挂点 `appeng/container/interfaces/IJEIGhostIngredients`：
  ```java
  List<IGhostIngredientHandler.Target<?>> getPhantomTargets(Object ingredient);
  Map<IGhostIngredientHandler.Target<?>, Object> getFakeSlotTargetMap();
  ```
  旁证：同代附属 Mekanism Energistics 的 `GuiGasInterfaceConfigurationTerminal`
  就 implements 了 `mezz/jei/api/gui/IGhostIngredientHandler$Target` —— 这条路子在实际附属里用过。

**结论与做法**：
- 我们的界面继承的是原版 `GuiContainer`（不是 AE2 的 `AEBaseGui`），所以**不能**指望 AE2 那个
  `addGhostIngredientHandler(AEGuiHandler.getGuiContainerClass(), …)` 覆盖到我们 ⇒
  **自己写一个 `@JEIPlugin`**：`register(IModRegistry)` 里
  `registry.addGhostIngredientHandler(Mode2ConfigScreen.class, new CellScreenGhostHandler())`。
- `getTargets(gui, ingredient, doStart)`：ingredient 是 `ItemStack` 时，按当前页签返回目标区域
  （物品页 → 加白名单 type 1；拒收页 → 拒收切换 type 7），`Target.accept(stack)` 里发对应包。
- **构建**：JEI 必须是 **compileOnly**（`deobfCompile`/`fg.deobf` 且不进 jar）——
  运行时没有 JEI 时，`@JEIPlugin` 类不会被扫描、也不会加载，所以不需要额外守卫。
  ⚠ **别把 JEI 打进我们的 jar**（我们没引 Shadow，也没有 relocate，打进去就是撞类）。
- 判定层不需要任何新接口：type 1 / type 7 的服务端处理早就在了。

---

## 4. ★ P3 的验收（爱丽丝独立执行，不看实现方自述）

**A. 编译与构建**
1. `& 'D:\大爱丽丝工作区\tools\build-1122.ps1'` → `BUILD SUCCESSFUL` + `:reobfJar` ✓
2. 产物 `javap -c` 自查：MC 调用是 `func_xxxx`（SRG），`appeng/**` 名字保持可读
3. jar 内不含 `com/example/`（陈旧产物自检）

**B. 回归（必须全绿，不许因为"改了界面"而变红）**
4. `/ae2addoncelltest` → **PASS 28 / FAIL 0**（A 组 11 条 = P1 语义、B 组 12 条 = P2 三模式/持久化、
   C 组 5 条 = 拒收三态）。**这是硬门：外包改动不许让任何一条变红。**

**C. 界面逐项**（与 1.20 版逐项对照，逐条给截图或文字证据）
5. 打开路径正确（从元件/驱动器/命令任一入口进得去，且**服务端也开了容器**）
6. 每个控件都能用：点得动、值生效、界面刷新
7. **设置项与判定层一致**：界面上改 mode/wm/阈值/白名单 → `/ae2addoncell info` 的读数跟着变（这是跨端真源一致性的硬证据）
8. 分页/滚动/页签切换不串数据
9. tooltip 与 1.20 版文案一致
10. 关闭界面不残留状态、不报错

**D. 网络**
11. **专用服务器**（不是单机的"内部服务器"）上跑一遍：开界面、改设置、关界面，`logs/latest.log` 无异常
12. 断线/重连后界面状态正确
13. 网络线程调度：改世界/容器的动作都有 `addScheduledTask`（**在代码里指出位置**，不能只说"应该没事"）

**E. 界面之后：命令入口保留还是删除**
14. 命令 `/ae2addoncell ...` **先保留**（它是排障与自检的后门），是否移除由 sensei 决定

---

## 5. ★ 交付纪律（违反即拒收）

1. **不许改判定层**：`cell/UnlimitedCellInventory.java`、`cell/CellStorageData.java`、`data/CellWorldData.java`
   一行都不许动。需要新接口就跟爱丽丝说，由爱丽丝加。
2. **不许改自检断言口径**：`/ae2addoncelltest` 的 A/B 组断言是实现方的**验收门**，不是可调参数。
   断言写错了由**规格方（爱丽丝）**改规格后重跑 —— 实现方不得为了让验收通过而放宽断言。
3. **必须给证据**：编译输出、`javap` 自查结果、游戏内实际操作的截图或日志行。**不接受"应该没问题"。**
4. **不许靠记忆写 1.12.2 API**：凡本文没覆盖的调用，先 `javap` 再写。
5. **事件总线与注册时机是定死的**（本项目崩过两次）：
   - AE2 的 `ICellRegistry.addCellHandler` 必须在 **`FMLInitializationEvent`** 注册（AE2 在它自己的 init 才放
     `BasicCellHandler`，谁先注册谁抢 index 0 → `VerifyException` 崩服）
   - 物品/方块注册在 **`@Mod.EventBusSubscriber`** 类里（1.12.2 的 `RegistryEvent` 发在
     `MinecraftForge.EVENT_BUS`；`@Mod` 实例只挂自己的 mod 总线，写在实例方法上会**静默不执行**）
   - 界面注册走 `NetworkRegistry.INSTANCE.registerGuiHandler`
6. **rv6 的返回值语义是反的**（详见 P1 规格 §3.4）：全收 = `null`、拒收 = 原样返回输入、取不到 = `null`；
   SIMULATE 与 MODULATE 必须返回**相同的剩余量**，且剩余量要在 MODULATE 判断**之前**算好。
7. **提交前自检**：构建日志含 `BUILD SUCCESSFUL`、`/ae2addoncelltest` 全绿、专用服务器跑过一次。

---

## 6. 交付物清单（外包方要交什么）

- 源码：新增/修改的 `.java` 文件放在 `D:\环境\ae2-addon-1.12.2\src\main\java\com\ae2addon\` 下（**不要动其它文件**）
- 资源：`assets/ae2addon/lang/{en_us,zh_cn}.lang` 的新键；纹理放 `assets/ae2addon/textures/gui/`
- 一份「改了什么」说明：逐文件列改动点 + **每处用到的 1.12.2 API 的 javap 证据行**
- 一份「怎么验」说明：给爱丽丝的复现步骤（命令、点击顺序、期望现象）
- **不要**自己构建发布 jar；构建与部署由爱丽丝做（避免产物不可追溯）
