# P1 规格：1.12.2 最简无限存储元件

**状态：可实施（规格冻结）** — 2026-10-02 写。前置：`port-1.12.2-plan.md`（§4 API 事实、§6 分期）。
本文件是 P1 的**唯一实现依据**；凡是本文件里写了「证据」的行，都是我实际 `javap` 过字节码/枚举过 jar 条目得到的，不是回忆。

---

## 0. 一页结论（先看这段）

1. **元件物品不能实现 `IStorageCell`** —— 一实现就会被 AE2 自带的 `BasicCellHandler` 抢走，我们的无限语义就没了。
2. **存储类直接实现 `ICellInventory<IAEItemStack>`**（不继承 `AbstractCellInventory`）—— 基类的 `storedItemCount` 是 private 且按字节记账，和"无限"冲突。
3. **返回值语义是反的**：全收 = `null`，拒收 = 原样返回输入，没取到 = `null`。**SIMULATE 也要返回同样的剩余量**（只不改状态）。
4. 对外报数一律夹到 `long`；内部用 `BigInteger`。
5. 验收靠一条游戏内自检命令 `/ae2addoncelltest` 打断言，不靠肉眼。

---

## 1. 目标环境（全部实测身份）

| 项 | 值 | 证据 |
|---|---|---|
| Forge | `1.12.2-14.23.5.2859` | 实例 `axolotl_config.json` 的 `loader_version` |
| 映射 | MCP `stable_39` (`39-1.12`) | 构建日志坐标 `..._mapped_stable_39-1.12` |
| JDK / Gradle / FG | JDK 8 / Gradle 4.9 / ForgeGradle **3.0.197** | gradle 缓存 `ForgeGradle/3.0.197` |
| AE2 | **AE2-UEL v0.56.7**，4,451,298 B | SHA256 `DFCA9F74CFEBB4E79806AE77CD6BCEDC30649BE2B0E505E06D629386B0C3074E`；与测试实例里的副本**逐字节一致** |
| 编译依赖 | `compile fg.deobf('curse.maven:ae2-extended-life-570458:6302098')` | 2026-10-02 实跑 `BUILD SUCCESSFUL` + 探针类编译通过 |
| 构建命令 | `& 'D:\大爱丽丝工作区\tools\build-1122.ps1'` | 一条命令，按日志判成败 |
| 测试实例 | `D:\360安全浏览器下载\mmcc\profiles\1.12.2测试` | 已装 UEL 0.56.7；`ae2-uel-extended-*.jar` 已移到 `_mods-disabled\`（同 modid 会 Duplicate mod 崩） |

---

## 2. 范围

**做**：物品通道、模式 1（无限制）、无界面、无升级卡、持久化最简（直接写元件物品 NBT）。
**不做**：阈值/白名单/臻藏/三模式、GUI、网络包、`WorldSavedData`、流体/气体/魔力通道、无限接口、千机、集成 CPU。

存储核心**直接上 `BigInteger`**（不是 long）——因为 P2 只要在它上面加模式逻辑即可，不必重写引擎。

---

## 3. ★ API 事实（带证据；写代码前必须认这几条）

### 3.1 注册链路（怎么让驱动器认我们的元件）

```
AEApi.instance().registries().cell()            → ICellRegistry
    .addCellHandler(ICellHandler)               // 追加到一个 ArrayList
    .getCellInventory(stack, saveProvider, channel)  // 按注册顺序遍历，第一个 isCell() 为真者胜
```
- `ICellRegistry.getCellInventory(...)` 的实现（`appeng.core.features.registries.cell.CellRegistry`）是**遍历一个 ArrayList**，所以顺序决定归属。
- `ICellHandler.getCellInventory` 返回 `ICellInventoryHandler<T>`；物品通道直接用 AE2 现成的包装器
  `new BasicCellInventoryHandler(inv, channel)`（构造签名 `(IMEInventory, IStorageChannel<T>)`）。
  **证据**：`CreativeCellInventory.getCell` 就是这么干的。

#### 3.1.1 ★★ 注册时机：**必须在 init，绝不能放 preInit**（2026-10-02 实机崩过）

`CellRegistry.addCellHandler` 的最后一行是一个断言（`javap -c -l`，行号表确认 line 50）：

```java
public void addCellHandler(ICellHandler handler) {
    Preconditions.checkNotNull(handler, "Called before FMLInitializationEvent.");        // line 44
    Preconditions.checkArgument(!handlers.contains(handler),
            "Tried to register the same handler instance twice.");                       // line 45
    handlers.add(handler);                                                               // line 47
    Verify.verify(handlers.get(0) instanceof BasicCellHandler);                           // line 50 ← 崩这里
}
```

它要求 **AE2 自己的 `BasicCellHandler` 必须位于 `handlers[0]`**；而 `CellRegistry` 构造时两个 list 都是空的。

**AE2 在哪里放它自己的 handler**：`appeng.core.Registration.initialize(FMLInitializationEvent, File)` 里连续调用
`addCellHandler(new BasicCellHandler())`、`addCellHandler(new CreativeCellHandler())`
（字节码常量池实证）⇒ **AE2 是在自己的 init 阶段注册的，不是 preInit。**

**所以**：谁在 preInit 里 `addCellHandler`，谁就抢到 index 0 → 直接
`LoaderExceptionModCrash: Caught exception from AE2 Addon / com.google.common.base.VerifyException`，
**游戏在开窗之前就崩**，而且堆栈里只有一个光秃秃的 `Verify.verify`，不读 `CellRegistry` 字节码根本看不出原因。

正确做法：放在 **`FMLInitializationEvent`**（`@Mod` 实例方法 `init`）。
安全性依据：FML 按依赖顺序初始化，我们的 `@Mod(dependencies = "required-after:appliedenergistics2")`
保证 AE2 的 init 先跑完 ⇒ 轮到我们时 `handlers[0]` 已经是 `BasicCellHandler`。

> 旁证：AE2 这个注册表**只对 `addCellHandler` 有这个 index 断言**；`addCellGuiHandler` 就是裸 `list.add`，
> 所以 GUI handler 早注册没事 —— 别把两者的时机混为一谈。

### 3.2 ★ 不许实现 `IStorageCell`（否则被抢）

- `appeng.core.features.registries.cell.BasicCellHandler.isCell(stack)` → `BasicCellInventory.isCell(stack)` → `getStorageCell(stack) != null`，而 `getStorageCell` 就是 **`stack.getItem() instanceof IStorageCell`**。
- `BasicCellHandler` 是 AE2 自己注册的，排在前面。**我们的元件一旦实现 `IStorageCell`，第一个命中的是 AE2，我们的 handler 永远不会被调用。**

⇒ **P1 的元件类只 `extends Item`，绝不 `implements IStorageCell` / `ICellWorkbenchItem`。**

### 3.3 驱动器 LED 依赖 `getCellInv()`

- `ICellHandler.getStatusForCell` 默认实现（接口 default）：`getCellInv() == null → 0`；否则取 `getCellInv().getStatusForCell()`，若为 1 且 `isPreformatted()` 则为 2。
- `BasicCellInventoryHandler.getCellInv()` 只在 internal（解开 `MEPassThrough` 后）`instanceof ICellInventory` 时返回它，否则 **null**。

⇒ **存储类必须实现 `ICellInventory`**，否则驱动器那一格永远显示"无元件"状态（status 0）。

### 3.4 ★★ 返回值语义（`javap -c` 实测，含 SIMULATE 分支）

参照物 `appeng.me.storage.BasicCellInventory`（未命中/已有条目/新条目三条路径 + `CreativeCellInventory`）：

| 情况 | 返回值 | SIMULATE 与 MODULATE 的差别 |
|---|---|---|
| 全收 | **`null`** | 无差别，都返回 `null` |
| 部分收 | **剩余的 `copy`**（`size - 收下`） | **无差别**：剩余量在 actionable 判断**之前**就算好了，只有"写状态"被 `if (actionable == MODULATE)` 门控 |
| 拒收 | **原样 `input`（同一个引用，不是 copy）** | 无差别 |

**取出 `extractItems`**：
| 情况 | 返回值 |
|---|---|
| 取到 | `copy(input).setStackSize(实际取出量)` |
| **没取到 / 没有该物品** | **`null`**（**不是 0**） |
| 传 null 进来 | `null` |

⇒ 任何 `x > 0` 的判断都得改成 `x != null`。**把"没取到"当"取到了" = 凭空造物。**

### 3.5 存档链路（我们的内存数据怎么落盘）

`AbstractCellInventory.saveChanges()` 的形状（实测）：
```java
storedItemTypes = (short) cellItems.size();
storedItemCount = 0;  for (s : cellItems) storedItemCount += s.getStackSize();
isPersisted = false;
if (container != null) container.saveChanges(this);   // → TileDrive.saveChanges(ICellInventory<?>)
else                   persist();                      // 没有宿主（在背包/手里）→ 直接写物品 NBT
```
- `ISaveProvider.saveChanges(ICellInventory<?>)`；`TileDrive` 就是 `ISaveProvider`，`TileDrive` 用它把元件物品写进自己的 NBT。
- `Platform.openNbtData(ItemStack)`：`getTagCompound()` 为 null 时**会 `setTagCompound(new NBTTagCompound())` 写回**（实测字节码），所以拿到返回值直接改就等于改了物品。

⇒ 我们的 `saveChanges()` 照抄这个形状：先写 NBT，再 `container.saveChanges(this)`。

### 3.6 UEL 与官方 rv6 的差异（会直接影响写代码的）

- `AbstractCellInventory`：`private long storedItemCount`（rv6 是 int）、`protected abstract boolean loadCellItem(NBTTagCompound, long)`。
  **但 P1 不继承它，所以这两条不咬我们** —— 记在这里是为了 P2 若想省事时别踩。
- `ICellHandler` 在 UEL 里没有新方法；`IMEInventoryHandler` 多了 `default boolean isSticky()`（默认 false，不用管）。

### 3.7 身份语义（key 必须和 AE2 一致）

- `AEItemStack.hashCode()` = `sharedStack.hashCode()`；`AEItemStack.equals(Object)` = `isSameType(...)` ⇒ **身份是"物品+损伤+NBT"，不含数量**。
- 我们的存储 map **必须用 `AEItemStack` 当 key**（不能用 `ItemStack`，它没实现 equals/hashCode）。
  理由：key 身份只要和 AE2 自己的口径不一致，终端里就会出现重复格/分裂。

### 3.8 1.12.2 的资源约定（照着同代附属抄）

- 语言文件是 **`.lang`**（不是 json）：`assets/<modid>/lang/en_us.lang`、`zh_cn.lang`。
- 键名：`setUnlocalizedName("<modid>:<name>")` ⇒ 键 = **`item.<modid>:<name>.name`**。
  **证据**：Mekanism Energistics 的 `en_us.lang` 里就是 `item.mekeng:gas_cell_1k.name=...`，其 `initItem` 里 `setUnlocalizedName("mekeng:" + name)`。
- 物品注册用 `RegistryEvent.Register<Item>`；模型用 `ModelRegistryEvent`。

---

## 4. 类清单（P1 只这 4 个文件 + 资源）

| 文件 | 职责 |
|---|---|
| `com/ae2addon/AE2Addon.java` | `@Mod` 主类：**init 注册 cell handler**（⚠ 绝不能放 preInit，见 §3.1.1）；订阅 `RegistryEvent.Register<Item>` 注册物品；`ModelRegistryEvent` 注册模型；`serverStarting` 注册自检命令 |
| `com/ae2addon/item/ItemUnlimitedCell.java` | 元件物品，**只 `extends Item`**（见 §3.2），带 tooltip 显示已存种类/总量 |
| `com/ae2addon/cell/UnlimitedCellHandler.java` | `implements ICellHandler`；`isCell` / `getCellInventory` |
| `com/ae2addon/cell/UnlimitedCellInventory.java` | `implements ICellInventory<IAEItemStack>`；BigInteger 存储 + 语义实现 + NBT 读写 |
| `com/ae2addon/command/CommandCellSelfTest.java` | 游戏内自检命令（§8），P1 验收的主要证据来源 |

删除：`com/example/examplemod/ExampleMod.java`（MDK 示例）、`com/ae2addon/ApiProbe.java`（临时探针）。

---

## 5. 逐方法行为（照这个写，不用再想）

### 5.1 `UnlimitedCellHandler implements ICellHandler`

| 方法 | 行为 |
|---|---|
| `isCell(ItemStack s)` | `!s.isEmpty() && s.getItem() == ModItems.UNLIMITED_CELL` |
| `getCellInventory(s, provider, channel)` | 若 `channel != 物品通道` 或 `!isCell(s)` → **`null`**；否则 `new BasicCellInventoryHandler(new UnlimitedCellInventory(s, provider), channel)`（注意 `ICellInventory<IAEItemStack>` → `<T>` 需要一次受检强转，因为泛型在这里无法静态证明） |
| `getStatusForCell` / `cellIdleDrain` | **不覆写**（默认实现已经转发给 `getCellInv()`，够用） |

物品通道取法（实测可编译）：
```java
AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class)
```

### 5.2 `UnlimitedCellInventory implements ICellInventory<IAEItemStack>`

字段：`ItemStack cellStack`、`ISaveProvider container`、`IStorageChannel<IAEItemStack> channel`、
`LinkedHashMap<AEItemStack, BigInteger> storage`、`IItemHandler configInv = new ItemStackHandler(0)`、`IItemHandler upgradeInv = new ItemStackHandler(0)`。

> ⚠ `getConfigInventory()` / `getUpgradesInventory()` **不能返回 null** —— `BasicCellInventoryHandler` 构造时会无条件遍历它们（`getSlots()`），且这正好让分区列表保持为空（= 不做白名单过滤）。

| 方法 | 行为 |
|---|---|
| **`injectItems(input, mode, src)`** | `input == null \|\| size <= 0` → `null`；`accepted = min(size, 还能收多少)`（P1 无限制 ⇒ `accepted = size`）；`remainder = size - accepted`；**`remainder == 0` → `null`；否则 `copy(input).setStackSize(remainder)`**；仅当 `mode == MODULATE` 时才 `storage.put(key, cur + accepted)` 并 `saveChanges()` |
| **`extractItems(input, mode, src)`** | `input == null` → `null`；`cur = storage.get(key)`，**`cur == null` → `null`**；`taken = min(input.size, cur)`（夹到 long）；`taken <= 0` → `null`；`mode == MODULATE` → 扣减、归零则删 key、`saveChanges()`；返回 `copy(input).setStackSize(taken)` |
| `getAvailableItems(out)` | 遍历 map，`AEItemStack`（size = clamp(Long.MAX)）逐个 `out.add(...)`，`return out` |
| `getChannel()` | 物品通道 |
| `getItemStack()` | `cellStack` |
| `getIdleDrain()` | `0.0`（P1 不做耗电） |
| `getFuzzyMode()` | `FuzzyMode.IGNORE_ALL` |
| `getBytesPerType()` | `0` |
| `canHoldNewItem()` | `true` |
| `getTotalBytes()` / `getFreeBytes()` | `Long.MAX_VALUE`（**返回值是 long，可以给大**） |
| `getUsedBytes()` | `0` |
| `getTotalItemTypes()` | `Integer.MAX_VALUE` |
| `getStoredItemCount()` | 总和，**clamp 到 `Long.MAX_VALUE`** |
| `getStoredItemTypes()` | `storage.size()` |
| `getRemainingItemTypes()` | `Integer.MAX_VALUE - size()` |
| `getRemainingItemCount()` | `Long.MAX_VALUE` |
| `getUnusedItemCount()` | `0` |
| `getStatusForCell()` | `1`（= 正常；驱动器 LED 靠它） |
| `persist()` | 写 NBT（无宿主时的路径，见 §3.5） |

内部私有：
```java
private void saveChanges() { writeToNbt(); if (container != null) container.saveChanges(this); }
```

### 5.3 关键实现注记

- **`remainder` 必须先算**，再判 `MODULATE` —— 这正是 AE2 自己的顺序（§3.4），照抄就不会在 SIMULATE 下行为不一致。
- 拒收时返回的是**传进来的那个引用**（AE2 自己也这样），别自作聪明 copy。
- `BigInteger` 与 `long` 的换算集中在一个工具方法里（`clamp(BigInteger) -> long`），别散落各处。

---

## 6. NBT schema（放在元件物品自己的 tag 里）

```
<cell ItemStack tag>:
  "ae2addon:cell": {
      "v": 1,                                  // int，格式版本
      "items": [                               // NBTTagList of NBTTagCompound
          { "key": <AEItemStack.writeToNBT 的结果>, "count": "123456789012345678901234567890" }
      ]
  }
```
- 用 `Platform.openNbtData(cellStack)` 拿根 tag（它会写回，见 §3.5）。
- `count` 存**十进制字符串**（BigInteger 无原生 NBT 类型）。
- 读的时候：`v` 不认识就当空盘并打日志；单个条目坏了跳过该条、不整个放弃。
- 载入在构造里做一次（`loadFromNbt()`）。

---

## 7. 边界与钳制

1. **对外一律 `long`**：`IAEStack.setStackSize(long)` 是 long 接口。内部 BigInteger 超了就给 `Long.MAX_VALUE`，绝不真把"无限"传出去。
2. **`getAvailableItems` 的 size 也用 clamp**，否则终端会把负/溢出数字画出来。
3. `AEItemStack.fromItemStack(stack)` 对空栈可能返回 null —— 入参一律先挡空栈。
4. P1 不做 NBT 变体归一化（`tag:{}` 与 `tag:null` 是两种 key）—— 这是已知行为，记在验收里当观察项，**别在 P1 偷偷改**。

---

## 8. 验收（P1 的"完成"定义）

### 8.1 必过项（游戏内 `/ae2addoncelltest`，命令自己打断言）

命令对**手里的元件**（没有就临时造一个）跑下面这组断言，逐条打印 `PASS/FAIL` 和实际值：

| # | 操作 | 断言 |
|---|---|---|
| A1 | `injectItems(1000 泥土, SIMULATE)` | 返回 **null**（全收）且 `getStoredItemCount() == 0`（**SIMULATE 不许改状态**） |
| A2 | `injectItems(1000 泥土, MODULATE)` | 返回 **null** 且 `stored == 1000` |
| A3 | `extractItems(400 泥土, SIMULATE)` | 返回 size **400** 且 `stored` 仍 **1000** |
| A4 | `extractItems(400 泥土, MODULATE)` | 返回 size **400** 且 `stored == 600` |
| A5 | `extractItems(10^7 泥土, MODULATE)` | 返回 size **600**（有多少给多少）且 `stored == 0`、`getStoredItemTypes() == 0` |
| A6 | `extractItems(1 泥土, MODULATE)`（空盘） | 返回 **null** ← **语义关键项** |
| A7 | `injectItems(5 石头, MODULATE)` 后 `getAvailableItems()` | 列表里恰好 1 条、size 5、物品是石头 |
| A8 | 同物品**不同 NBT** 各存 1 个 | `getStoredItemTypes() == 2`（身份按 NBT 分开） |

### 8.2 手动项（sensei 在游戏里点一遍）

- B1 启动不崩；日志能看到我们的注册行 + 无 `Duplicate mod`。
- B2 `/give` 拿到元件；放进 ME 驱动器 → 那一格 LED 是**绿/正常**（不是"未识别"）。
- B3 ME 终端存入 64 石头 → 显示 64；取 32 → 剩 32。
- B4 存档退出、重进 → 数量还在。
- B5 **回归**：AE2 自带 1k 元件仍能正常存/取（我们的 handler 没抢它）。

### 8.3 证据要求

- 构建：`BUILD SUCCESSFUL` + `:reobfJar` + jar 的 SHA256。
- 游戏内：`/ae2addoncelltest` 的完整输出（8 条断言逐条结果）截图或复制。

---

## 9. 陷阱清单（写代码时对照）

1. 元件实现 `IStorageCell` → 被 AE2 抢走，白干（§3.2）。
2. 存储类不实现 `ICellInventory` → 驱动器 LED 恒为异常，`getCellInv()` 为 null（§3.3）。
3. SIMULATE 里顺手改了状态 → 终端/合成会"预览即真实扣减"（§3.4）。
4. `extractItems` 没取到返回 0 而不是 null → 上层把它当"取到了 0 个"处理，可能死循环或造物（§3.4）。
5. `getConfigInventory()` 返回 null → `BasicCellInventoryHandler` 构造时 NPE（§5.2）。
6. 用 `ItemStack` 当 map key → 身份错乱（§3.7）。
7. 把 BigInteger 直接转 int/long 不用 clamp → 溢出成负数（§7）。
8. 语言文件写成 `zh_cn.json` → 1.12.2 不认（§3.8）。

---

## 10. 后续（不在 P1 内，先记着）

- P2：三模式/阈值/白名单/臻藏 + `WorldSavedData`；届时 `injectItems` 的 `accepted` 不再恒等于输入量，§5.2 里已经预留了"先算 remainder"的结构。
- P3：Mode2 界面 + 网络包。
- P4：兼容层（按 plan §5 的表）。
- 待验证（不影响 P1）：`AbstractCellInventory.loadCellItem(NBTTagCompound, long)` 的实际覆写姿势 —— 只有将来想继承基类时才需要。
