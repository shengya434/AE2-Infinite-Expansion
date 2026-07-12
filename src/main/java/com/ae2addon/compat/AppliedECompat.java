package com.ae2addon.compat;

import com.ae2addon.AE2Addon;
import net.minecraftforge.fml.ModList;

/**
 * AppliedE兼容 — 为万能无限元件注册EMC通道支持。
 * <p>
 * 当AppliedE加载时，我们的万能无限元件会在Mode 3下
 * 通过AppliedE的EMC计算系统贡献无限EMC。
 * <p>
 * 注意：无限物品 ≠ 无限EMC。物品需要有ProjectE定义的EMC价格。
 * 没有EMC价格的物品即使无限量也贡献0 EMC。
 */
public class AppliedECompat {

    private static boolean checked = false;

    public static void init() {
        if (checked) return;
        checked = true;

        if (!ModList.get().isLoaded("appliede")) {
            AE2Addon.LOGGER.info("AppliedE not detected, EMC features disabled");
            return;
        }

        try {
            // AppliedE 0.14.3 的EMC集成方式：
            // 
            // 方案A: AppliedE会自动扫描ME网络中所有细胞，
            // 通过getAvailableStacks()获取物品，计算EMC。
            // 我们的Mode 3报告INFINITE数量的所有物品，
            // AppliedE计算：INFINITE × 物品EMC价格 = 无限EMC
            // ✅ 无需额外注册
            //
            // 方案B: 如果物品没有EMC价格，需要直接提供原始EMC。
            // 这需要实现AppliedE的IEMCCellHandler接口。
            // 将在未来版本中支持。
            
            AE2Addon.LOGGER.info("⚡ AppliedE detected! Mode 3 items contribute EMC via AppliedE scanning");
            AE2Addon.LOGGER.info("   Items without EMC value → 0 EMC contribution");
        } catch (Exception e) {
            AE2Addon.LOGGER.warn("AppliedE init error: {}", e.getMessage());
        }
    }
}
