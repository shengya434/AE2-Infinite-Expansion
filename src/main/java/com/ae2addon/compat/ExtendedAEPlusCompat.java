package com.ae2addon.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * ExtendedAE+ 可选集成（2026-08-28 sensei）：
 * - 频道卡（channel_card）：接口无线连网（无线收发器主端），无需接线
 * - 虚拟合成卡（virtual_crafting_card）：最后一次发配瞬间结束任务
 * <p>
 * compileOnly 依赖（libs/extendedae_plus jar），运行时未装时短路。
 */
public final class ExtendedAEPlusCompat {

    private static boolean checked;
    private static boolean loaded;

    private ExtendedAEPlusCompat() {
    }

    public static boolean isLoaded() {
        if (!checked) {
            checked = true;
            loaded = ModList.get().isLoaded("extendedae_plus");
        }
        return loaded;
    }

    /** 频道卡物品（注册表查询，未装返回 null）。 */
    public static Item channelCard() {
        if (!isLoaded()) {
            return null;
        }
        return ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("extendedae_plus", "channel_card"));
    }

    /** 虚拟合成卡物品（注册表查询，未装返回 null）。 */
    public static Item virtualCraftingCard() {
        if (!isLoaded()) {
            return null;
        }
        return ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("extendedae_plus", "virtual_crafting_card"));
    }
}
