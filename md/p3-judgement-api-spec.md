# P3 判定层接口规格（1.12.2）—— 给第二轮 codex 的调用契约 + 我自己的实施清单

**状态**：2026-10-02 立。前置：`p2-modes-and-persistence-spec.md`（已验收）、`p3-gui-network-handoff.md`。
**为什么有这份**：P3 第一轮 codex 把界面骨架做出来了（编译过、回归门 28/0 绿），但它只接通了 24 个包类型里的 12 个，
缺口里有一半原因是**判定层还没有那些接口**。sensei 拍板「做全」⇒ 判定层这部分由爱丽丝补，
补完按本文 §5 的对照表交给 codex 接界面。

---

## 0. 一句话

**1.20 的界面/网络层一共调判定层 35 个方法**（我用正则从 `gui/`+`network/` 里抠出来的全量），
其中 **21 个已有**、**14 个要补**。补的这 14 个 + 持久化 + 自检断言就是本轮我的活。

---

## 1. 已有接口（codex 可以直接调，别自己绕）

| 用途 | 方法 |
|---|---|
| 模式 | `getMode()` / `setMode(int)` / `getWorkMode()` / `setWorkMode(int)` |
| 阈值 | `getThreshold()` / `setThreshold(long)` / `thresholdFor(IAEItemStack)` / `setItemThreshold(IAEItemStack,long)` |
| 白名单 | `addWhitelist(IAEItemStack)` / `removeWhitelist(IAEItemStack)` / `isWhitelisted(IAEItemStack)` |
| 无限集 | `isInfinite(IAEItemStack)` / `cancelInfinite(IAEItemStack)` |
| 拒收 | `isRejecting` / `toggleReject` / `clearReject` / `toggleRejectDefault` / `setRejectDefault` / `rejectDefault` |
| 存量 | `storedAmount(IAEItemStack)`（s1+s2+ca 合计，BigInteger） / `getStoredItemCount()`（clamp 后） |
| 其他 | `describeSettings()` / `getUuid()` / `rawData()`（**仅供自检**，界面不许摸） |

---

## 2. 要补的接口（逐个给签名 + 语义 + 1.20 出处）

### 2.1 ★ 规则：tag 规则在 1.12.2 上换成 **OreDictionary**

**为什么**：1.20 的 tag 规则用的是**物品 tag**（`TagKey.create(Registries.ITEM, …)` + `builtInRegistryHolder().is(…)`，
出处 L1043-1053）。**1.12.2 没有物品 tag**（tag 是 1.13 才进原版的）。
1.12.2 生态里"一类物品"的等价物是 **`OreDictionary`**（`ingotIron` / `logWood` / `plankWood` …）。

**决定**：tag 规则在 1.12.2 上**实现为 OreDictionary 规则**，接口名与语义照搬，只把"匹配"换成 OreDictionary：
```java
boolean addOreRule(String oreName);      // 例 "ingotIron"，对应 1.20 的 addTagRule
boolean removeOreRule(String oreName);
Set<String> getOreRules();               // 对应 getTags()
boolean addModRule(String mod);          // 对应 addModRule，语义不变
boolean removeModRule(String mod);
Set<String> getModRules();               // 对应 getMods()
int  getRuleMode(String ruleKey);        // 1=Instant(立即) 2=Touch(触碰)；缺省 1
void setRuleMode(String ruleKey, int mode);   // 只接受 1/2，越界按 1；规则不存在则不动作
void setRuleAllInstant(boolean instant);      // 对应 setRuleInstant
boolean isRuleInstant();
boolean ruleAllowsInfinite(IAEItemStack key); // 对应 L1056
boolean isInfiniteByRule(IAEItemStack key);   // 对应 L1110（含 mode==2 判断）
```
- `ruleKey` 字符串格式与 1.20 一致：**`"ore:<name>"`** / **`"mod:<namespace>"`**（1.20 是 `tag:`/`mod:`；我们换前缀但保持结构）。
- **匹配顺序与语义（照抄 1.20 L1056-1074）**：
  1. 命名空间命中 `mods` → 若该规则 mode==1（立即）→ **true**；否则记 `matched=true`；
  2. 逐个 ore 规则：命中 → 若 mode==1 → true；否则 `matched=true`；
  3. 最后 `return matched && touched.contains(key)`（触碰模式必须"存过"）。
- **带 NBT 的变体不参与规则**（照抄 1.20 L1028 的修复）：`hasTag()`（1.12.2 用 `key.getDefinition().hasTagCompound()`）为真 → **false**。
  理由（1.20 的实测教训）：否则带 NBT 物品会被"收下不记账"的无限路径吞掉，原物品消失。
- **`touched`（触碰集合）**：只有**真正存入过**的 key 才进（1.20 L429 `ruleTouched.add(what)`），要持久化。
- **`ruleActive`（照抄 L1077-1080）**：`blacklist.contains || qtyLocked.contains || s2.containsKey` → **false**；否则 `ruleAllowsInfinite`。
  ⇒ 黑名单、数量锁定、还有"当前在有限存储里"的条目，都不算规则无限。

### 2.2 黑名单
```java
boolean toggleBlacklist(IAEItemStack key);   // 返回切换后是否在黑名单
boolean isBlacklisted(IAEItemStack key);
Set<IAEItemStack> getBlacklist();            // 返回副本
```
- 语义（照抄 L1088-1103 + L444/L465）：黑名单**只阻止"升级为无限"**（wm2 的存入即无限、wm1 的阈值升级都拦），
  **不阻止存入**（物品照常进 s2）；已在 `ul` 的条目保持原样；加入黑名单时从 `touched` 里移除。

### 2.3 数量锁定 / 设置
```java
void setStoredQuantity(IAEItemStack key, BigInteger q, boolean lock);  // 照抄 L744-756
boolean isQuantityLocked(IAEItemStack key);
Set<IAEItemStack> quantityLockedKeys();       // 返回副本
```
- `setStoredQuantity`：先把该 key 从 `ul`/`wl`/`ca` 移除 → 写入 `s2`（q<=0 则删键）→ 按 `lock` 增删 `qtyLocked`。
- 锁定后在 insert/extract 的行为（已在 P2 实现，出处 L366/L375/L392/L498-500）：
  **insert 收下但不上账**；**extract 最多给出 `s2` 里记的量**。

### 2.4 面板枚举（含零数量条目）
```java
public static class PanelItem {
    public final IAEItemStack key;     // size=1 的规范键
    public final BigInteger amount;    // 真实数量（BigInteger，不许 clamp）
    public final boolean isInfinite;
    public final long bytes;           // 无限条目用 INFINITE_BYTES，否则 0
    public final long itemThreshold;   // 0 = 用全局
    public final boolean quantityLocked;
}
List<PanelItem> getPanelItems();       // 照抄 L1114-1154（mode != 2 返回空表）
```
- 顺序：wm3 → 先 `wl` 再 `ul`（跳过已在 wl 的）再 `s2`；wm1/2 → `wl` → `ul` → `s2`（跳过已在 wl/ul 的）。
- **零数量条目**：`itemThr` 里配了阈值但数量为 0 的 key **也要出现**（`amount = ZERO`，`isInfinite=false`），
  否则界面上"清掉数量后想再编辑阈值"就没入口了（1.20 L821 的注释就是为这个）。

### 2.5 切换无限 / 承诺额度
```java
boolean togglePanelInfinite(IAEItemStack key);   // 照抄 L1156-1177：返回切换后是否无限
BigInteger getCommitedAmount(IAEItemStack key);  // 照抄 L1185：取不到回落 thresholdFor(key)
boolean hasCommitedAmount(IAEItemStack key);
```
- `togglePanelInfinite`：锁定条目直接 false 不动；已在 `ul|wl` → 三者都移除、返回 false；
  否则把 `s2` 的量搬进 `ca`、`s2` 删键、加入 `ul`、返回 true。

### 2.6 一键格式化
```java
void wipeAllData();    // 照抄 L243-259
```
清 `s1 s2 ca wl ul itemThr oreRules modRules ruleModes touched blacklist qtyLocked rejectOn rejectOff`；
`rejectDefault=false`、`ruleInstant=true`、`workMode=1`、`thr=65536`；**`m3`（mode3 数据）不清**；写回物品 NBT。

### 2.7 给网络层用的集合访问器（一律返回**副本**，防外部改内部状态）
```java
Set<IAEItemStack> getWl();  Set<IAEItemStack> getUl();
Set<IAEItemStack> getRejectOnKeys();  Set<IAEItemStack> getRejectOffKeys();
Map<IAEItemStack, Long> itemThresholds();
Map<IAEItemStack, BigInteger> getS2();
```

---

## 3. 持久化（`CellStorageData` 新增 NBT 键）

| 键 | 内容 | 编码 |
|---|---|---|
| `or` | ore 规则名 | 字符串列表 |
| `md` | mod 规则名 | 字符串列表 |
| `rm` | 规则模式 | `{name: mode}` 复合（键 = 规则字符串） |
| `rt` | 触碰集合 | key 列表（同 `w` 的写法） |
| `bl` | 黑名单 | key 列表 |
| `ql` | 数量锁定 | key 列表 |
| `ri` | ruleInstant | boolean |

⚠ 读档时**单个条目坏了只跳过该条**（与既有实现一致），不要整盘放弃。

---

## 4. 自检新增 **D 组**（放进 `/ae2addoncelltest`，与 A/B/C 并列）

| # | 做什么 | 断言 |
|---|---|---|
| D1 | mode2 + mod 规则（用 `minecraft`）Instant | 存一个 minecraft 物品 → 立刻无限（取 10^7 返回 10^7）；`isInfiniteByRule` 为真 |
| D2 | 同一规则改 Touch 后，**新**物品 | 没存过 → 不无限；存 1 个后 → 无限 |
| D3 | 带 NBT 的物品在规则下 | **不进**无限路径，数量落在 s2（身份与 NBT 都在） |
| D4 | 黑名单 | 把已无限的 key 拉黑 → wm2 下不再"存入即无限"（新存的数量落 s2）；`isBlacklisted` 真 |
| D5 | 数量锁定：`setStoredQuantity(key, 500, true)` | insert 100 后 `storedAmount` 仍 500；extract 600 只拿到 500；`isQuantityLocked` 真 |
| D6 | `togglePanelInfinite` | s2=300 的条目 → 切换后 `ca`=300、`ul` 命中、s2 清空；再切换 → 回到 s2 且 ca 清 |
| D7 | `getPanelItems` | 含零数量但配了单物品阈值的条目；无限条目 `amount` 为 `INFINITE` 且 `isInfinite` 真 |
| D8 | `wipeAllData` | 清完：s1/s2/ca/wl/ul/规则/黑名单/锁定/拒收全空，`thr` 回 65536、`wm` 回 1、`m3` 保留 |
| D9 | 持久化 | D1~D8 的状态 `persist()` 后重载 → 规则/黑名单/锁定/触碰集合逐项一致 |
| D10 | 越界 | `setRuleMode` 传 3 → 按 1；对不存在的规则 `setRuleMode` → 不动作；`getCommitedAmount` 对没承诺的 key → 回落该物品阈值 |

**D 组与 A/B/C 一起构成 P3 第二轮的验收门**：`/ae2addoncelltest` 必须 **PASS（28 + 10 = 38）/ FAIL 0**。

---

## 5. ★ 给 codex 的对照表（1.20 调用 → 1.12.2 本层方法）

| 1.20 里调的名字 | 1.12.2 调这个 |
|---|---|
| `getMode/setMode` | 同名 ✓ |
| `getWorkMode/setWorkMode` | 同名 ✓（注：1.20 里工作模式走 `Mode2ConfigPacket` type 6） |
| `getThreshold/setThreshold` | 同名 ✓ |
| `setItemThreshold` / `itemThresholds` | `setItemThreshold` ✓ / `itemThresholds()` ✓ |
| `addWl/removeWl/getWl` | `addWhitelist/removeWhitelist/getWl` |
| `getUl` | `getUl` |
| `toggleReject/clearReject/rejectDefault/setRejectDefault` | 同名 ✓ |
| `rejectOnKeys/rejectOffKeys` | `getRejectOnKeys/getRejectOffKeys` |
| `addTagRule/removeTagRule/getTags` | **`addOreRule/removeOreRule/getOreRules`**（1.12.2 用 OreDictionary，见 §2.1） |
| `addModRule/removeModRule/getMods` | `addModRule/removeModRule/getModRules` |
| `getRuleMode/setRuleMode/setRuleInstant` | 同名 ✓（另加 `isRuleInstant`） |
| `toggleBlacklist/getBlacklist` | 同名 ✓ |
| `setStoredQuantity/isQuantityLocked/quantityLockedKeys` | 同名 ✓ |
| `getPanelItems` | 同名 ✓（`PanelItem` 见 §2.4） |
| `togglePanelInfinite` | 同名 ✓ |
| `getCommitedAmount/hasCommitedAmount` | 同名 ✓ |
| `wipeAllData` | 同名 ✓ |

**规则字符串格式**：`"ore:<name>"` / `"mod:<namespace>"`（1.20 是 `"tag:…"`，我们换前缀）。
