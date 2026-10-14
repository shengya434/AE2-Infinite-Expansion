# 规格：通用「禁止存入（拒收）」开关（目标版本 2.0.1 / 任务代号 R1）

## 0. 一句话

给万能无限元件加一个**独立于「锁定数值」**的新维度：**拒收** —— 被拒收的物品**一个都收不进来**
（物品留在来源侧），但**抽取照常**（只出不进）。作用范围 **模式 1 + 2 + 3 通用**。

## 1. 语义对照（**先看懂这张表再动手，写反了就是吞料**）

`insert` 的返回值 = **实际收下的量**。这条已用字节码取证，不是猜的：
- `appeng/api/storage/MEStorage.insert` 的默认实现字节码是 `lconst_0; lreturn`（返回 0 = 什么都没收下）；
- `appeng/me/storage/CompositeStorage.insert` 把子存储的返回值**原样透传**，并在 `ret > 0 && MODULATE` 时
  用它**累加自己的记账**（累加 ⇒ 只能是"收下的量"）。

| 现有行为 | 代码 | 效果 |
|---|---|---|
| **锁定数值**（已实测） | `return amount` | **全收下但不上账**（进黑洞，来源被扣） |
| **拒收**（本次新增） | **`return 0`** | **一个都不收**（物品留在来源） |

- ⚠ **优先级：拒收 > 锁定**。某个 key 同时被锁定又被拒收时，按拒收处理（物品退回来源），
  不许走"收下不上账"。
- ⚠ **只拦进、不拦出**：`extract` / `extractBI` 路径**一个字都不许改**（拒收的东西照样能抽出来）。

## 2. 数据模型（两处同步，照 `qtyLocked` 的三段式抄）

### 2.1 `src/main/java/com/ae2addon/cell/CellDataSavedData.java`

照 `qtyLocked`（现 L128 字段 / L143 `putSet` / L165 `getSet`）的样子加：

```java
public final Set<AEKey> rejectOn  = new HashSet<>();   // 显式拒收
public final Set<AEKey> rejectOff = new HashSet<>();   // 显式放行（默认为拒收时用来豁免个别物品）
public boolean rejectDefault = false;                  // 元件级默认
```
- 序列化短键沿用现有风格：`putSet(tag, "rjo", rejectOn)` / `putSet(tag, "rjf", rejectOff)` /
  `tag.putBoolean("rjd", rejectDefault)`，读端对称。
- **旧存档没有这三个键 → 空集 + false**（不写迁移代码，缺省即兼容）。

### 2.2 `src/main/java/com/ae2addon/cell/UnlimitedCellInventory.java`

- 实例镜像：`private Set<AEKey> rejectOn/rejectOff = new HashSet<>(); private boolean rejectDefault;`
  并在 `load()` / `save()` 里照 `qtyLocked` 那两行（现 L182-183 / L217-218）同步。
- 判定与操作（名字就叫这些，别改名）：

```java
/** 生效判定：显式拒收 > 显式放行 > 元件默认 */
public boolean isRejecting(AEKey k) {
    if (k == null) return false;
    if (rejectOn.contains(k)) return true;
    if (rejectOff.contains(k)) return false;
    return rejectDefault;
}

/** 手势/界面切换：把当前生效值取反，写成显式项（两处同步 + dataDirty + save()） */
public void toggleReject(AEKey k) { ... }

public void setRejectDefault(boolean v) { ... }
public boolean rejectDefault() { return rejectDefault; }
public Set<AEKey> rejectOnKeys() { return rejectOn; }
```

## 3. 插入闸门（4 处 + 1 处物质球特例）

**不加 `mode == 2` 条件** —— 三种模式通用。

1. `public long insert(AEKey what, long amount, Actionable act, IActionSource src)`（现 L348）
   最前面：`if (isRejecting(what)) return 0;`
2. `private long insert(..., int depth)`（现 L357）最前面：`if (isRejecting(what)) return 0;`
3. `private void insertBI(...)`（现 L376）**返回类型 void → boolean**（true = 已受理，false = 未受理）：
   - 顶部：`if (isRejecting(what)) return false;`
   - 物质球分支（现 L386-399）：**先看内层 key** —— `if (isRejecting(innerKey)) return false;`
     （**球不拆、不吞**：整个球按"未受理"退回来源）；递归 `if (!insertBI(innerKey, innerAmount, act, src, depth + 1)) return false;`
     否则 `return true;`
   - 其余**每一个既有分支**都要显式 `return true`（mode 3 收下、mode 2 规则命中、黑名单路径、
     workMode 2/3 路径、以及走到底的普通入库）。**逐分支看完再定，不许一把梭**；
     报告里请把每个分支的行号和给的返回值列出来。
4. `private insert(..., depth)` 的返回改成：`return insertBI(what, BigInteger.valueOf(amount), act, src, depth) ? amount : 0;`
5. **其它 `insertBI` 调用点逐个核对**：凡是忽略返回值的调用点，要么证明"这里的拒收不可能发生"，
   要么改成"未受理就不消费"。报告里逐点列出（文件 + 行号 + 结论）。

## 4. 交互：手势 + 界面（两条都要）

### 4.1 手势

> **⚠ 实际落地口径（2026-10-13 定、10-14 复核）——以这段为准：**
> `Ctrl+右键 = 切拒收` **没有实现**（也未进任何文案）；最终手势是
> **`左键` = 切换 拒↔允**、**`Shift+左键` = 删除该键的显式标记**、
> **`JEI 拖入` = 加入拒收（仅模式选择页）**。
> 配置页现在把 `Ctrl+右键` 直接吞掉（`if (button == 1 && hasControlDown()) return true;`），
> 所以按下去不会有任何动作，也不会误触发「单物品阈值」。
> 原 R6 提案（删掉 Shift 热键）已被同一天的实测决定推翻，见 `md/r6-reject-hotkeys-spec.md` 顶部口径更新。

`client/gui/Mode2ConfigScreen.mouseClicked` 里现有分支：`Ctrl+左键 = 元件内数量`（L1226）、
`Shift+右键 = 切黑名单`（L1230）、`Shift+左键 = 切无限`（L1234）、`右键 = 单物品阈值`（L1238）。
**新增 `button == 1 && hasControlDown()`（Ctrl+右键）= 切换该条目的拒收**。
- 作用对象是**物品条目**；打到"规则条"位置时**忽略**（规则条手势一律不动）。
- 实现走新 packet 类型（见 §5）。

### 4.2 界面
- **条目行状态标记**：条目右侧显示 `拒`（红字）表示该条目生效中；**点击这个标记 = 切换拒收**（与手势同一条路径）。
- **元件级默认**：屏幕顶部（搜索框右侧，或现有按钮区）加 `□ 默认拒收` 勾选框 →
  切换 `rejectDefault`（发 packet）。
- **提示行**：`gui.ae2addon.mode2.hint_bar` 的手势全表补上 `Ctrl+右键=切拒收`。
  （⚠ 2026-10-14 复核：这句**没做**，且按上面 4.1 的口径也不该做；拒收手势写在 `hint_reject` 与列表 tooltip 里。）
- **语言键**（`assets/ae2addon/lang/zh_cn.json` 与 `en_us.json` 各一份）：
  `gui.ae2addon.mode2.reject_mark`（`拒` / `No-In`）、`gui.ae2addon.mode2.reject_default`（`默认拒收` / `Default No-In`）。

## 5. 网络包

`network/Mode2ConfigPacket` 现在用 `type` 区分动作（现 L55 `private final int type`，各 type 有各自的字段）。
- **type 7**：切换"某 key 的拒收"（复用现成的 `CompoundTag keyTag` 字段写法）。
- **type 8**：设置元件默认拒收（带 boolean）。
- 服务端处理端（`handle` / menu 侧）照现有 type 的写法加分支，**必须校验**：发包者确实打开了这个元件的界面
  （照现有 type 的校验方式抄，别放宽）。

## 6. 不许破坏（回归清单）

1. **锁定数值（S5b）语义不变**：`return amount` = 收下不上账（**别顺手改成 0**）。
2. `extract` / `extractBI` **0 改动**。
3. 黑名单、规则无限、`workMode` 1/2/3、物质球解包、归网限流、蓄水池回退路径行为不变。
4. **未设置拒收时逐值不变**：`rejectOn/rejectOff` 空 + `rejectDefault=false` 时，
   所有 insert 的返回值与改动前一致（照 `git diff` 逐个分支核）。
5. reobf：新增/改名的方法不要与 MC 同名同签名；新增公开方法名一律用 mod 独有词
   （`isRejecting` / `toggleReject` / `setRejectDefault` / `rejectDefault` 这些名字没问题）。

## 7. 构建与部署（照技能书 §0）

1. 版本号升到 **2.0.1**（`gradle.properties` / `mods.toml` / `build.gradle` 的 Implementation-Version 一处不落）。
2. WSL 构建：`BUILD SUCCESSFUL` **且日志含 `:reobfJar`**；旧 jar 改名 `.disabled` 保回退；
   实例 `mods` 里活跃 `ae2-addon*.jar` **恰 1 个**。
3. `python tools/check_reobf_consistency.py <jar>` ✅。
4. 构建产物写全路径：`D:\ae2addon-build\build\libs\ae2-addon.jar`。

## 8. 验收（分两段）

**爱丽丝核（离线）**：
- 编译成功 + 含 reobf + 一致性自检 ✅；
- `git diff --stat` 只动 §2/§4/§5 列的文件（+ 2 个 lang 文件 + 版本号）；
- §3.5 的 `insertBI` 调用点逐点核对表；
- §3.3 的"每个分支返回什么"逐行表；
- `grep` 证明 `extract` 路径 0 改动。

**sensei 实测清单（我出，你跑）**：
1. 给物品 X 设拒收 → 塞 X（终端 shift 点击 / 漏斗 / 接口任选）→ **X 留在来源**、元件里 X 数量不变；
2. 从元件抽 X → 正常能抽；
3. 关掉拒收 → 恢复能存；
4. 开「默认拒收」→ 没设过的物品全被拒；单独放行的物品仍能存；
5. 同一 key 同时锁定 + 拒收 → **拒收胜**（物品留在来源，不进黑洞）；
6. 物质球里装被拒收的物品 → **球不被吞**（留在来源）；
7. 抽/存其它未涉及物品、模式 1/2/3 各自的基本功能 → 无异常；
8. 旧存档（没有新键）读入正常、界面不炸。
