package com.ae2addon.gui;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.menu.locator.MenuLocators;
import com.ae2addon.AE2Addon;
import com.ae2addon.block.InfiniteInterfaceBE;
import com.ae2addon.init.ModMenuTypes;
import com.ae2addon.network.FeederStatusPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * ME 接口（无限级）配置界面：3×3 样板槽 + 蓄水池状态。
 * <p>
 * 样板槽只接受已编码样板（isEncodedPattern）；
 * 放样板块 → 声明可处理配方（CPU 推过来）+ 样板输入自动补货。
 */
public class InfiniteInterfaceMenu extends AbstractContainerMenu {

    private final InfiniteInterfaceBE feeder;

    /** 打开菜单的玩家（服务端广播用；AbstractContainerMenu 无 getPlayer()）。 */
    private final Player opener;

    /** 客户端：蓄水池状态行（FeederStatusPacket 同步）。 */
    public volatile List<String> statusLines = List.of();

    /** 服务端：上次发送的状态指纹（变化检测，防高频刷包）。 */
    private String lastStatusKey = "";

    public InfiniteInterfaceMenu(int id, Inventory playerInventory, InfiniteInterfaceBE feeder) {
        super(ModMenuTypes.INFINITE_INTERFACE.get(), id);
        this.feeder = feeder;
        this.opener = playerInventory.player;

        // 分页布局（2026-08-28）：样板/标记槽固定 3×3 每页，容量卡加页；
        // 当前页 = 客户端视图状态（currentPage），槽位按页显隐
        final int gridY = 83;
        // 样板槽（左 3×3×页；每页9格）
        var patternInv = feeder.getPatternInventory();
        for (int i = 0; i < 27; i++) {
            int page = i / 9;
            int col = (i % 9) % 3;
            int row = (i % 9) / 3;
            addSlot(new PageSlot(patternInv, i, 26 + col * 18, gridY + row * 18, page) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return PatternDetailsHelper.isEncodedPattern(stack);
                }
            });
        }
        // 标记槽（右 3×3×页；任意物品 = 自动补货清单）
        var markerInv = feeder.getMarkerInventory();
        for (int i = 0; i < 27; i++) {
            int page = i / 9;
            int col = (i % 9) % 3;
            int row = (i % 9) / 3;
            addSlot(new PageSlot(markerInv, i, 96 + col * 18, gridY + row * 18, page));
        }
        // 升级槽（5个，参数区下方；只收 AE2 升级卡）
        var upgradeInv = feeder.getUpgrades().toContainer();
        for (int i = 0; i < 5; i++) {
            addSlot(new Slot(upgradeInv, i, 44 + i * 18, 59));
        }
        // 玩家背包 9×3 + 快捷栏（固定位置）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 161 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 215));
        }
    }

    /** 分页槽：仅当前页可见可点（isActive 客户端渲染用；服务端点击按位置处理）。 */
    private class PageSlot extends Slot {
        private final int page;

        PageSlot(net.minecraft.world.Container inv, int index, int x, int y, int page) {
            super(inv, index, x, y);
            this.page = page;
        }

        @Override
        public boolean isActive() {
            return page == currentPage;
        }
    }

    /** 当前显示页（客户端视图状态；0 基，≤ maxPage）。 */
    public volatile int currentPage = 0;

    /** 翻页（客户端按钮用）。 */
    public void flipPage(int delta) {
        int max = feeder.maxPage();
        currentPage = Math.max(0, Math.min(max, currentPage + delta));
    }

    /** 标记槽 slotId 起点（固定：样板槽 0-26）。 */
    public int markerSlotStart() {
        return 27;
    }

    /** 标记槽 slotId 终点（不含；固定 54）。 */
    public int markerSlotEnd() {
        return 54;
    }

    public static InfiniteInterfaceMenu fromNetwork(int id, Inventory playerInventory,
            FriendlyByteBuf buffer) {
        var locator = MenuLocators.readFromPacket(buffer);
        var host = locator.locate(playerInventory.player, InfiniteInterfaceBE.class);
        if (host == null) {
            throw new IllegalStateException("Could not locate InfiniteInterfaceBE host");
        }
        return new InfiniteInterfaceMenu(id, playerInventory, host);
    }

    public InfiniteInterfaceBE getFeeder() {
        return feeder;
    }

    /** Screen 渲染槽位底框用（slots 是 protected，外部不可见）。 */
    public List<Slot> getSlotList() {
        return slots;
    }

    @Override
    public boolean stillValid(Player player) {
        return !feeder.isRemoved();
    }

    /**
     * 右键标记槽（slotId 9-17）= 用手中容器直接标记流体/气体（2026-08-28）：
     * 不放入槽、不消耗；空手右键 = 清空标记。其余点击走原版逻辑。
     */
    @Override
    public void clicked(int slotId, int button,
            net.minecraft.world.inventory.ClickType clickType, Player player) {
        if (clickType == net.minecraft.world.inventory.ClickType.PICKUP
                && button == 1 && slotId >= markerSlotStart() && slotId < markerSlotEnd()) {
            if (feeder.handleMarkerRightClick(slotId - markerSlotStart(), getCarried())) {
                return; // 已作为标记处理，跳过原版拆分/放置
            }
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = getSlot(slotIndex);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int patternEnd = 27;
        int markerEnd = 54;
        int upgradeEnd = 59;
        int invEnd = 95;
        int hotbarEnd = 104;

        if (slotIndex < patternEnd) {
            // 样板槽 → 玩家背包
            if (!moveItemStackTo(stack, upgradeEnd, hotbarEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else if (slotIndex < markerEnd) {
            // 标记槽 → 玩家背包
            if (!moveItemStackTo(stack, upgradeEnd, hotbarEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else if (slotIndex < upgradeEnd) {
            // 升级槽 → 玩家背包
            if (!moveItemStackTo(stack, upgradeEnd, hotbarEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 样板槽（仅已编码样板）或标记槽（任意物品）
            if (PatternDetailsHelper.isEncodedPattern(stack)
                    && !moveItemStackTo(stack, 0, patternEnd, false)) {
                return ItemStack.EMPTY;
            }
            if (!stack.isEmpty() && !moveItemStackTo(stack, patternEnd, markerEnd, false)) {
                return ItemStack.EMPTY;
            }
            // 背包内移动（避免卡死）
            if (!stack.isEmpty() && slotIndex >= invEnd && !moveItemStackTo(stack, upgradeEnd, invEnd, false)) {
                return ItemStack.EMPTY;
            }
            if (!stack.isEmpty() && slotIndex < invEnd && !moveItemStackTo(stack, invEnd, hotbarEnd, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public void broadcastChanges() {
        var lines = buildStatusLines();
        String key = String.join("\u0000", lines);
        if (!key.equals(lastStatusKey)) {
            lastStatusKey = key;
            if (opener instanceof ServerPlayer serverPlayer) {
                AE2Addon.NETWORK.send(
                        net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> serverPlayer),
                        new FeederStatusPacket(lines));
            }
        }
        super.broadcastChanges();
    }

    /** 服务端构建蓄水池状态行（§ 着色，客户端直接渲染）。 */
    private List<String> buildStatusLines() {
        List<String> lines = new ArrayList<>();
        var summary = feeder.reservoirSummary();
        lines.add("§e蓄水池: §f" + summary[0] + " §7种 / §f合计 " + summary[1]
                + " §8| §e已喂出: §f"
                + com.ae2addon.block.InfiniteInterfaceBE.fmt(feeder.totalFed())
                + " §8| §b推送 "
                + com.ae2addon.block.InfiniteInterfaceBE.fmt(
                        java.math.BigInteger.valueOf(feeder.feedRatePerSecond()))
                + "§7/s");
        var front = feeder.getFront();
        String machine = "§7无相邻机器";
        if (front != null && feeder.getLevel() != null) {
            var target = feeder.getLevel().getBlockEntity(feeder.getBlockPos().relative(front));
            if (target != null) {
                machine = "§a" + target.getBlockState().getBlock().getName().getString()
                        + " §7(" + front.getName() + ")";
            }
        }
        long rejects = feeder.rejectRatePerSecond();
        if (rejects > 0) {
            machine += " §c[拒收 " + rejects + "/s]";
        }
        lines.add("§7喂出目标: " + machine);
        return lines;
    }
}
