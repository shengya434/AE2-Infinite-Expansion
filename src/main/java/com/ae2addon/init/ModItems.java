package com.ae2addon.init;

import com.ae2addon.AE2Addon;
import com.ae2addon.item.EternalHeartItem;
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

    // ── 核心基础材料 ──
    public static final RegistryObject<Item> ETERNAL_HEART = ITEMS.register(
            "eternal_heart",
            EternalHeartItem::new
    );

    // ── 方块物品 ──
    public static final RegistryObject<Item> INFINITE_CRAFTING_STORAGE_ITEM = ITEMS.register(
            "infinite_crafting_storage",
            () -> new BlockItem(ModBlocks.INFINITE_CRAFTING_STORAGE.get(), new Item.Properties())
    );
    public static final RegistryObject<Item> INFINITE_CO_PROCESSING_ITEM = ITEMS.register(
            "infinite_co_processing",
            () -> new BlockItem(ModBlocks.INFINITE_CO_PROCESSING.get(), new Item.Properties())
    );

    // ── 万能无限存储元件 ──
    public static final RegistryObject<Item> UNIVERSAL_STORAGE_CELL = ITEMS.register(
            "universal_storage_cell",
            UniversalStorageCell::new
    );

    // ── 创造模式标签页 ──
    public static final RegistryObject<CreativeModeTab> TAB_AE2ADDON = CREATIVE_TABS.register(
            "ae2addon_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ae2addon"))
                    .icon(() -> new ItemStack(ETERNAL_HEART.get()))
                    .displayItems((params, output) -> {
                        // 物品
                        output.accept(ETERNAL_HEART.get());
                        output.accept(UNIVERSAL_STORAGE_CELL.get());
                        // 方块（作为物品）
                        output.accept(ModBlocks.INFINITE_CRAFTING_STORAGE.get());
                        output.accept(ModBlocks.INFINITE_CO_PROCESSING.get());
                    })
                    .build()
    );
}
