# P4 · 1.12.2 无限元件「多存储类型」方案（2026-10-03 定稿）

> sensei 三点决定：**① 形态 = 一个万能盘吃所有通道**；**② 规则 = 只保留模组规则 + 白名单/黑名单/阈值/数量锁定**
> （矿词规则仅对物品通道生效）；**③ 今晚第一步 = 流体**。
> 补两条（2026-10-03 下午，起因是 sensei 提醒「CrazyAE 也加了其他类型的存储元件」）：
> **④ 本期只做 物品/流体/气体/源质**，CrazyAE 的魔力/能量/经验**留后**（但 `CellChannel` 表要设计成
> "加一行就多一个通道"）；**⑤ 与 CrazyAE 元件共存即可、各列各的**（不去抢同一资源）。
> 本文档里所有 API 形状均来自 **javap 实测**（AE2-UEL v0.56.7 / MekanismEnergistics 0.1.5 /
> ThaumicEnergistics 2.5.2，见 §1），不是记忆。

---

## 1. 通道清单（实证）

| 通道 | 从哪来 | 关键类 | 本机实例 |
|---|---|---|---|
| 物品 | AE2-UEL 原生 | `IItemStorageChannel` / `ApiStorage$ItemStorageChannel` | ✅ |
| **流体** | AE2-UEL 原生 | `IFluidStorageChannel` / `ApiStorage$FluidStorageChannel` | ✅ |
| **气体** | MekanismEnergistics 0.1.5 | `com.mekeng.github.common.me.storage.IGasStorageChannel`、`IAEGasStack`/`AEGasStack` | ✅ |
| **源质** | ThaumicEnergistics 2.5.2 | `thaumicenergistics.api.storage.IEssentiaStorageChannel`、`IAEEssentiaStack` | ✅ |
| 魔力 | 需要 Applied Botanics（appbot） | — | ❌ 没装（Botania 本身有） |
| EMC / FE | 需要 AppliedE / Applied Flux | — | ❌ 没装 |

**结论**：1.12.2 这条线做 **物品 / 流体 / 气体 / 源质** 四个通道；魔力、EMC、FE **留到装了对应桥 mod 再做**
（按项目既有做法：`Loader.isModLoaded` 门 + 三段式（门类/工厂类/compat 类）避免缺类崩溃）。

### 1.1 ⚠ 已有第三方通道：CrazyAE（2026-10-03 补查，sensei 提醒）

`CrazyAE-1.12.2-v0.6.0.2.jar` **已装在实例里，且它的功能开关全部 enabled**（`config/crazyae-features.cfg`：
`mana_cells` / `mana_dense_cells` / `mega_mana_dense_cells` / `energy_cells` / `energy_dense_cells` /
`mega_energy_dense_cells` / `improved_energy_cells` / `mana_term` / `energy_term` / `mana_buses` /
`energy_buses` / `improved_drive` / `part_drive` …），它自己就带这些通道：

| CrazyAE 通道 | 接口 | 说明 |
|---|---|---|
| 魔力 | `dev.beecube31.crazyae2.core.api.storage.IManaStorageChannel`（+ `ManaAsAEStack`） | Botania 魔力（它开了 `botania_*` 集成） |
| 能量 | `…core.api.storage.energy.IEnergyStorageChannel`（+ `FEEnergyAsAEStack` / `EUEnergyAsAEStack` / `QE…` / `EF…` / `SE…`） | 多种能量 stack |
| 经验 | `…core.api.storage.exp.IExperienceStorageChannel`（+ `ExperienceAsAEStack`） | 经验 |

**由此产生两条硬规矩**：
1. **不要自己造魔力/能量/经验通道** —— 同一个资源出现两条通道，终端里就会有两套键/两套元件，
   玩家看到重复且互不相通。要支持就**接 CrazyAE 的通道接口**（compileOnly + 三段式可选依赖）。
2. **驱动器侧已确认按通道枚举**（多通道可行性的关键）：
   - AE2 原版 `appeng.tile.storage.TileDrive`：`Map<IStorageChannel<?>, List<IMEInventoryHandler>> inventoryHandlers`
     + `public List<IMEInventoryHandler> getCellArray(IStorageChannel)` + `lambda$updateState$0(IStorageChannel)`
     ⇒ `updateState` **逐通道**回调每个 cell handler；
   - CrazyAE 的 `TileImprovedDrive` 是**同形状的克隆**（`getCellArray(IStorageChannel)` + 同样的 Map + 同样的 lambda），
     `DriveWatcherImproved` 与它自家 watcher 配套 ⇒ 在它的驱动器里多通道同样成立。
3. **它抢不走我们的元件**（javap 实证）：CrazyAE 只注册两个 handler，且 `isCell` 都是
   `instanceof ManaItemCreativeCell` / `instanceof MultiEnergyItemCreativeCell`（它自己的创造元件），
   我们的 `isCell` 只看 `ItemUnlimitedCell` ⇒ 与它的注册顺序无关。
4. ⚠ 但它有 `mixins/core/MixinDriveWatcher`（改 `appeng/me/storage/DriveWatcher`，涉及
   `TileDrive`/`IStorageChannel`/`IMEInventory`/`IStorageGrid`）⇒ **验收单里必须加一条**：
   在**它的改进驱动器**里，我们的元件的**每个通道**都能被 ME 网络发现（不只是物品通道）。


### rv6 的 API 形状（与 1.20 不同！）

```
ICellHandler
  boolean isCell(ItemStack)
  <T extends IAEStack<T>> ICellInventoryHandler<T> getCellInventory(ItemStack, ISaveProvider, IStorageChannel<T>)
  <T> int  getStatusForCell(ItemStack, ICellInventoryHandler<T>)
  <T> double cellIdleDrain(ItemStack, ICellInventoryHandler<T>)

ICellInventory<T extends IAEStack<T>> extends IMEInventory<T>
  ItemStack getItemStack() / double getIdleDrain() / FuzzyMode getFuzzyMode()
  IItemHandler getConfigInventory() / getUpgradesInventory()
  int getBytesPerType() / boolean canHoldNewItem() / long getTotalBytes()/getFreeBytes()/getUsedBytes()
  long getTotalItemTypes()/getStoredItemCount()/getStoredItemTypes()/getRemainingItemTypes()
  long getRemainingItemCount() / int getUnusedItemCount() / int getStatusForCell() / void persist()

IMEInventory<T>
  T injectItems(T, Actionable, IActionSource)   // 返回值仍是"剩余"
  T extractItems(T, Actionable, IActionSource)
  IItemList<T> getAvailableItems(IItemList<T>)
  IStorageChannel<T> getChannel()

IStorageChannel<T>
  IItemList<T> createList() / T createStack(Object) / T readFromPacket(ByteBuf) / T createFromNBT(NBTTagCompound)
  int transferFactor() / int getUnitsPerByte()

IAEStack<T>（四种键的公共父接口）
  getStackSize/setStackSize/inc/dec/add/copy/empty/reset/isMeaningful
  writeToNBT/writeToPacket/getChannel/isItem/isFluid/asItemStackRepresentation
```

- **要点 1**：`ICellHandler` 是**按通道**调用的 ⇒ 一个物品可以服务多个通道，只要按 `channel` 返回不同的
  `ICellInventory` 包装。这就是"万能盘吃多通道"的接入点（现状：`UnlimitedCellHandler` 里
  `if (channel != itemChannel()) return null;` 那两行）。
- **要点 2**：**1.20 的形状不能照抄** —— 1.20（AE2 15.x）的 `StorageCell`/`ICellHandler` 已统一为全通道
  （一次报所有通道、只注册一次），所以那边 `UnlimitedCellInventory implements StorageCell` 直接写。
  rv6 必须**每通道一个 `ICellInventory<T>` 薄壳**。
- **要点 3**：判定/记账逻辑可以完全泛型化 —— 它只用得到 `IAEStack<T>` 的公共方法
  （尺寸/复制/写 NBT/身份），通道特有的只有「键怎么造、有哪些键、显示名怎么取」。
- **要点 4**：包装用 AE2 自己的 `BasicCellInventoryHandler<>(inv, channel)`（`CreativeCellInventory` 也用它）；
  驱动器 LED、升级卡扫描、分区列表都依赖 `getCellInv()` 非空。

---

## 2. 重构设计（判定核心通道无关）

```
com.ae2addon.cell
  CellChannel              通道描述表：id、IStorageChannel、键枚举器、命名空间取法、显示名、数量单位
  ChannelCellCore<T>       通道无关的判定与记账（由现 UnlimitedCellInventory 抽出）
  ItemCellInventory<T>     物品薄壳（现有类改成委托）
  FluidCellInventory       流体薄壳（今晚第一步）
  GasCellInventory         气体薄壳（桥 mod，三段式可选依赖）
  EssentiaCellInventory    源质薄壳（同上）
```

**`ChannelCellCore<T extends IAEStack<T>>` 负责**（= 现有逻辑原样搬过来，只把 `IAEItemStack` 换成 `T`）：

- 三种主模式 + 工作模式 + 阈值 + 每键阈值；`s1`/`s2`/`ca`/`wl`/`ul`/`touched`/`qtyLocked`/`blacklist`/`m3`
- `injectItems`/`extractItems` 的 rv6 语义（返回剩余、SIMULATE 不改状态）
- 列表构造：无限来源（`wl ∪ ul ∪ 规则命中`）+ 有限行，**永不把有限数量加到无限条目上**（`findPrecise` 判定）
- `clamp(BigInteger)`：对外（AE2 的 long）一律夹到 `Long.MAX_VALUE`
- `reconcileCommitted()`：孤儿承诺额度退回**本通道本模式的房间**
- 摘要（tooltip 的 `_t`/`_b2`）

**`CellChannel` 负责**（唯一按通道不同的部分）：

| 能力 | 物品 | 流体 | 气体 | 源质 |
|---|---|---|---|---|
| 枚举全部键（mode3 / 立即规则扫描） | `Item.REGISTRY` + `getSubItems` 全 meta | `FluidRegistry.getRegisteredFluids()` | Mekanism 气体注册表（反射/compileOnly） | Thaumcraft aspect 表 |
| 命名空间（模组规则用） | `item.getRegistryName().getNamespace()` | `fluid.getName()` 的 `:` 前缀 | 气体名的 `:` 前缀 | aspect 无命名空间 ⇒ 只按名字 |
| 显示名 | `stack.getDisplayName()` | `fluid.getLocalizedName(FluidStack)` | 气体本地化名 | aspect 本地化名 |
| 数量单位 | 个 | **mB**（1 桶 = 1000） | mB / 单位 | 单位（vis 类） |

**规则语义（按 sensei 决定）**：**矿词规则只对物品通道生效**；四个通道都支持
**模组规则**（按命名空间）、白名单、黑名单、阈值、数量锁定。非物品通道的"模组规则"命中判定 = 命名空间相等。

**存档**：`CellStorageData` 里 **每通道一个子标签**（`ch_item` / `ch_fluid` / `ch_gas` / `ch_essentia`）；
**旧存档没有子标签 ⇒ 按物品通道读**（向后兼容，`FORMAT_VERSION` 不需要 +1，读时兼容即可）。
`CellWorldData` 的键仍是 uuid，值里多一层通道标签。

**通道隔离**：与今天的「主模式隔离」同理 —— 通道之间也完全隔离（同通道内才是模式隔离）。
读数字段（`getStoredItemCount` 等）一律**只读当前通道 + 当前模式那一间**，否则又是"两边漂移"。

**界面 / 命令**：
- 面板加**通道切换**（物品/流体/气体/源质），分类条之上或标题行；
- 命令 `/ae2addoncell channel <item|fluid|gas|essentia>`（backdoor 保留）；
- tooltip 按通道分行（`无限存储元件 [无限制] · 流体`）。

---

## 3. 今晚第一步：流体（任务清单）

1. **抽 `CellChannel` + `ChannelCellCore<T>`**：先把物品通道改成走新核心，**行为零变化**（自检 66 项必须仍全绿）
   —— 这一步是纯重构，验收门就是"66/0 不变"。
2. **流体薄壳 `FluidCellInventory`**：`ICellInventory<IAEFluidStack>`；`createStack(FluidStack)`；
   mode 3 枚举 `FluidRegistry`（先量一下数量，`/ae2addoncelltest` 里报耗时，沿用 H2 的做法）。
3. **注册**：`UnlimitedCellHandler.getCellInventory` 按 `channel` 分派；流体通道用
   `new BasicCellInventoryHandler<>(new FluidCellInventory(stack, saveProvider), fluidChannel)`。
4. **存档**：`ch_fluid` 子标签 + 旧档兼容（现有物品档必须能照常读）。
5. **自检 K 组（流体）**：注入/取出 rv6 语义、阈值升级、无限（wl/ul）、模组规则命中、删除规则后额度归位、
   主模式隔离在流体上同样成立、`getStoredItemTypes()` 与终端列出条数一致、持久化往返、mode3 枚举耗时。
6. **手工项（sensei）**：流体盘插驱动器 → ME 终端里能看能取；与 AE2 原生流体元件共存时列表不溢出
   （今天修过的"两片盘聚合"在流体上同样要成立）。

**待定/风险**：
- mode 3 对流体 = 枚举**全部注册流体**：数量可能上千（含 Mekanism/Thermal 的流体），耗时必须先量再定
  （沿用 H2 的门限做法：测出来再决定要不要按需列出）。
- 流体的"阈值"单位是 mB（默认 65536 mB = 65.5 桶）—— 需要一个 per-channel 默认值，UI 上要标单位。
- 气体/源质薄壳要**三段式可选依赖**（缺 MekanismEnergistics / ThaumicEnergistics 时不能崩）。

## 4. 分工

- **爱丽丝**：`CellChannel` / `ChannelCellCore` 泛型化、注册与存档、自检 K 组、构建与独立验收。
- **codex**：面板通道切换 + 网络包（type 编号要新增且 encode/decode 成对）+ 语言键（纯追加）。
- 规矩沿用：codex 不碰 `cell/`、`data/`、`util/`、`CommandCellSelfTest.java`、构建配置；爱丽丝代提交。
