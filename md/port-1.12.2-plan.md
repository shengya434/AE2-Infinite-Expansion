# 1.12.2 移植方案（无限存储元件 + 跨 mod 兼容层）

**状态：取证中（第 1 草案）** — 2026-10-02 立。sensei 选定范围：**只做「无限存储元件（核心）」+「跨 mod 兼容层」**；
无限接口、千机样板体系、集成 CPU 多方块**不在本次范围**。

> 待填的两张表（两个取证子任务跑着）：**§4 rv6 API 差异**、**§5 兼容层在 1.12.2 还剩哪些**。
> 在它们回来之前，本文件里凡是标「取证中」的都**不要当结论用**。

---

## 1. 为什么要单独评估"移植"，而不是"搬代码"

当前模组是 **Forge 1.20.1 + AE2 15.4.11** 的形态：**198 个 Java 文件 / 44,155 行**，
其中 **111 个文件 import 了 `appeng.*`**、18 个 mixin、24 个兼容层文件、180 个 recipe json。

1.12.2 的 AE2 是 **rv6 那一代**，API 是 `IAEItemStack` / `IStorageChannel` / `TileEntity` 这一套，
跟 1.20 的 `AEKey` / `MEStorage` / `BlockEntity` 是**两套完全不同的东西**。
所以这不叫移植、叫**用同一套设计重做一份**：能直接搬的是**设计、数据模型、算法**，代码几乎全要重写。

**最重要的一条待确认**（子任务在查）：1.12.2 里**应该没有 `CraftingCpuLogic`**（那是 1.16+ 重写后的类）⇒
那 2713 行的 CPU mixin 层无法移植 —— 好在**它在本次范围之外**（集成 CPU / 虚拟结算不做）。

## 2. 本次范围的文件清点（已确认）

| 分组 | 文件 | 行数 | 处置 |
|---|---|---|---|
| 核心存储 | `cell/UnlimitedCellInventory.java` | 1099 | **重写 API 层**，算法/三模式/BigInteger 字典照搬 |
| | `cell/BoundInfiniteCellInventory.java` | 85 | 同上（小） |
| 元件物品 | `item/UniversalStorageCell.java` | 205 | 重写（rv6 的 cell 注册链路不同） |
| | `item/BoundInfiniteCellItem.java` | 83 | 重写（小） |
| | `item/CellEditorItem.java` | 73 | 重写（小） |
| | `item/ConfigCardItem.java` | 38 | 重写（小） |
| 注册 | `init/UnlimitedCellHandler.java` | 52 | 重写（rv6 用 `ICellHandler` 注册表） |
| | `init/ModItems.java`（元件相关部分） | 251 | 部分改写（1.12.2 无 DeferredRegister） |
| 持久化 | `data/CellDataSavedData.java` | 286 | 改成 `WorldSavedData` |
| 槽位守卫 | `mixin/InfiniteCellSlotGuardMixin.java` | 38 | 重写挂载点（rv6 无 `AppEngInternalInventory`） |
| 元件界面 | `gui/Mode2ConfigScreen.java` | 1448 | **重写**（1.20 `AbstractContainerScreen` → 1.12.2 `GuiScreen`） |
| | `gui/Mode2ConfigMenu.java` | 213 | 改成 1.12.2 `Container` |
| | `gui/ModeSelectScreen.java` | 198 | 重写 |
| | `gui/ModeSelectMenu.java` | 72 | 改写 |
| | `gui/RejectListState.java` | 30 | 直接可用（纯客户端快照） |
| | `client/gui/AE2AddonConfigScreen.java` | 397 | 改成 1.12.2 的 `GuiConfig`/自绘（配置系统不同） |
| 网络 | `network/Mode2ConfigPacket.java` | 1007 | **重写**（`SimpleChannel` → `SimpleNetworkWrapper`/`IMessage`） |
| | `network/SetCellModePacket.java` | 51 | 重写 |
| | `network/CellScreenTarget.java` | 45 | 可留（纯解析逻辑） |
| | `network/RequestCellScreenPacket.java` | 43 | 重写 |
| | `network/CellEditorPickPacket.java` | 48 | 重写 |
| 配置 | `config/AE2AddonConfig.java`（元件相关项） | 390 | 改写（`ForgeConfigSpec` → `Configuration`） |
| 工具 | `util/{ChatLog,SizeFormat,NumberExpr}.java` | 69+30+321 | 基本可直接搬（无 AE2 依赖） |
| 资源 | `lang/{en_us,zh_cn}.json` | — | **键名规则要全改**（1.12.2 用 `item.<modid>.<name>.name`） |
| | `data/**/recipes/*.json` | 180 | **全部转 1.12.2 配方格式** |
| | `data/**/loot_tables/**` | 7 | 转 1.12.2 loot table（vanilla 格式，改动小） |
| | `models/blockstates/textures` | — | 基本沿用，物品/方块模型细节要按 1.12.2 调整 |

**要重写的合计约 5,500 行 Java**（元件核心 ≈1,900、界面 ≈2,400、网络 ≈1,200）+ 180 个配方 json。

## 3. 目标环境与前置条件（已确认一条硬前提）

| 项 | 目标 | 现状 |
|---|---|---|
| Forge | 1.12.2 —— `14.23.5.2859`（推荐）| 无 |
| Java | **JDK 8（必须完整 JDK，不是 JRE）** | ✅ **已装**：`C:\Program Files\Eclipse Adoptium\jdk8u504-b01`（Temurin 1.8.0_504，190 MB，`javac -version` 已验）。⚠ **没动 `JAVA_HOME`**（它现在是空的），PATH 上的 `java` 仍是 26 ⇒ 1.12.2 项目必须**显式钉** JDK 8（`org.gradle.java.home`） |
| 构建 | 独立仓库 + ForgeGradle（与 1.20.1 项目**物理隔离**，避免互相污染 gradle 缓存） | 现在只有 `D:\ae2addon-build` 一个构建目录（1.20.1） |
| 映射 | MCP `stable_39`（1.12.2 社区标准）| — |
| AE2 | **AE2-UEL（非官方延续版）** —— 见 §4.0 | 官方 rv6-stable-7 已取证；UEL 正在取证 |
| Mixin | Forge 1.12.2 自带 Mixin 0.7.11（够用；本次只需 1 个 slot guard） | — |

## 4. 1.12.2 侧 API 事实

### 4.0 ★ 目标 jar 是 **AE2-UEL**，不是官方 rv6（sensei 更正，2026-10-02）

- **1.12.2 上真正在用的权威版本是 `AE2-UEL`（Applied Energistics 2 Unofficial Extended Life）** ——
  官方 `rv6-stable-7` 停更在 2019 年，1.12.2 的整合包实际都用 UEL。
- **坐标（已核）**：GitHub 组织 `AE2-UEL`，主仓库 `github.com/AE2-UEL/Applied-Energistics-2`；
  CurseForge 项目 id `570458`（slug `ae2-extended-life`）。
- **最新版本 = `ae2-uel-v0.56.7`（2025-03-14）**，jar 直链
  `https://edge.forgecdn.net/files/6302/98/ae2-uel-v0.56.7.jar`；CF 上 103 个文件全是 1.12.2；
  GitHub `releases.atom` 最新 tag 也是 v0.56.7（此后停更）。
- 「**AE2 UEL: Extended**」= **Beecube31 对 UEL 的下游 fork**（GitHub `beecupbe/ae2uel_extended`，
  CF 913322 / Modrinth `ae2-uel-extended`，最新 `…-v0.55.30-0.4.0.2-rv6-stable-7.jar` 2024-01-04，
  加料版、通道上限提到 256）—— **不是同一项目**，本次不选它。
- **UEL 是官方 rv6 的 fork** ⇒ 下面 §4.1 的官方 rv6 结论**预期仍然成立**，但**必须重验**（UEL 有新增 API 与修 bug），
  **重验在跑**（产物将放 `D:\ae2addon-build\tmp-ae2uel\`）。**UEL 回来后以它为准。**

### 4.1 官方 rv6-stable-7 基线（javap 实测，作为底稿）

**jar 身份**：`appliedenergistics2-rv6-stable-7.jar`，4,062,630 B，SHA256 `E7F7FBF6CAAF6206DBD958FB8F9185F874A405289E62AE3E97209B455A89FB1A`，
来源 CurseForge CDN（文件 id 2747063）。内部 `mcmod.info` 自称 `rv6-stable-7 / 1.12.2`；
jar 内 3128 条 SHA-256 摘要**逐条重算比对：`checked=3128 mismatches=0`**；class 文件 major version 52（Java 8）。
（⚠ 侧面事实：**Modrinth 上没有 1.12.2 的 AE2** —— 官方项目最低 1.18，所以别去 Modrinth 找。）

**存在**：`IMEInventory`、`IMEInventoryHandler`、`ICellInventory`、`ICellInventoryHandler`、`ICellHandler`、`ICellRegistry`、
`ISaveProvider`、`IStorageChannel`、`channels/IItemStorageChannel`、`data/IAEStack`、`data/IAEItemStack`、
`networking/security/IActionSource`、`config/Actionable`、`implementations/items/IAEItemPowerStorage`、
`me/cluster/implementations/CraftingCPUCluster`、`networking/crafting/ICraftingProvider`、`ICraftingMedium`。
**`appeng/api/storage/**` 共 26 个类**（`IStorageHelper`/`ICellWorkbenchItem`/`IMEMonitor`/`ICellGuiHandler` 等都在）。

**不存在**（jar 条目枚举，不是"javap 报错"）：
- **`appeng/crafting/execution/` 整个包不存在**，匹配 `CpuLogic` 的条目 **0 条** ⇒ **`CraftingCpuLogic` 没有**（那是 1.16+ 的架构）；
  1.12.2 的对应物是 `CraftingCPUCluster`（`updateCraftingLogic` / private `executeCrafting` / `cancel` / `submitJob` / `isBusy`）。
  ⇒ 与本次范围无关，但**记录在案：将来若要移植集成 CPU，等于从零重写**。
- **`appeng/api/stacks*` 条目 0 条** ⇒ `AEKey` / `AEItemKey` / `GenericStack` 这一套都没有。

**三条关键签名（javap 原文）**：

```
IMEInventory<T extends IAEStack<T>>:
  T injectItems(T, Actionable, IActionSource);
  T extractItems(T, Actionable, IActionSource);
  IItemList<T> getAvailableItems(IItemList<T>);
  IStorageChannel<T> getChannel();

ICellHandler:
  boolean isCell(ItemStack);
  <T extends IAEStack<T>> ICellInventoryHandler<T> getCellInventory(ItemStack, ISaveProvider, IStorageChannel<T>);
  default int getStatusForCell(ItemStack, ICellInventoryHandler<T>);
  default double cellIdleDrain(ItemStack, ICellInventoryHandler<T>);
  // 注册：AEApi.instance().registries().cell().addCellHandler(handler)

ISaveProvider:  void saveChanges(ICellInventory<?>);
```

### 4.2 ★★ 语义对照表（**移植时最容易出人命的一张表**）

| 场景 | 1.20.1（我们现在的代码） | 1.12.2 rv6（**字节码实测**） |
|---|---|---|
| 全部收下**且记账** | `insert` 返回 `amount`（收下的量） | `injectItems` 返回 **`null`**（剩余为 0） |
| 部分收下 | 返回收下的量 | 返回**剩余**的 `IAEItemStack`（copy 后 `lsub` 再 `areturn`） |
| **拒收**（我们的"禁止存入"） | **`return 0`** | **返回原样 input**（剩余 = 全部） |
| **锁定**（全收不上账/进黑洞） | **`return amount`** | **`return null`**（= 对方以为全收下了） |
| 取出 | `extract` 返回实际取出量 | `extractItems` 返回实际取出量，**没取出 = `null`**（不是 0！） |

⇒ **方向相反**：1.20 是"报收下多少"，rv6 是"报**没**收下多少"。我们整套拒收/锁定语义要**重新推一遍**，
并且**所有"判断有没有取到"的代码（`x > 0` / `x != null`）都得改**，否则把"没取到"当"取到了" = 复制/吞料。
（证据：`appeng.me.storage.BasicCellInventory` 的 `javap -c`；参照物 `CreativeCellInventory`——
inject 命中 `findPrecise` 就 `aconst_null`、没命中 `aload_1; areturn`。⚠ **SIMULATE 分支我没走完整字节码，别当结论**。）

**元件槽位校验的挂载点也变了**：1.20 我们靠 mixin 拦 `AppEngInternalInventory#getHost()`（1.16+ 才有的类）；
rv6 没有那个类，校验在 `IMEInventoryHandler` 上（`getSlot()` / `validForPass(int)` / `canAccept(T)` /
`isPrioritized(T)` / `getPriority()`）⇒ 那个 38 行的 `InfiniteCellSlotGuardMixin` 要重写挂载点。

### 4.3 UEL 与官方 rv6 的差异（javap 实测，2026-10-02）

**jar 身份**：`ae2-uel-v0.56.7.jar`，4,451,298 B，SHA256 `DFCA9F74CFEBB4E79806AE77CD6BCEDC30649BE2B0E505E06D629386B0C3074E`，
CurseForge 项目 570458 / 文件 id 6302098（beta，2025-03-14）；`mcmod.info` 自称 `v0.56.7 / 1.12.2`；
1659 个 class（rv6 1537）。**是 coremod**（manifest 带 `FMLCorePlugin: appeng.core.AE2ELCore`）。
⚠ UEL 的 manifest **没有 per-entry 校验摘要** ⇒ rv6 那种"3128 条自证"在 UEL 上做不了，只有"大小 + 自称版本"。

**逐字一致**（可放心按 rv6 底稿写）：`IMEInventory` / `ICellInventory` / `ICellInventoryHandler` / `ICellHandler` /
`ISaveProvider` / `IStorageChannel` / `IItemStorageChannel` / `IAEStack` / `IActionSource` / `IStorageCell` /
`AbstractStorageCell` / `BasicItemStorageCell` / `CreativeCellInventory` / `ICraftingProvider` / `ICraftingMedium` /
`ICraftingProviderHelper`；**`appeng/api/storage/**` 的类集合 = `(identical sets)`**（29 条目）。
`CraftingCpuLogic` 与 `appeng.api.stacks.*` 在 UEL 里**同样不存在** ✓（与 rv6 一致）。

**UEL 新增**（对我们有意义的部分）：
- `IMEInventoryHandler` **+`default boolean isSticky()`**（默认 false）—— 参照物：`DriveWatcher`/`NetworkInventoryHandler` 会用它。
- UEL 新增的 API 类只有 3 个：`api/implementations/IUpgradeableCellHost`、`IUpgradeableCellContainer`、`config/LockCraftingMode`
  （**没有新的 cell 接口**）。
- `IAEItemStack` +`equals(ItemStack)` / `getCachedItemStack(long)` / `setCachedItemStack(ItemStack)`。

**★★ UEL 特有雷区（rv6 没有，继承就编译不过）**：`appeng.me.storage.AbstractCellInventory`
- `private int storedItemCount` → **`private long storedItemCount`**
- `protected abstract boolean loadCellItem(NBTTagCompound, int)` → **`loadCellItem(NBTTagCompound, long)`**

⇒ 想继承 AE2 的 cell 基类（`AbstractCellInventory`/`BasicItemStorageCell`）来省事，**必须按 UEL 的 long 签名覆写**；
按 rv6 或按 1.20 的写法都会编译失败。**（未验证：这个 int→long 是哪一版改的，v0.55.x 没查。）**

**UEL 的"大容量"怎么做的（重要参照）**：它**没有**新的无限/超大元件（`appeng/items/storage/` 与 rv6 完全一致、
全 jar 匹配 `infinite|unlimited|creative|big|huge|mega|omni` 的类 rv6 全有、lang 差异只有颜色代码）。
它是**把计数放开成 long + 一整套超堆叠基础设施**：`AppEngInternalOversizedInventory`（被 `AppEngNetworkInventory` 用）、
`container/slot/SlotOversized`（被 `AEBaseContainer`/`ContainerInterface` 用）、`ItemVariantList`/`NormalItemVariantList`/
`FuzzyItemVariantList`、`helpers/NonBlockingItems`、`util/inv/BlockingItemHandler`、
以及 ASM transformer `core/transformer/ItemStackPatch`/`PacketBufferPatch`/`AE2ELTransformer`。
唯一的"无限"参照物仍是**创造元件** `CreativeCellInventory`（构造时把 `CellConfig` 里每个物品 `setStackSize(2147483647L)`；
inject 命中 → `null`，未命中 → 原样退回；extract 命中 → `copy()` 全量返还）。

### 4.4 由 API 结构推出的两条设计结论

1. **元件处理器必须"每通道一份"**：rv6/UEL 的 `ICellHandler.getCellInventory(ItemStack, ISaveProvider, IStorageChannel<T>)`
   是**带通道参数**的，注册表也按通道查询 ⇒ 我们那种"一个元件存万物"的万能盘，在 1.12.2 上要
   **为每个要支持的通道各注册一个 handler**（物品 / 流体 / 气体 / 魔力 …），各自包同一份底层 BigInteger 存储。
   （现实参照：`AE Additions` 的 Super Storage Cell 就是"物品/流体/化学物同格"。）
2. **`long` 与超堆叠是 UEL 给的免费礼物**：`storedItemCount` 已经是 long、且有 `AppEngInternalOversizedInventory`/
   `SlotOversized` 这套 ⇒ 我们的 BigInteger 存储往外报数时，**边界不必死夹在 `int`**；
   但仍要在"交给原版 ItemStack / 网络包"的地方夹（UEL 的 `PacketBufferPatch` 只能兜一部分，别赌）。

## 5. 兼容层在 1.12.2 还剩哪些（子任务实测，2026-10-02）

### 5.1 我们那 24 个兼容层的去留

**逐条判定的依据**：目标 mod 在 1.12.2 是否存在（子任务实测，**CF 侧用 gameVersion=1.12.2 复核**）。

| 我们的 compat（行数） | 目标 mod 在 1.12.2 | 结论 |
|---|---|---|
| `ChemicalCompat`(175) | 我们自己的抽象层（无外部目标） | **保留移植**（元件/物质球的通用化学物抽象） |
| `MekanismCompat`(332) / `MekanismChemCompat`(235) / `MekanismGasCompat`(43) | **Mekanism 9.8.3.390** ✓ | **移植但等于重写**：Mekanism 9 与 10 的 API 是两套 |
| `BotaniaCompat`(282) | **Botania r1.10-364.4** ✓ | 移植；⚠ 1.20 侧的"魔力 key"来自 Applied Botanics（1.18+ 没有），1.12.2 要改接 **Botania Applie** |
| `BloodMagicCompat`(160) | **BloodMagic 2.4.3-105** ✓ | 移植 |
| `EnderioCompat`(204) | **EnderIO 5.3.72** ✓ | 移植 |
| `EvilcraftCompat`(103) | **EvilCraft 0.10.78**（只在 CF）✓ | 移植 |
| `GregTechCompat`(268) / `GregTechRuntimeCompat`(182) | **GTCE 1.17.1.770**（+ GTCEu 2.8.10-beta）✓ | 移植（工作量最大的一家） |
| `NaturesAuraCompat`(96) | **Nature's Aura 18.1**（**只在 CF**，Modrinth 会漏报）✓ | 移植 |
| `ThermalCompat`(437) | **Thermal Foundation 2.6.7.1 / Expansion 5.5.7.1** ✓ | 移植 |
| `EMCCompat`(59) | **ProjectE PE1.4.1** ✓ | 移植 |
| `AeAddonRecipeCompat`(183) | 我们自己的（AE 附属配方处理） | 保留移植 |
| `CreateCompat`(106) / `CreateSequencedCompat`(332) | Create **官方无 1.12.2**（有非官方 Create Legacy） | **排除**（除非将来要接那个 backport） |
| `ProductiveBeesCompat`(851) | Productive Bees **无 1.12.2**（CF 文件数 0） | **排除** |
| `ArsNouveauCompat`(208) | Ars Nouveau **最低 1.16+** | **排除** |
| `AeResourceKeys`(106) / `AppFluxPowerCompat`(172) | Applied Botanics 1.18.2+ / AppliedFlux 1.20.1+ / Ars Énergistique 1.19.2+ | **排除**（1.12.2 没有这些桥；改接下面 5.2 的替代通道） |
| `AppliedECompat`(31) / `ExtendedAEPlusCompat`(105) | AppliedE / ExtendedAE-Plus 都是 1.20.1+ | **排除** |
| `SmithingCompat`(70) | 1.12.2 没有锻造台 | **排除** |
| `UselessModCompat`(223) | 待查（子任务未覆盖） | 待定 |
| `AeAddonRecipeCompat` 之外的 recipe 侧兼容（多数只服务千机） | —— | **本次范围外**（千机不做） |

### 5.2 1.12.2 + UEL 的"非物品通道"（决定 P4 能接什么）

| 通道 | addon | 状态 | 备注 |
|---|---|---|---|
| **气体/化学物**（Mekanism） | **Mekanism Energistics**（CF 1027681，0.1.5，2024-10-02） | 活着 | **源码就在 `github.com/AE2-UEL/` 组织下** ⇒ 与 UEL 同源，最可信 |
| 同格（物品/流体/化学物） | **AE Additions**（ExtraCells2 fork，1.3.8，2025-05-14） | 活着 | 有 Super Storage Cell 可作设计参照 |
| **魔力**（Botania） | **Botania Applie**（CF 1540084，1.3.0，**2026-09-06**） | 活着 | **1.12.2 上唯一的魔力↔AE2 通道**；依赖明写 UEL ≥ 0.56.7 |
| FE/能量 | **Applied Integrations**（CF 301924，2020-01-23） | **已停更** | 子任务 javap 证实 `EnergyStorageChannel implements IEnergyStorageChannel`、`AEEnergyStack implements IAEStack`；**与 UEL 的兼容性未验证** |
| 要素 | Thaumic Energistics Extended Life（2.5.2，2026-07-12） | 活着 | 本次范围外可不管 |
| EMC | Equivalent Energistics (LTS)（0.9.0，2026-05-24） | 活着 | 与我们的 `EMCCompat`/ProjectE 有关 |
| —— | Modrinth `aekey-legacy`（1.0.5，2026-03-25） | —— | **把 1.20.1 的 AEKey API 移植到 1.12.2** —— 只当**参考读物**，我们的核心不许依赖它 |

**已澄清的噪音**：`ME Capability Adapter` 不是能量通道（只是让 AE 通道穿过代理 capability）；
`Mekanism Eco Energistics` 不是 AE2 附属（是 Mekanism 太阳能板附属，名字骗人）。

**⚠ 方法学教训**：**Modrinth 会漏报老版本的 1.12.2 支持** —— Nature's Aura 在 Modrinth 最低 1.16.3，
CF 上却有 19 个 1.12.2 文件；EvilCraft / ProjectE 也只有 CF 有本体 ⇒ **查老版本必须 CF 侧复核**。

## 6. 分期计划（草案）

- **P0 · 环境**：装 JDK 8 → 建 1.12.2 独立仓库 → ForgeGradle 跑通空模组 → 拉 rv6 jar 做 API 对照。
  **验收**：`BUILD SUCCESSFUL` + 游戏能进 + 空模组被识别。
- **P1 · 最简元件**：一个只能存物品、模式 1（无限制）、无界面、无持久化优化的元件，接进元件工作台/驱动器。
  **验收**：能插进驱动器、能存能取、存档重进数量还在。
- **P2 · BigInteger 与三模式**：`Map<key, BigInteger>`、阈值、白名单、臻藏；持久化改 `WorldSavedData`。
  **验收**：单物品存到超过 `Long.MAX` 不溢出、**主模式存储完全隔离**（切换不搬家、各自切回原样；2026-10-03 更正，见 p2 规格尾部）。
- **P3 · 界面 + 网络**：Mode2 配置界面（两页签/分类列表/拒收）与对应网络包移植。
  **验收**：与 1.20 版逐项对齐今天验收单 A/B 两组。
- **P4 · 兼容层**：按 §5 结论只做"1.12.2 上确实存在且元件用得着"的那几家。
  **验收**：每家给一条最小复现（存进去 / 取出来 / 拒收生效）。

## 7. 风险（先列，不等取证）

1. **语义方向反了就是吞料**：`injectItems` 返回值方向必须用字节码钉死（见 §4）。
2. **BigInteger 与 rv6 的 long 边界**：rv6 的 `IAEItemStack` 是 long ⇒ 界面/网络传参要在边界处夹，别把"无限"当真值传出去。
3. **1.12.2 的元件槽位校验挂载点不同**：现在靠 mixin 拦 `AppEngInternalInventory#getHost()`（1.16+ 才有的类），rv6 要换 ICellHandler/驱动器校验层。
4. **旧 AE2 addon 生态不等于今天**：Applied Botanics/Mekanistics/Flux/Create 那一批 1.12.2 都没有，兼容层可能大幅缩水 ⇒ **先看 §5 再定 P4 值不值得做**。
5. **构建隔离**：1.12.2 的 ForgeGradle 与 1.20.1 的 Gradle 版本冲突，必须独立目录 + 独立 JDK，不能共用 `D:\ae2addon-build`。

## 8. 待办

- [x] **装 JDK 8** —— `C:\Program Files\Eclipse Adoptium\jdk8u504-b01`（Temurin 1.8.0_504，已验，未动 `JAVA_HOME`）
- [x] **官方 rv6-stable-7 API 基线取证** —— 产物 `D:\ae2addon-build\tmp-ae2rv6\`
- [x] **AE2-UEL 复验（sensei 更正后的目标）** —— 产物 `D:\ae2addon-build\tmp-ae2uel\`；结论见 §4.3
- [x] **1.12.2 兼容层调查** —— 报告 `D:\大爱丽丝工作区\reports\ae2-1122-mod-audit-2026-10-02.md`；结论见 §5
- [x] **独立仓库 + 构建目录**：`D:\环境\ae2-addon-1.12.2` + `D:\ae2addon-build-1122`（ASCII 路径，robocopy 镜像）
- [x] **P0 跑通空模组 —— 绿**（2026-10-02 17:16）：`BUILD SUCCESSFUL` ✓ + `:reobfJar` ✓ + `stable_39` 生效 ✓，
      产物 `ae2-addon-1122-0.1.0-1.12.2.jar`（内容是 MDK 自带 examplemod，2.4 KB）。
      **一条命令构建**：`& 'D:\大爱丽丝工作区\tools\build-1122.ps1'`（钉 JDK 8 + ASCII 镜像 + 按日志判成败）
- [ ] **P1 规格**：最简元件（只存物品 / 模式 1 / 无界面 / 无持久化优化）——
      必须按 §4.2 的口径把返回值语义**反过来**，并且**每通道注册一个 handler**（§4.4-1）
- [ ] 动手写 P1 前必须补的两条取证：`injectItems` 的 **SIMULATE 分支**字节码；UEL 的 `loadCellItem(NBTTagCompound, long)` 实际覆写姿势

### 8.1 P0 路上踩的四个坑（**下次别重踩**）

1. **Gradle 发行包从官方源会被截断**：`services.gradle.org` 只下到 549,805 B（正确 76,334,709 B / 215 条目）
   → `ZipException`。**用腾讯云镜像** `https://mirrors.cloud.tencent.com/gradle/gradle-4.9-bin.zip`，
   手动装进 `~/.gradle/wrapper/dists/<dist>/`（好 zip + 解压 + `<zip>.ok`），并把 `distributionUrl` 指镜像。
2. **forge maven 偶发 `Remote host terminated the handshake`**（实测打掉 javaxdelta/trove/jsr305）
   ⇒ `buildscript.repositories` **前置** BMCLAPI（`https://bmclapi2.bangbang93.com/maven/`）+ 阿里云 public/central。
3. **PowerShell 假失败**：`$ErrorActionPreference='Stop'` + 原生命令写 stderr（`java -version`、gradlew）
   → 抛 `NativeCommandError` 打死脚本；**gradle 明明 BUILD SUCCESSFUL 却报 exit 1** 就是这个。
   ⇒ 脚本里用 `'Continue'`，成败**显式**判（`throw` / grep `BUILD SUCCESSFUL`）。
4. **`write`/`edit` 工具写出的 `.ps1` 没 BOM** → PS 5.1 按 GBK 读 → 脚本里的中文路径全乱（robocopy exit 16）
   ⇒ 补 BOM：`[IO.File]::WriteAllText($p,$t,(New-Object Text.UTF8Encoding($true)))`，验前 3 字节 `239,187,191`。
