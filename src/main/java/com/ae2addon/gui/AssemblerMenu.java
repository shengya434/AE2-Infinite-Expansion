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
    /** 当前页窗口容器（45 槽）：服务端广播前从 core 重载，客户端渲染/拖拽走它。
     *  直连 core 的 PatternSlot 在客户端会读 stale 页 + 广播回写错位（2026-09-04）。 */
    private final SimpleContainer pageContainer = new SimpleContainer(AssemblerCoreBE.PAGE_SIZE);

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

        // 样板槽：当前页窗口容器 45 格（vanilla Slot 绑定，客户端由广播维护）
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int i = row * COLS + col;
                addSlot(new Slot(pageContainer, i, SLOT_X0 + col * 18, SLOT_Y0 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return !stack.isEmpty();
                    }

                    @Override
                    public int getMaxStackSize() {
                        return 1;
                    }
                });
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

    /** 客户端请求翻页（delta = ±1，循环）。基于客户端已确认页 clientPage 计算
     *  （客户端 BE 副本的 page 不随服务端更新——2026-09-04 修复：曾用
     *  core.getPage() 导致永远基于 0 计算 → 只能到 1/2/200 页）。 */
    public void changePage(int delta) {
        if (clientSide) {
            int next = Math.floorMod(clientPage + delta, AssemblerCoreBE.PAGES);
            clientPage = next;
            com.ae2addon.AE2Addon.NETWORK.sendToServer(
                    new com.ae2addon.network.AssemblerPagePacket(core.getBlockPos(), next));
        }
    }

    @Override
    public void broadcastChanges() {
        if (!clientSide) {
            // 服务端权威同步：先把玩家可能改动的窗口内容写回 core（落盘），
            // 再从 core 当前页重载窗口（翻页/落盘后内容一致），随后 super 广播。
            int base = core.getPage() * AssemblerCoreBE.PAGE_SIZE;
            boolean dirty = false;
            for (int i = 0; i < AssemblerCoreBE.PAGE_SIZE; i++) {
                if (!ItemStack.matches(core.getSlot(base + i), pageContainer.getItem(i))) {
                    dirty = true;
                    core.setSlot(base + i, pageContainer.getItem(i));
                }
            }
            if (dirty) {
                core.onPatternsChanged();
            }
            for (int i = 0; i < AssemblerCoreBE.PAGE_SIZE; i++) {
                pageContainer.setItem(i, core.getSlot(base + i));
            }
        }
        clientPage = core.getPage();
        super.broadcastChanges();
    }

    @Override
    public void removed(Player player) {
        // 关闭前最后落盘一次（broadcastChanges 可能未覆盖最后改动）
        if (!clientSide) {
            int base = core.getPage() * AssemblerCoreBE.PAGE_SIZE;
            boolean dirty = false;
            for (int i = 0; i < AssemblerCoreBE.PAGE_SIZE; i++) {
                if (!ItemStack.matches(core.getSlot(base + i), pageContainer.getItem(i))) {
                    dirty = true;
                    core.setSlot(base + i, pageContainer.getItem(i));
                }
            }
            if (dirty) {
                core.onPatternsChanged();
            }
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
}
