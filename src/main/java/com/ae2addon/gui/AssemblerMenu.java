package com.ae2addon.gui;

import appeng.api.crafting.IPatternDetails;
import com.ae2addon.block.AssemblerCoreBE;
import com.ae2addon.init.ModMenuTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkHooks;

/**
 * 装配处理器样板槽菜单：每页 9×5 = 45 格，200 页翻页（5×9×200 规格）。
 * <p>
 * 槽直连核心（getSlot/setSlot 按全局下标 page×45+i），翻页由 AssemblerPagePacket
 * 改服务端 page，broadcastChanges 自动把新页槽内容推给客户端。
 */
public class AssemblerMenu extends AbstractContainerMenu {

    private final AssemblerCoreBE core;
    /** 客户端页码显示（broadcastChanges 时同步）。 */
    public int clientPage;
    /** 客户端侧标记（playerInventory 的 level 判断）。 */
    private final boolean clientSide;

    // 槽区几何
    private static final int COLS = 9;
    private static final int ROWS = 5;
    private static final int SLOT_X0 = 8;
    private static final int SLOT_Y0 = 30;
    private static final int PLAYER_Y = 118;

    private AssemblerMenu(int id, Inventory playerInventory, AssemblerCoreBE core) {
        super(ModMenuTypes.ASSEMBLER.get(), id);
        this.core = core;
        this.clientSide = playerInventory.player.level().isClientSide;

        // 样板槽：当前页 45 格（全局下标 = page×45 + i）
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int i = row * COLS + col;
                addSlot(new PatternSlot(core, i, SLOT_X0 + col * 18, SLOT_Y0 + row * 18));
            }
        }
        // 玩家背包 + 快捷栏
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, PLAYER_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, PLAYER_Y + 58));
        }
    }

    public static AssemblerMenu fromNetwork(int id, Inventory playerInventory,
            FriendlyByteBuf buffer) {
        var pos = buffer.readBlockPos();
        if (playerInventory.player.level().getBlockEntity(pos)
                instanceof AssemblerCoreBE core) {
            return new AssemblerMenu(id, playerInventory, core);
        }
        throw new IllegalStateException("无法定位装配处理器核心");
    }

    public static void open(Player player, net.minecraft.core.BlockPos pos) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        var core = (AssemblerCoreBE) player.level().getBlockEntity(pos);
        var provider = new SimpleMenuProvider((containerId, inventory, ignored) ->
                new AssemblerMenu(containerId, inventory, core),
                Component.translatable("gui.ae2addon.assembler.title"));
        NetworkHooks.openScreen(serverPlayer, provider,
                buffer -> buffer.writeBlockPos(pos));
    }

    public AssemblerCoreBE getCore() {
        return core;
    }

    /** 客户端请求翻页（delta = ±1，循环）。 */
    public void changePage(int delta) {
        if (clientSide) {
            int next = Math.floorMod(core.getPage() + delta, AssemblerCoreBE.PAGES);
            com.ae2addon.AE2Addon.NETWORK.sendToServer(
                    new com.ae2addon.network.AssemblerPagePacket(core.getBlockPos(), next));
            // 本地即时反馈（服务端确认后 broadcast 覆盖）
            clientPage = next;
        }
    }

    @Override
    public void broadcastChanges() {
        clientPage = core.getPage();
        super.broadcastChanges();
    }

    @Override
    public void removed(Player player) {
        // 样板槽可能被编辑过：关闭菜单时刷新 CraftingService 的 provider 声明列表
        if (!core.getLevel().isClientSide) {
            core.onPatternsChanged();
        }
        super.removed(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack inSlot = slot.getItem();
            copy = inSlot.copy();
            if (index < 45) {
                // 样板槽 → 背包
                if (!moveItemStackTo(inSlot, 45, 45 + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // 背包 → 样板槽（仅有效样板可放）
                if (!moveItemStackTo(inSlot, 0, 45, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (inSlot.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return core != null && !core.isRemoved()
                && player.level().getBlockEntity(core.getBlockPos()) == core
                && player.distanceToSqr(core.getBlockPos().getX() + 0.5,
                        core.getBlockPos().getY() + 0.5,
                        core.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    /**
     * 直连核心样板槽的 Slot（全局下标随核心当前页动态 = page×45+pageIndex）。
     * Slot 基类需要 Container——传一个哑容器，全部方法覆写走核心。
     */
    private static class PatternSlot extends Slot {
        private static final Container DUMMY = new SimpleContainer(0);
        private final AssemblerCoreBE core;
        private final int pageIndex;

        PatternSlot(AssemblerCoreBE core, int pageIndex, int x, int y) {
            super(DUMMY, 0, x, y);
            this.core = core;
            this.pageIndex = pageIndex;
        }

        private int globalIndex() {
            return core.getPage() * AssemblerCoreBE.PAGE_SIZE + pageIndex;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty();
        }

        @Override
        public ItemStack getItem() {
            return core.getSlot(globalIndex());
        }

        @Override
        public boolean hasItem() {
            return !core.getSlot(globalIndex()).isEmpty();
        }

        @Override
        public void set(ItemStack stack) {
            core.setSlot(globalIndex(), stack);
            setChanged();
        }

        @Override
        public ItemStack remove(int amount) {
            ItemStack current = core.getSlot(globalIndex());
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack taken = current.split(amount);
            core.setSlot(globalIndex(), current);
            setChanged();
            return taken;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
