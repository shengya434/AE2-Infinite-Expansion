# 千机五模组兼容实施记录（2026-10-11）

此文件记录本轮实现过程中的实测。静态 jar 数据与游戏内 `/qianji scan` 分开标记；未运行的验机命令不会写成通过。

## industrialforegoing

- 已接入 6 类的物品/流体输入、产出与激光钻非消耗透镜；`fluid_extractor` 覆盖了标准 API 错报的输入物品产出。
- jar 静态样本：`stonework_generate` 有 6 条，其中 `cobblestone` 的 `waterNeed/lavaNeed=1000`、两者 `Consume=0`；`netherrack` 为水 250/250、岩浆 400/200。激光矿 JSON 的 `output` 是单条 item/tag，`catalyst` 是透镜。
- 中间构建：`BUILD SUCCESSFUL`，reobf `exit=0`，SHA256 `6ACFB581CECBD03AA82BAB961D6E250888DBA57AF39F98E678E4C810B886AA56`。随后修正了 stonework 非消耗槽识别及 extractor 的 100% 损耗边界，最终构建待全部模组完成后重跑。
- `/qianji scan industrialforegoing`：尚未运行；预期报告路径 `logs/ae2addon-qianji-scan-industrialforegoing.txt`，实际计数待验机。
- TODO：crusher/laser_drill_ore 的多候选产出规则以及 `defaultRecipe` / `modIdCondition` 的机器侧影响未证实；当前多候选只选首个代表物品并写一次警告。

## integrateddynamics

- 已接入 squeezer、mechanical_squeezer、drying_basin、mechanical_drying_basin；两种 facade 和六种 `crafting_special_*` 按设计排除。squeezer 的每条概率输出保留自身数量和概率。
- 接入后的完整构建：`BUILD SUCCESSFUL`，reobf `exit=0`，SHA256 `C327619376D9EE44F5576FAF17AEB510022473797E56B61EE5DE49418A8C4E59`。
- `/qianji scan integrateddynamics`：尚未运行；预期报告路径 `logs/ae2addon-qianji-scan-integrateddynamics.txt`，实际计数待验机。

## pneumaticcraft

- 已接入八类机器配方；`FluidIngredient` 优先按流体读取。动态爆炸/冷却产物读为数量 1 的概率输出；五种参数或动态配方类型按设计排除。
- 接入后的完整构建：`BUILD SUCCESSFUL`，reobf `exit=0`，SHA256 `4F3B5A4BEED7F0BA5B4C2E396DA35FF592E7237B53A6CA8D91506EF32D6D3C59`。
- `/qianji scan pneumaticcraft`：尚未运行；预期报告路径 `logs/ae2addon-qianji-scan-pneumaticcraft.txt`，实际计数待验机。
- 注意：现有千机对纯概率产物执行整数配平并生成主产物；此规则会作用于爆炸/冷却配方，需在游戏扫描中检查最终样板。

## ae2cs

- 已接入聚合器、粉碎机、蚀刻机三类；输入通过 `SizedIngredient.ingredient()/count()` 读取，空成分不占槽。两个无静态输入产出的自定义配方按设计排除。
- 静态样本：聚合器配方的输入有 16/32/32 个数值，兼容层按三个独立槽保留；游戏内 `/qianji probe` 尚待核对。
- 接入后的完整构建与 BigReactors 同批：`BUILD SUCCESSFUL`，reobf `exit=0`，SHA256 `540F91101ABCA66F0A4F7FAC0D6C65CC26B871545E369EBEFF29930092F997FB`。
- `/qianji scan ae2cs`：尚未运行；预期报告路径 `logs/ae2addon-qianji-scan-ae2cs.txt`，实际计数待验机。

## bigreactors

- 已接入三种流化机配方和再处理器；`getMatchingElements()` 的物品/流体堆数量直接进入槽位；产出从 `getResult().getResult()` 读取。正反顺序配方用归一化输入和产出键在候选列表及 ME 索引里去重。
- 静态样本：`fluidizersolid` 的流体产出覆盖 500～18000 mB；游戏内 `/qianji probe` 尚待核对。
- 接入后的完整构建：`BUILD SUCCESSFUL`，reobf `exit=0`，SHA256 `540F91101ABCA66F0A4F7FAC0D6C65CC26B871545E369EBEFF29930092F997FB`。
- `/qianji scan bigreactors`：尚未运行；预期报告路径 `logs/ae2addon-qianji-scan-bigreactors.txt`，实际计数与去重显示待验机。
