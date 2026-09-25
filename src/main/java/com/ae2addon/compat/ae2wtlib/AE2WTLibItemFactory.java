package com.ae2addon.compat.ae2wtlib;

import net.minecraft.world.item.Item;

/**
 * 无线终端的"实例化工厂"（2026-09-21 v273）。
 * <p>
 * 单独放一个类的原因：它**引用** {@link QianJiWUTItem}（那个类实现了 AE2WTLib 的接口）。
 * 只要没人调用 {@link #create()}，这个类就不会被加载 → 没装 AE2WTLib 也不会缺类崩。
 * 调用点必须写成 `if (AE2WTLibCompat.isLoaded()) AE2WTLibItemFactory.create();`。
 */
public final class AE2WTLibItemFactory {

    private AE2WTLibItemFactory() {
    }

    public static Item create() {
        return new QianJiWUTItem();
    }
}
