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
    /**
     * 按钮 id ≥ 此值 → "跳到第 (id - JUMP_BASE) 页"。
     * <p>
     * 2026-09-21 v244：搜索结果在一页之外时，客户端需要**直接跳页**；
     * 复用现有的 {@code clickMenuButton} 通道比新加一个包便宜，也不引入新的权限面
     * （按钮点击本来就要服务端校验）。
     */
    private static final int JUMP_BASE = 100;

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
        // 3 条同步数据：0 = 当前页，1 = 集成型CPU 是否在线，2 = 当前并行上限（0 = 不限/∞）
        // 2026-09-17 sensei 要求千机界面显示这两个状态
        this.pageData = new ContainerData() {
            private int page = 0;
            /** 客户端专用：服务端同步过来的值（**不能**在客户端重算，见 serverSide() 注释） */
            private int syncedOnline = 0;
            private int syncedParallel = 1;

            /**
             * ⚠ 2026-09-17 sensei 实测 bug：「世界里没有集成型CPU 也显示在线」。
             * 原因：客户端也会调 get()，而 {@code isIntegratedCpuOnline()} 在客户端**一律返回 true**
             * （为了不让本地槽位校验误拒）→ 显示就成了恒「在线」。
             * 修法：**服务端实时算**（同步给别人），客户端只读同步值。
             */
            private boolean serverSide() {
                var be = QianJiMenu.this.be;
                return be != null && be.getLevel() != null && !be.getLevel().isClientSide();
            }

            @Override
            public int get(int index) {
                if (index == 0) return page;
                boolean server = serverSide();
                boolean online = server
                        ? QianJiMenu.this.be.isIntegratedCpuOnline()   // 服务端：实时判定
                        : syncedOnline != 0;                           // 客户端：读同步值
                if (index == 1) return online ? 1 : 0;
                if (server) {
                    return online ? 0 : QianJiMenu.this.be.parallelLimit();
                }
                return syncedParallel;
            }

            @Override
            public void set(int index, int value) {
                switch (index) {
                    case 0 -> page = value;
                    case 1 -> syncedOnline = value;
                    default -> syncedParallel = value;
                }
            }

            @Override
            public int getCount() { return 3; }
        };
        addDataSlots(pageData);

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
        // id ≥ JUMP_BASE：直接跳页（GUI 搜索结果的定位），id 0/1 仍是上一页/下一页
        if (id >= JUMP_BASE) {
            return jumpToPage(id - JUMP_BASE);
        }
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

    /**
     * 跳到指定页（自动夹在合法范围内）。翻页的三件套与 {@link #clickMenuButton} 完全一致，
     * 免得两处各写一遍、以后改一处忘另一处。
     *
     * @param page 目标页（0 起）
     * @return 是否真的换了页
     */
    public boolean jumpToPage(int page) {
        int target = Math.max(0, Math.min(MAX_PAGE, page));
        if (target == pageData.get(0)) return false;
        pageData.set(0, target);
        pagedHandler.setPage(target);
        broadcastChanges();
        return true;
    }

    /**
     * GUI 搜索：在**打开这个菜单的那台千机**上搜（服务端执行，扫 1280 槽）。
     * <p>
     * 门槛就挂在"菜单"上——玩家必须先真的打开这台千机，才能通过它搜索；
     * 客户端自己拿不到任何样板数据。搜索逻辑与 {@code /qianji find} 共用同一份实现。
     *
     * @param term  关键词
     * @param limit 条数上限（≤0 = 不限）
     * @return 命中列表（槽位 + 数据）
     */
    public java.util.List<QianJiBE.PatternHit> searchPatterns(String term, int limit) {
        return be == null ? java.util.List.of() : be.searchPatterns(term, limit);
    }

    /**
     * 这台千机所属的网络（服务端）。
     * <p>
     * 2026-09-21：编码时"空白样板可从网络直接取用"要用它（见 {@code QianJiPatternPacket}）。
     * 客户端拿到的 be 不可靠/为 null → 返回 null，调用方自己兜底。
     *
     * @return 网络；没接入或客户端时返回 {@code null}
     */
    public appeng.api.networking.IGrid grid() {
        if (be == null || be.getLevel() == null || be.getLevel().isClientSide()) return null;
        return be.getGrid();
    }

    public int getCurrentPage() {
        return pageData.get(0);
    }

    /** 集成型CPU 是否在线（读同步数据，客户端也能拿到） */
    public boolean isIntegratedCpuOnline() {
        return pageData.get(1) != 0;
    }

    /** 当前并行数的显示文本：0 = 不限（∞） */
    public String parallelText() {
        int limit = pageData.get(2);
        return limit <= 0 ? "§a∞" : "§e" + limit;
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
