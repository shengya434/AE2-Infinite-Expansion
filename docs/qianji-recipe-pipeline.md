# 千机「归一化配方 + 自有样板模式」设计（2026-09-15 sensei 定稿）

> 目标：**不再反推任意 mod 的配方**。由我们自己的 mod 生成统一的配方模型，
> 通过专用 JEI 页编码成「千机完全理解」的样板，千机按我们的模型精确执行。

## 0. 为什么这么做（背景）

- 现状：千机拿普通 AE2 处理样板当"配方查询钥匙"，再用反射兼容层（GT/Create/Mekanism）
  猜出真实配方 → 猜错/读不到几率时，样板声明的副产就被当成「必出」（实测 15% 副产 5 中 5）
- 根因：**配方的产出语义（尤其概率产出）在各 mod 里五花八门**，靠运行时反射猜不可靠
- 定稿方案：**归一化 + 显式编码**，把"猜"变成"读我们自己的数据"

## 1. 归一化配方层（第一步）

世界加载/首次使用时扫描 `RecipeManager`，把可处理配方转成统一模型：

```java
record QianJiRecipe(
    ResourceLocation id,          // 来源配方 id（AE2 合成树/日志可追溯）
    String machine,               // 来源机器/配方类型（gtceu:assembler / create:crushing / minecraft:smelting …）
    List<Ingredient> inputs,      // 输入（保留 Ingredient 标签语义）
    List<ItemStack> primary,      // 主产物（必出）
    List<Chanced> chanced,        // 概率产出：物品 + 几率(0..1) + 数量
    boolean supported             // 千机是否可执行（不支持的只展示、不可编码）
) {}
```

- 提取策略（优先级从高到低）：
  1. 各 mod 专用提取器（GT：`outputs/inputs` 映射 + `Content.chance`；Create：`getRollableResults` / 序列装配 `resultPool` + `transitionalItem`）
  2. 标准 `Recipe#getResultItem` + `getIngredients`
- 缓存：按 `RecipeManager` 实例缓存（换世界/数据包重载时重建）

## 2. 新 JEI 类别：「千机·可处理配方」

- 每页显示：机器/配方类型、输入（含数量与标签）、主产物、概率产出（百分比）
- 页面上提供 **「编码样板」按钮**（计划中）
- 复用现有 `integration/jei/` 基础设施（已有 `IntegratedCPURecipeCategory`、`FeederGhostHandler` 等）

## 3. 样板格式（**关键决定：副产只进元数据**）

- 载体：**AE2 处理样板**（AE2 合成 CPU 只认自己的样板，不能换成新物品）
- 样板输出栏：**只写主产物** → AE2 永远不会为副产等待（不会出现「缺少 N」）
- 我们自己的 NBT 元数据（写进样板物品）：
  ```
  ae2addon:qianji = {
      recipe: "<来源配方 id>",
      primary: [...],                     // 冗余存一份，便于自校验
      chanced: [ { item: "<id>", count: N, chance: 0.15 }, ... ]
  }
  ```
- 兼容：没有该元数据的旧样板 → 退回现有兼容推断路径（不破坏已有存档）

## 4. 千机执行语义

```
读到元数据 → 精确执行：
   ① 输入校验（按归一化模型的 Ingredient 逐条命中）
   ② 主产物按样板数量给足
   ③ 概率产出按 chance 掷骰（chance 只认配方自带值；催化剂改为**副产物产出数量倍数**：
      基础 50% ×2 / 50% ×1（期望 1.5）· 高级 ×2 · 终极 50% ×4 / 50% ×3（期望 3.5））
   ④ 聊天栏报告：命中的配方 id + 每次概率产出的结果
没有元数据 → 兼容推断（现状：命中配方打分 + 兼容层几率表）
```

## 5. 实施顺序建议

1. 归一化配方层（`recipe/QianJiRecipe*`）+ 千机改为优先查它
2. 样板元数据读写（编码/解析工具类）
3. JEI 类别 + 「编码样板」按钮
4. 旧样板兼容回退收尾（保持现有逻辑不动）

## 6. 待确认/待办

- [x] 副产是否写进样板输出 → **只写主产物**（sensei 2026-09-15 定）
- [ ] 归一化模型要不要把"输入数量"也纳入校验（GT 配方有数量语义）
- [ ] 概率产出的掷骰失败是否在聊天栏每次都提示（可能刷屏 → 考虑限频）
