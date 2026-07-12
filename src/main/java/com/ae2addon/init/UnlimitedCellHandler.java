package com.ae2addon.init;

import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.ae2addon.AE2Addon;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.compat.AppliedECompat;
import com.ae2addon.item.UniversalStorageCell;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 万能无限存储的细胞处理器。
 * 注册三次：物品、流体、EMC通道各自独立。
 */
public class UnlimitedCellHandler implements ICellHandler {

    private static final UnlimitedCellHandler INSTANCE = new UnlimitedCellHandler();
    private static final UnlimitedCellHandler INSTANCE_FLUID = new UnlimitedCellHandler();

    @Override
    public boolean isCell(ItemStack stack) {
        return stack.getItem() instanceof UniversalStorageCell;
    }

    @Override
    public @Nullable StorageCell getCellInventory(ItemStack stack, ISaveProvider saveProvider) {
        if (!isCell(stack)) return null;
        return new UnlimitedCellInventory(stack, saveProvider);
    }

    @Mod.EventBusSubscriber(modid = AE2Addon.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class Registration {
        @SubscribeEvent
        public static void onCommonSetup(FMLCommonSetupEvent event) {
            event.enqueueWork(() -> {
                StorageCells.addCellHandler(INSTANCE);       // 物品通道
                StorageCells.addCellHandler(INSTANCE_FLUID); // 流体通道

                // 尝试注册EMC通道（如果装了AppliedE）
                AppliedECompat.init();

                AE2Addon.LOGGER.info("⚡ Unlimited Cell Handler registered (items + fluids + EMC)!");
            });
        }
    }
}
