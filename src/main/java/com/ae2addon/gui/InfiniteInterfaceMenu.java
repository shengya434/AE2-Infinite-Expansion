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

        // 样板槽 3×3（左侧）
        var patternInv = feeder.getPatternInventory();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                addSlot(new Slot(patternInv, col + row * 3, 26 + col * 18, 17 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return PatternDetailsHelper.isEncodedPattern(stack);
                    }
                });
            }
        }

        // 标记槽 3×3（右侧，任意物品 = 自动补货清单）
        var markerInv = feeder.getMarkerInventory();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                addSlot(new Slot(markerInv, col + row * 3, 96 + col * 18, 17 + row * 18));
            }
        }

        // 玩家背包 9×3
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 120 + row * 18));
            }
        }
        // 快捷栏 1×9
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 176));
        }
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

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = getSlot(slotIndex);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (slotIndex < 9) {
            // 样板槽 → 玩家背包
            if (!moveItemStackTo(stack, 18, 54, true)) {
                return ItemStack.EMPTY;
            }
        } else if (slotIndex < 18) {
            // 标记槽 → 玩家背包
            if (!moveItemStackTo(stack, 18, 54, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 样板槽（仅已编码样板）或标记槽（任意物品）
            if (PatternDetailsHelper.isEncodedPattern(stack)
                    && !moveItemStackTo(stack, 0, 9, false)) {
                return ItemStack.EMPTY;
            }
            if (!stack.isEmpty() && !moveItemStackTo(stack, 9, 18, false)) {
                return ItemStack.EMPTY;
            }
            // 背包内移动（避免卡死）
            if (!stack.isEmpty() && slotIndex >= 45 && !moveItemStackTo(stack, 18, 45, false)) {
                return ItemStack.EMPTY;
            }
            if (!stack.isEmpty() && slotIndex < 45 && !moveItemStackTo(stack, 45, 54, false)) {
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
