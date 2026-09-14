package com.ae2addon.gui;

import com.ae2addon.block.QianJiBE;
import com.ae2addon.init.ModMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.NotNull;

/**
 * 千机·阿比舒 样板管理界面
 * <p>
 * 使用 PagedPatternHandler 包装真正的 1280 槽处理器，
 * 仅暴露当前页的 54 个槽位给 GUI。
 * 翻页通过 clickMenuButton 实现服务端同步。
 * <p>
 * 槽位布局：
 *   Slot 0-53:  当前页样板（PagedPatternHandler）
 *   Slot 54:     催化剂
 *   Slot 55-90:  玩家背包（36槽）
 */
public class QianJiMenu extends AbstractContainerMenu {

    private static final int PAGE_SIZE = 54;
    private static final int MAX_PAGE = (1280 + PAGE_SIZE - 1) / PAGE_SIZE - 1;

    private static final int BTN_PREV = 0;
    private static final int BTN_NEXT = 1;

    private final QianJiBE be;
    /** 分页包装器：将 1280 槽映射为当前页的 54 槽 */
    private final PagedPatternHandler pagedHandler;
    private final ItemStackHandler catalystHandler;
    private final ContainerData pageData;

    // 客户端构造
    public QianJiMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        this(id, inv, buf.readBlockPos());
    }

    // 服务端构造
    public QianJiMenu(int id, Inventory inv, BlockPos pos) {
        super(ModMenuTypes.QIAN_JI.get(), id);

        BlockEntity be = inv.player.level().getBlockEntity(pos);
        if (!(be instanceof QianJiBE qianjiBE)) {
            throw new IllegalStateException("Block entity at " + pos + " is not QianJiBE");
        }
        this.be = qianjiBE;
        this.pagedHandler = new PagedPatternHandler(qianjiBE.getPatternHandler());
        this.catalystHandler = qianjiBE.getCatalystHandler();
        this.pageData = new SimpleContainerData(1);
        addDataSlots(pageData);
        pageData.set(0, 0); // 首页

        // ── 样板槽（54 个，映射到当前页） ──
        for (int i = 0; i < PAGE_SIZE; i++) {
            int x = 8 + (i % 9) * 18;
            int y = 40 + (i / 9) * 18;
            addSlot(new SlotItemHandler(pagedHandler, i, x, y));
        }

        // ── 催化剂槽 ──
        addSlot(new SlotItemHandler(catalystHandler, 0, 8, 18));

        // ── 玩家背包（3×9） ──
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 140 + r * 18));
            }
        }
        // 快捷栏（1×9）
        for (int c = 0; c < 9; c++) {
            addSlot(new Slot(inv, c, 8 + c * 18, 198));
        }
    }

    /**
     * 按钮点击处理（服务端接收）
     * 由 ServerboundContainerButtonClickPacket 触发
     */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        int curPage = pageData.get(0);
        int newPage = curPage;
        if (id == BTN_PREV) newPage = Math.max(0, curPage - 1);
        if (id == BTN_NEXT) newPage = Math.min(MAX_PAGE, curPage + 1);
        if (newPage != curPage) {
            pageData.set(0, newPage);
            pagedHandler.setPage(newPage);
            broadcastChanges();
            return true;
        }
        return false;
    }

    public int getCurrentPage() {
        return pageData.get(0);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack stack = ItemStack.EMPTY;
        Slot slot = getSlot(slotIndex);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        ItemStack inSlot = slot.getItem();
        stack = inSlot.copy();

        int machineSlots = PAGE_SIZE + 1; // 54 样板 + 1 催化剂
        int totalSlots = machineSlots + 36;

        if (slotIndex < machineSlots) {
            // 机器槽 → 玩家背包
            if (!moveItemStackTo(inSlot, machineSlots, totalSlots, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 优先催化剂（Slot 54），再样板槽
            if (!moveItemStackTo(inSlot, 54, 55, false)) {
                if (!moveItemStackTo(inSlot, 0, PAGE_SIZE, false)) {
                    return ItemStack.EMPTY;
                }
            }
        }

        if (inSlot.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        return stack;
    }

    @Override
    public boolean stillValid(Player player) {
        return be != null && be.isFormed()
                && player.distanceToSqr(be.getBlockPos().getX() + 0.5,
                        be.getBlockPos().getY() + 0.5,
                        be.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    // ════════════════════════════════════════════
    //  分页包装器
    // ════════════════════════════════════════════

    /**
     * 包装 1280 槽的 realHandler，对外暴露 54 槽（当前页）。
     * 所有读写都映射到 realHandler 的真实槽位。
     * 翻页时触发 broadcastChanges 刷新客户端显示。
     */
    private static class PagedPatternHandler extends ItemStackHandler {
        private final ItemStackHandler realHandler;
        private int page = 0;

        PagedPatternHandler(ItemStackHandler real) {
            super(PAGE_SIZE); // 继承 54 槽，但数据不存储在父类
            this.realHandler = real;
        }

        void setPage(int page) {
            this.page = page;
            onContentsChanged(-1); // 触发刷新
        }

        private int realSlot(int virtualSlot) {
            return page * PAGE_SIZE + virtualSlot;
        }

        /** 越界检查：最后一页可能只有不足 54 个真实槽 */
        private boolean validRealSlot(int realSlot) {
            return realSlot >= 0 && realSlot < realHandler.getSlots();
        }

        @Override
        public int getSlots() { return PAGE_SIZE; }

        @Override
        public ItemStack getStackInSlot(int slot) {
            int rs = realSlot(slot);
            if (!validRealSlot(rs)) return ItemStack.EMPTY;
            return realHandler.getStackInSlot(rs);
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            int rs = realSlot(slot);
            if (!validRealSlot(rs)) return;
            if (stack.isEmpty() || isItemValid(slot, stack)) {
                realHandler.setStackInSlot(rs, stack);
            }
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            int rs = realSlot(slot);
            if (!validRealSlot(rs)) return ItemStack.EMPTY;
            return realHandler.extractItem(rs, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            int rs = realSlot(slot);
            if (!validRealSlot(rs)) return 0;
            return realHandler.getSlotLimit(rs);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            int rs = realSlot(slot);
            if (!validRealSlot(rs)) return false;
            return realHandler.isItemValid(rs, stack);
        }

        @Override
        public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            int rs = realSlot(slot);
            if (!validRealSlot(rs)) return stack;
            if (!isItemValid(slot, stack)) return stack;
            return realHandler.insertItem(rs, stack, simulate);
        }

        @Override
        protected void onContentsChanged(int slot) {
            // 转发到被包裹的 handler，触发 setChanged 和缓存失效
            // 但注意不要重复调用 — realHandler.setStackInSlot 已经触发了
        }

        @Override
        public CompoundTag serializeNBT() {
            return realHandler.serializeNBT();
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            realHandler.deserializeNBT(nbt);
        }
    }
}
