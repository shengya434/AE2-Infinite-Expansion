# P3 外包开工封面（先读这一份）

**任务**：把「无限存储元件」的**配置界面 + 网络包**从 1.20.1 移植到 **1.12.2 / Forge 14.23.5.2859 / AE2-UEL v0.56.7**。
判定层（存储引擎、三模式、持久化）**已经做完并验收通过**，本次只做界面与网络。

---

## 0. 一句话边界

| 你做 | 你不做 |
|---|---|
| `ModeSelectScreen/Menu`、`Mode2ConfigScreen/Menu`、`RejectListState`、网络包 | 判定层（`cell/`、`data/`）一行都不许动 |
| 让界面能设置：mode / workMode / 全局与单物品阈值 / 白名单 / 取消无限 / 拒收三态 | 模组自身配置界面（`AE2AddonConfigScreen`）—— 那部分由我们自己做 |
| 资源：`.lang` 新键、`textures/gui/` 纹理 | 非物品通道（流体/气体/魔力）→ 下一阶段 |

---

## 1. 开工前必须做的三件事

1. **记下基线**：仓库 `D:\环境\ae2-addon-1.12.2`，基线提交 **`716b24d`**
   （`git log --oneline -1` 应看到 "feat(1.12.2): P1 + P2 完成基线…"）。
   你的所有改动请留在工作区并**自己提交**，我们按 `git diff 716b24d` 逐条核对。
2. **先跑一次构建与自检，确认你的环境是绿的**（命令见 §4）。**红的先别动手**，把输出发回来。
3. **读这三份**（按顺序，别跳）：
   - `D:\环境\ae2-addon\md\p3-gui-network-handoff.md` ← **你的规格书**，§1 平台适配表 + §2/§3 清单与决定 + §4 验收单 + §5 交付纪律
   - `D:\大爱丽丝工作区\reports\p3-ui-inventory-2026-10-02.md` ← **1.20 侧的逐项事实**（532 行，每个控件带行号、77 个 lang 键的中英值）
   - `D:\环境\ae2-addon\md\p2-modes-and-persistence-spec.md` ← 判定层口径（你调用的接口都在这里）

---

## 2. 五条硬约束（违反即拒收）

1. **不许改判定层**：`cell/UnlimitedCellInventory.java`、`cell/CellStorageData.java`、`data/CellWorldData.java`。
   需要新接口就提出来，由我们加。
2. **不许改自检断言**：`/ae2addoncelltest` 的 A/B/C 组共 **28 条**是验收门，不是可调参数。
   断言写错了由我们改规格后重跑 —— **不许为了让验收通过而放宽断言**。
3. **不许靠记忆写 1.12.2 API**：凡是规格书 §1 没覆盖的调用，先 `javap` 确认签名（命令见 §4）。
   本项目已经因为"凭记忆/凭直觉写平台细节"崩过两次。
4. **两条接线是定死的**（同一次崩溃换来的）：
   - AE2 的 `ICellRegistry.addCellHandler` 必须在 **`FMLInitializationEvent`** 注册（AE2 在自己的 init 才放
     `BasicCellHandler`，抢 index 0 会 `VerifyException` 崩服）；
   - 物品/方块注册写在 **`@Mod.EventBusSubscriber`** 类里（1.12.2 的 `RegistryEvent` 发在
     `MinecraftForge.EVENT_BUS`；写在 `@Mod` 实例方法上会**静默不执行**）。
5. **网络线程**：`IMessageHandler.onMessage` 在**网络线程**执行，凡改世界/容器/开界面必须
   `addScheduledTask` —— 验收时要求你**逐个指出这些位置**，不接受"应该没事"。

---

## 3. 三条最容易写错的地方（规格书里有详细版）

1. **1.12.2 没有 `GuiGraphics`**：绘制用 `Gui.drawTexturedModalRect` / `Gui.drawRect`（static）/
   `fontRenderer.drawString`；`GuiContainer` **没有泛型**，且 `drawGuiContainerBackgroundLayer` 是**抽象**方法。
2. **rv6 的返回值语义是反的**：全收 = `null`、拒收 = 原样返回输入、取不到 = `null`；
   SIMULATE 与 MODULATE 必须返回**同样的剩余量**，且剩余量要在 MODULATE 判断**之前**算好。
3. **数据不在物品 NBT 里**：元件 NBT 只有 `mode/wm/thr/uuid`，列表数据在世界存档。
   **不许**往物品 NBT 里塞列表（那会把每个槽位的同步包撑爆）。1.20 的 `LightStackWriter` 因此不需要移植。

---

## 4. 构建 / 自检 / 取证 三条命令

```powershell
# ① 构建（一条命令；钉 JDK 8、镜像到 ASCII 目录、按日志判成败）
& 'D:\大爱丽丝工作区\tools\build-1122.ps1'
#   期望：BUILD SUCCESSFUL : True / reobfJar ran : True

# ② 游戏内自检（这是验收门）
/ae2addoncelltest
#   期望：PASS 28 / FAIL 0

# ③ 1.12.2 API 取证（凡规格书没写的调用，先跑这个再写代码）
$javap = 'C:\Program Files\Eclipse Adoptium\jdk8u504-b01\bin\javap.exe'
$j = "$env:USERPROFILE\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.12.2-14.23.5.2859_mapped_stable_39-1.12\forge-1.12.2-14.23.5.2859_mapped_stable_39-1.12.jar"
& $javap -p -cp $j net.minecraft.client.gui.GuiContainer
```

**注意**：源码是 UTF-8；构建已固定 `-encoding UTF-8`（JDK 8 在中文 Windows 上默认按 GBK 读源码，会报
「编码GBK的不可映射字符」）。**别改 `build.gradle` 的编码设置。**

---

## 5. 交付物（缺一项都不算完成）

1. 源码：改动/新增文件留在 `D:\环境\ae2-addon-1.12.2\src\main\java\com\ae2addon\` 下，**自己 git commit**。
2. 资源：`assets/ae2addon/lang/{en_us,zh_cn}.lang` 新键（77 个键的中英值在盘点文件 A 节）；
   纹理放 `assets/ae2addon/textures/gui/`。
3. **「改了什么」说明**：逐文件列改动点，且**每处用到的 1.12.2 API 附上 `javap` 证据行**。
4. **「怎么验」说明**：命令 + 点击顺序 + 期望现象（要能让我照着重放）。
5. 构建输出与 `/ae2addoncelltest` 的完整输出（截图或日志行）。

**不要**自己构建发布 jar、不要改部署目录 —— 构建与部署由我们做，避免产物不可追溯。

---

## 6. 验收（由我们独立执行，不看自述）

`p3-gui-network-handoff.md` §4 的 14 条，简言之：
构建绿 + **自检 28 条不许变红** + 界面逐项可用 + **界面上改的设置能在 `/ae2addoncell info` 里读到**
（这是跨端真源一致性的硬证据）+ **专用服务器**跑一遍无异常 + 网络线程调度位置能逐个指出。

有任何一条不满足，我们会把失败项列出来让你修 —— 但**不会**降低断言口径。
