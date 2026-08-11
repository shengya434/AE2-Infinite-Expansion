package com.ae2addon.compat;

import com.ae2addon.AE2Addon;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * EMC兼容（等价交换 ProjectE）。
 * <p>
 * 使用反射避免硬依赖——只在运行时检测ProjectE。
 * 如果装了ProjectE，我们的元件在Mode 3下提供无限EMC。
 * 千机的配方校验也用它做价值守恒检查。
 */
public class EMCCompat {

    private static boolean projectELoaded = false;
    private static boolean checked = false;

    private static Object emcProxy = null;
    private static Method getValueMethod = null;
    private static Method hasValueMethod = null;

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
            Class<?> apiClass = Class.forName("moze_intel.projecte.api.ProjectEAPI");
            emcProxy = apiClass.getMethod("getEMCProxy").invoke(null);
            getValueMethod = emcProxy.getClass().getMethod("getValue", ItemStack.class);
            hasValueMethod = emcProxy.getClass().getMethod("hasValue", ItemStack.class);
            AE2Addon.LOGGER.info("⚡ EMC Integration active! (proxy={})", emcProxy.getClass().getSimpleName());
        } catch (Exception e) {
            AE2Addon.LOGGER.warn("Failed to init EMC proxy: {}", e.getMessage());
            emcProxy = null;
        }
    }

    /** 检查ProjectE是否已加载且代理可用 */
    public static boolean isProjectELoaded() {
        return projectELoaded && emcProxy != null;
    }

    /** 获取物品EMC值（无值返回 0） */
    public static long getEmcValue(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return 0;
            Object v = getValueMethod.invoke(emcProxy, stack);
            return v instanceof Long l ? l : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 物品是否有EMC值 */
    public static boolean hasEmcValue(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return false;
            Object v = hasValueMethod.invoke(emcProxy, stack);
            return v instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
        }
    }
}
