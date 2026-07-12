package com.ae2addon.gui;

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

    public ModeSelectMenu(int id, Inventory playerInventory, ItemStack cellStack) {
        super(ModMenuTypes.MODE_SELECT.get(), id);
        this.cellStack = cellStack;
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

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return !cellStack.isEmpty();
    }
}
