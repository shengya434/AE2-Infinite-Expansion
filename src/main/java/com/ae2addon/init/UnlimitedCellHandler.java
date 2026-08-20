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
 * <p>
 * AE2 15.x 的 ICellHandler 已统一为全通道（物品/流体都由 getAvailableStacks 报告），
 * 只需注册一次。旧版按通道分别注册的做法在新版下是重复注册，已移除。
 */
public class UnlimitedCellHandler implements ICellHandler {

    private static final UnlimitedCellHandler INSTANCE = new UnlimitedCellHandler();

    @Override
    public boolean isCell(ItemStack stack) {
        return stack.getItem() instanceof UniversalStorageCell;
    }

    /**
     * 显式检测：是否为通用无限元件。
     */
    public static boolean isUnlimitedCell(ItemStack stack) {
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
                StorageCells.addCellHandler(INSTANCE);

                // 尝试注册EMC通道（如果装了AppliedE）
                AppliedECompat.init();

                AE2Addon.LOGGER.info("⚡ Unlimited Cell Handler registered (unified item+fluid channel)!");
            });
        }
    }
}
