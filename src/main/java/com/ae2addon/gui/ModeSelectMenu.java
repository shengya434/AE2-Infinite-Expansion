package com.ae2addon.gui;

import com.ae2addon.block.InfiniteDriveBE;
import com.ae2addon.init.ModMenuTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 模式选择界面的容器。
 * 不需要实际槽位，只是一个交互界面。
 */
public class ModeSelectMenu extends AbstractContainerMenu {

    private final ItemStack cellStack;
    private final InfiniteDriveBE drive;
    private final int driveSlot;

    public ModeSelectMenu(int id, Inventory playerInventory, ItemStack cellStack) {
        this(id, playerInventory, cellStack, null, -1);
    }

    public ModeSelectMenu(int id, Inventory playerInventory, ItemStack cellStack,
                          InfiniteDriveBE drive, int driveSlot) {
        super(ModMenuTypes.MODE_SELECT.get(), id);
        this.cellStack = cellStack;
        this.drive = drive;
        this.driveSlot = driveSlot;
    }

    /**
     * 从网络数据包创建（服务端→客户端同步）
     */
    public static ModeSelectMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        ItemStack stack = buf.readItem();
        return new ModeSelectMenu(id, inv, stack);
    }

    /**
     * 服务端同步时写入网络数据包
     */
    @Override
    public void sendAllDataToRemote() {
        super.sendAllDataToRemote();
    }

    public ItemStack getCellStack() {
        return cellStack;
    }

    public boolean isCellPresent(Player player) {
        return drive == null || (driveSlot >= 0 && drive.isFormed()
                && player.level().hasChunkAt(drive.getBlockPos())
                && player.level().getBlockEntity(drive.getBlockPos()) == drive
                && drive.getInternalInventory().getStackInSlot(driveSlot) == cellStack);
    }

    public void markCellChanged() {
        if (drive != null) drive.setChanged();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (drive != null && !player.level().isClientSide && isCellPresent(player)) {
            drive.onChangeInventory(drive.getInternalInventory(), driveSlot);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return !cellStack.isEmpty() && isCellPresent(player);
    }
}
