# AE2 Addon — Infinite Expansion · Minecraft 1.12.2

本分支是 **1.12.2（AE2-UEL）** 的移植线，与 `main` / `wip/qianji-drive`（Minecraft 1.20.1）**代码不共享历史**，
两条线各自独立演进。1.12.2 侧目前包含：万能无限存储元件（三种模式）、阈值/白名单/黑名单与拒收规则、
世界存档持久化、中文拼音搜索、GUI 与网络包（24 种包类型）。

## 环境

| 项 | 版本 |
|---|---|
| Minecraft | 1.12.2 |
| Forge | 14.23.5.2859 |
| AE2 | AE2-UEL 0.56.x（开发基线 0.56.7；0.56.8 的 fork 上也验证过兼容） |
| 前置 | **MixinBooter**（必需） |

## 构建

1. 按 [`libs/README.md`](libs/README.md) 把需要的依赖 jar 放进 `libs/`
   —— 本仓库**不转发**第三方模组的二进制（Mekanism / Thaumcraft / JEI / Botania 等版权归各自作者）
2. `gradlew.bat build`（Windows）或 `./gradlew build`
3. 产物：`build/libs/ae2-addon-1122-<版本>-1.12.2.jar`

## 许可

本项目以 **GNU LGPL-3.0** 发布，全文见 [`LICENSE`](LICENSE)。

> 说明：1.12.2 的 `mcmod.info` 规格里**没有** `license` 字段（`ModMetadata` 不含该字段），
> 所以许可只以本文件与 `LICENSE` 声明，不在 `mcmod.info` 里写无效键。

第三方依赖（Mekanism、Thaumcraft、JEI、Botania、Flux Networks、MixinBooter 等）的版权与许可归各自作者所有，
使用/分发它们请遵守各自的许可条款。
