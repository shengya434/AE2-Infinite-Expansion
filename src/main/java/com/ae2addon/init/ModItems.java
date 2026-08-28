package com.ae2addon.init;

import com.ae2addon.AE2Addon;
import com.ae2addon.item.EternalHeartItem;
import com.ae2addon.item.MatterBallItem;
import com.ae2addon.item.UniversalStorageCell;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, AE2Addon.MODID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AE2Addon.MODID);

    // ── 已有物品 ──

    public static final RegistryObject<Item> ETERNAL_HEART = ITEMS.register(
            "eternal_heart",
            EternalHeartItem::new
    );

    // ── 已有方块物品 ──

    public static final RegistryObject<Item> INFINITE_CRAFTING_STORAGE_ITEM = ITEMS.register(
            "infinite_crafting_storage",
            () -> new BlockItem(ModBlocks.INFINITE_CRAFTING_STORAGE.get(), new Item.Properties())
    );
    public static final RegistryObject<Item> INFINITE_CO_PROCESSING_ITEM = ITEMS.register(
            "infinite_co_processing",
            () -> new BlockItem(ModBlocks.INFINITE_CO_PROCESSING.get(), new Item.Properties())
    );

    // ── 已有元件 ──

    public static final RegistryObject<Item> UNIVERSAL_STORAGE_CELL = ITEMS.register(
            "universal_storage_cell",
            UniversalStorageCell::new
    );

    // ── 物质球（取消无限时大量物品临时存放） ──

    public static final RegistryObject<Item> MATTER_BALL = ITEMS.register(
            "matter_ball",
            MatterBallItem::new
    );

    // ── 新增方块物品 ──

    public static final RegistryObject<Item> INTEGRATED_CPU_ITEM = ITEMS.register(
            "integrated_cpu",
            () -> new BlockItem(ModBlocks.INTEGRATED_CPU.get(), new Item.Properties())
    );

    /** ME接口（无限级）方块物品 */
    public static final RegistryObject<Item> INFINITE_INTERFACE_ITEM = ITEMS.register(
            "infinite_interface",
            () -> new BlockItem(ModBlocks.INFINITE_INTERFACE.get(), new Item.Properties())
    );

    // ── 创造模式标签页 ──

    public static final RegistryObject<CreativeModeTab> TAB_AE2ADDON = CREATIVE_TABS.register(
            "ae2addon_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ae2addon"))
                    .icon(() -> new ItemStack(ETERNAL_HEART.get()))
                    .displayItems((params, output) -> {
                        // 防御性填充（2026-08-28 12:30 崩）：任何物品计数异常都钳回 1，
                        // 单个物品失败不影响其余（ForgeHooks 对 count≠1 会硬抛）
                        acceptTabItem(output, ETERNAL_HEART.get());
                        acceptTabItem(output, UNIVERSAL_STORAGE_CELL.get());
                        acceptTabItem(output, ModBlocks.INFINITE_CRAFTING_STORAGE.get());
                        acceptTabItem(output, ModBlocks.INFINITE_CO_PROCESSING.get());
                        acceptTabItem(output, ModBlocks.INTEGRATED_CPU.get());
                        acceptTabItem(output, ModBlocks.INFINITE_INTERFACE.get());
                        acceptTabItem(output, MATTER_BALL.get());
                    })
                    .build()
    );

    /**
     * 创造标签页安全添加：显式构造 ItemStack 并保证 count=1
     * （ForgeHooks 对 count≠1 抛 IllegalArgumentException）。
     * 单物品异常只跳过该物品并记日志，绝不崩游戏。
     */
    private static void acceptTabItem(net.minecraft.world.item.CreativeModeTab.Output output,
            net.minecraft.world.level.ItemLike item) {
        try {
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
            if (stack.getCount() != 1) {
                com.ae2addon.AE2Addon.LOGGER.warn(
                        "[ae2addon] 创造标签页物品 {} count={}（异常，已钳回 1）",
                        item, stack.getCount());
                stack.setCount(1);
            }
            output.accept(stack);
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] 创造标签页添加失败，已跳过: {}", item, e);
        }
    }
}
