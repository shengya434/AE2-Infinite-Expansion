# libs/ — 请自行获取的依赖 jar（不入库）

本仓库**不再跟踪** `libs/*.jar`：这些是第三方模组，版权归各自作者，
公开仓库不转发它们的二进制。要构建本项目，请把下列 jar 放进本目录（版本号是当前开发用的那批）。

| 文件 | 用途 |
|---|---|
| `Mekanism-CE-Unofficial-All-10.0.5.021.jar` | Mekanism（社区版）— 化学物通道 |
| `MekanismEnergistics-0.1.5.jar` | Mekanism × AE2 桥 — 化学物存储键 |
| `Thaumcraft-1.12.2-6.1.BETA26.jar` | 神秘时代 — 要素（essentia）通道 |
| `thaumic-energistics-extended-life-1.12.2-2.5.2.jar` | 神秘时代 × AE2 桥 |
| `botania_applie-1.3.0.jar` | 植物魔法 × AE2 桥 — 魔力通道 |
| `flux_applied-1.2.4.jar` | 通量网络 / FE 能量通道 |
| `jei_1.12.2-4.16.5.1030.jar` | JEI — 配方页与拖拽 |
| `!mixinbooter-11.17.jar` | MixinBooter — 1.12.2 的 mixin 前置 |

另外 `gradle/wrapper/gradle-wrapper.jar` 是 Gradle 自带包装器，仍随仓库跟踪。

获取方式：从各模组的官方发布页（CurseForge / Modrinth / GitHub Releases）下载对应版本即可。
缺了某个 jar 时，对应的兼容通道不会编译进来，构建会报「找不到符号」——按上表补齐即可。
