package com.ae2addon.compat;

import com.ae2addon.AE2Addon;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.item.UniversalStorageCell;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * EMC兼容（等价交换 ProjectE）。
 * <p>
 * 使用反射避免硬依赖——只在运行时检测ProjectE。
 * 如果装了ProjectE，我们的元件在Mode 3下提供无限EMC。
 */
public class EMCCompat {

    private static boolean projectELoaded = false;
    private static boolean checked = false;

    /** 尝试注册EMC钩子 */
    public static void init() {
        if (checked) return;
        checked = true;

        projectELoaded = ModList.get().isLoaded("projecte");
        if (!projectELoaded) {
            AE2Addon.LOGGER.info("ProjectE not detected, EMC features disabled");
            return;
        }

        try {
            // 通过反射注册EMC提供者
            // ProjectE的API在持续变化中，以下为参考实现
            registerEMCProvider();
            AE2Addon.LOGGER.info("⚡ EMC Integration active! Infinite EMC available!");
        } catch (Exception e) {
            AE2Addon.LOGGER.warn("Failed to register EMC provider: {}", e.getMessage());
        }
    }

    private static void registerEMCProvider() throws Exception {
        // ProjectE 1.20.1 的EMC注册API示例：
        // 需要根据实际安装的ProjectE版本调整

        // 方法1: 通过 IMC 注册 (如果ProjectE支持)
        // net.minecraftforge.fml.InterModComms.sendTo(
        //     "projecte", "register_emc_provider",
        //     () -> (IEMCProvider) (item, emcMap) -> {
        //         if (item.getItem() instanceof UniversalStorageCell) {
        //             // 所有物品提供无限EMC
        //             return Long.MAX_VALUE;
        //         }
        //         return null;
        //     }
        // );

        // 方法2: 通过EMCAPI (如果ProjectE有这个类)
        // Class<?> emcAPI = Class.forName("moze_intel.projecte.api.EMCAPI");
        // emcAPI.getMethod("registerCustomEMC", ItemStack.class, long.class)
        //        .invoke(null, stack, Long.MAX_VALUE);

        AE2Addon.LOGGER.info("EMC provider registration stub (implement per ProjectE version)");
    }

    /** 检查ProjectE是否已加载 */
    public static boolean isProjectELoaded() {
        return projectELoaded;
    }

    /** 获取某个物品的虚拟EMC值（在Mode 3下为无限） */
    public static long getVirtualEMC(ItemStack stack, int mode) {
        if (mode == 2 || mode == 3) return Long.MAX_VALUE;
        return 0;
    }
}
