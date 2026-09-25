package com.ae2addon.compat.ae2wtlib;

import net.minecraftforge.fml.ModList;

/**
 * AE2WTLib 是否装载（2026-09-21 v273）。
 * <p>
 * ⚠ **这个类里不许出现 AE2WTLib 的任何类型** —— 它是"门"，没装 AE2WTLib 的整合包也会加载它。
 * 真正的 AE2WTLib 代码全放在同包的另外几个类里，只在 {@link #isLoaded()} 为真时才碰。
 */
public final class AE2WTLibCompat {

    public static final String MODID = "ae2wtlib";

    private AE2WTLibCompat() {
    }

    /** AE2WTLib 在不在（通用终端的所有集成都以它为前提） */
    public static boolean isLoaded() {
        try {
            return ModList.get().isLoaded(MODID);
        } catch (Throwable t) {
            return false;
        }
    }
}
