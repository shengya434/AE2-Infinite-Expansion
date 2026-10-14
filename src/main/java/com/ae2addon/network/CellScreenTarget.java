package com.ae2addon.network;

import com.ae2addon.AE2Addon;
import com.ae2addon.block.InfiniteDriveBE;
import com.ae2addon.gui.InfiniteDriveMenu;
import com.ae2addon.gui.Mode2ConfigMenu;
import com.ae2addon.gui.ModeSelectMenu;
import com.ae2addon.item.UniversalStorageCell;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Resolves the cell currently bound to a navigation menu. */
public final class CellScreenTarget {
    public record CellTarget(ItemStack stack, InfiniteDriveBE drive, int driveSlot, String source) {}

    private CellScreenTarget() {}

    public static @Nullable CellTarget resolveCellTarget(ServerPlayer player) {
        if (player.containerMenu instanceof ModeSelectMenu menu) {
            if (!menu.stillValid(player) || !(menu.getCellStack().getItem() instanceof UniversalStorageCell)) return missing(player);
            return new CellTarget(menu.getCellStack(), menu.getDrive(), menu.getDriveSlot(), "menu");
        }
        if (player.containerMenu instanceof Mode2ConfigMenu menu) {
            if (!menu.stillValid(player) || !(menu.getCellStack().getItem() instanceof UniversalStorageCell)) return missing(player);
            return new CellTarget(menu.getCellStack(), menu.getDrive(), menu.getDriveSlot(), "menu");
        }
        if (player.containerMenu instanceof InfiniteDriveMenu menu && menu.isEditorMode()) {
            ItemStack stack = menu.getSelectedCell();
            int slot = menu.findCellSlot(stack);
            if (menu.stillValid(player) && slot >= 0 && stack.getItem() instanceof UniversalStorageCell)
                return new CellTarget(stack, menu.getDrive(), slot, "drive");
            return missing(player);
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.getItem() instanceof UniversalStorageCell)
            return new CellTarget(stack, null, -1, "hand");
        stack = player.getOffhandItem();
        if (stack.getItem() instanceof UniversalStorageCell)
            return new CellTarget(stack, null, -1, "offhand");
        return missing(player);
    }

    private static @Nullable CellTarget missing(ServerPlayer player) {
        AE2Addon.LOGGER.warn("[cellscreen] target missing menu={}", player.containerMenu.getClass().getSimpleName());
        player.sendSystemMessage(Component.translatable("gui.ae2addon.cell.target_missing"));
        return null;
    }
}
