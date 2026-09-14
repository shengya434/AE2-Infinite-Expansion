package com.ae2addon.gui;

import appeng.blockentity.inventory.AppEngCellInventory;
import com.ae2addon.block.InfiniteDriveBE;
import com.ae2addon.init.ModMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.NotNull;

/**
 * 驱动器（无限级）元件面板 —— **512 格，54 格/页 × 10 页**（2026-09-14 重写）。
 * <p>
 * 大槽位 GUI 的铁律（本项目 3f/3g 经验）：**绝不把 512 个槽全加进菜单**（广播爆炸），
 * 只暴露当前页的 54 格；页码走服务端权威的 {@link ContainerData}（客户端本地猜页会导致
 * 写进旧页 → 吞元件），翻页按钮只发 {@code clickMenuButton} 包。
 */
public class InfiniteDriveMenu extends AbstractContainerMenu {

    private static final int PAGE_SIZE = 54;
    private static final int MAX_PAGE =
            (InfiniteDriveBE.CELL_SLOTS + PAGE_SIZE - 1) / PAGE_SIZE - 1;

    private static final int BTN_PREV = 0;
    private static final int BTN_NEXT = 1;

    private final InfiniteDriveBE be;
    private final PagedCellHandler paged;
    private final ContainerData pageData;

    /** 客户端构造（IForgeMenuType → 只带 pos） */
    public InfiniteDriveMenu(int id, Inventory playerInv, FriendlyByteBuf buf) {
        this(id, playerInv, buf.readBlockPos());
    }

    /** 服务端构造 */
    public InfiniteDriveMenu(int id, Inventory playerInv, BlockPos pos) {
        super(ModMenuTypes.INFINITE_DRIVE.get(), id);

        BlockEntity be = playerInv.player.level().getBlockEntity(pos);
        if (!(be instanceof InfiniteDriveBE drive)) {
            throw new IllegalStateException("Block entity at " + pos + " is not InfiniteDriveBE");
        }
        this.be = drive;
        this.paged = new PagedCellHandler();
        this.pageData = new SimpleContainerData(1);
        addDataSlots(pageData);
        pageData.set(0, 0);

        // ── 当前页元件槽（54 格 = 6×9）──
        for (int i = 0; i < PAGE_SIZE; i++) {
            addSlot(new SlotItemHandler(paged, i, 8 + (i % 9) * 18, 20 + (i / 9) * 18));
        }

        // ── 玩家背包 ──
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 152 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 210));
        }
    }

    // ── 翻页 ──

    @Override
    public boolean clickMenuButton(Player player, int id) {
        int current = pageData.get(0);
        int target = current;
        if (id == BTN_PREV) target = Math.max(0, current - 1);
        if (id == BTN_NEXT) target = Math.min(MAX_PAGE, current + 1);
        if (target == current) return false;

        pageData.set(0, target);
        paged.setPage(target);
        broadcastChanges();
        return true;
    }

    public int getCurrentPage() { return pageData.get(0); }

    public int getMaxPage() { return MAX_PAGE; }

    // ── 转移 ──

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = getSlot(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack inSlot = slot.getItem();
        ItemStack result = inSlot.copy();
        int machineSlots = PAGE_SIZE;
        int totalSlots = machineSlots + 36;

        if (slotIndex < machineSlots) {
            if (!moveItemStackTo(inSlot, machineSlots, totalSlots, false)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(inSlot, 0, PAGE_SIZE, false)) return ItemStack.EMPTY;
        }

        if (inSlot.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return be != null && be.isFormed()
                && player.distanceToSqr(be.getBlockPos().getX() + 0.5,
                        be.getBlockPos().getY() + 0.5,
                        be.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    // ── 分页映射 ──

    /**
     * 把 512 格元件库存的「当前页 54 格」暴露成 IItemHandler。
     * 每次写入都会显式通知 BE（{@code onChangeInventory}）——BE 据此重建该槽的
     * {@code DriveWatcher} 并请求网格重新挂载（否则新插的元件不生效）。
     */
    private class PagedCellHandler extends ItemStackHandler {
        private int page = 0;

        PagedCellHandler() { super(PAGE_SIZE); }

        void setPage(int page) { this.page = page; }

        private AppEngCellInventory real() { return be.getCellInventory(); }

        private int realSlot(int slot) { return page * PAGE_SIZE + slot; }

        private boolean valid(int realSlot) {
            return realSlot >= 0 && realSlot < InfiniteDriveBE.CELL_SLOTS;
        }

        private void touch(int realSlot) {
            be.onChangeInventory(real(), realSlot);
        }

        @Override
        public int getSlots() { return PAGE_SIZE; }

        @Override
        public @NotNull ItemStack getStackInSlot(int slot) {
            int rs = realSlot(slot);
            return valid(rs) ? real().getStackInSlot(rs) : ItemStack.EMPTY;
        }

        @Override
        public void setStackInSlot(int slot, @NotNull ItemStack stack) {
            int rs = realSlot(slot);
            if (!valid(rs)) return;
            real().setItemDirect(rs, stack);
            touch(rs);
        }

        @Override
        public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            int rs = realSlot(slot);
            if (!valid(rs) || !real().isItemValid(rs, stack)) return stack;
            ItemStack remainder = real().insertItem(rs, stack, simulate);
            if (!simulate) touch(rs);
            return remainder;
        }

        @Override
        public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            int rs = realSlot(slot);
            if (!valid(rs)) return ItemStack.EMPTY;
            ItemStack out = real().extractItem(rs, amount, simulate);
            if (!simulate && !out.isEmpty()) touch(rs);
            return out;
        }

        @Override
        public int getSlotLimit(int slot) {
            int rs = realSlot(slot);
            return valid(rs) ? real().getSlotLimit(rs) : 0;
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            int rs = realSlot(slot);
            return valid(rs) && real().isItemValid(rs, stack);
        }

        @Override
        public @NotNull CompoundTag serializeNBT() {
            // 持久化由 BE 自己负责（AEBaseInvBlockEntity 已存元件库存），这里不再重复序列化
            return new CompoundTag();
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) { /* 由 BE 自己持久化 */ }
    }
}
