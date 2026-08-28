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
        final int gridY = 80; // 上移 3px：给状态行 3 行（含开关行）腾空间
        // 样板槽（左 3×3×5页；每页9格，容量卡最多4→45格）
        var patternInv = feeder.getPatternInventory();
        for (int i = 0; i < 45; i++) {
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
        // 标记槽（右 3×3×5页；任意物品 = 自动补货清单）
        var markerInv = feeder.getMarkerInventory();
        for (int i = 0; i < 45; i++) {
            int page = i / 9;
            int col = (i % 9) % 3;
            int row = (i % 9) / 3;
            addSlot(new PageSlot(markerInv, i, 96 + col * 18, gridY + row * 18, page));
        }
        // 升级槽（9个，参数区下方整行；容量/速度/红石/反向/感应/频道/虚拟合成全部可同时插）
        var upgradeInv = feeder.getUpgrades().toContainer();
        for (int i = 0; i < 9; i++) {
            addSlot(new UpgradeSlot(upgradeInv, i, 8 + i * 18, 59));
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

    /** 升级槽：mayPlace 做数量上限检查（客户端 UI 提示 + moveItemStackTo 用）；
     *  服务端点击插入由 clicked() 兜底拦截（vanilla 容器路径不过滤）。 */
    private class UpgradeSlot extends Slot {
        UpgradeSlot(net.minecraft.world.Container inv, int index, int x, int y) {
            super(inv, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (stack.isEmpty()) {
                return true;
            }
            net.minecraft.world.item.Item item = stack.getItem();
            int max = appeng.api.upgrades.Upgrades.getMaxInstallable(
                    item, com.ae2addon.init.ModBlocks.INFINITE_INTERFACE.get().asItem());
            if (max <= 0) {
                return false; // 本机不支持的卡
            }
            return countUpgrade(item) < max;
        }
    }

    /** 升级槽中某卡现有数量（直接数容器，不依赖 installed 缓存）。 */
    private int countUpgrade(net.minecraft.world.item.Item item) {
        int n = 0;
        var inv = feeder.getUpgrades();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStackInSlot(i).getItem() == item) {
                n++;
            }
        }
        return n;
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

    /** 标记槽 slotId 起点（固定：样板槽 0-44）。 */
    public int markerSlotStart() {
        return 45;
    }

    /** 标记槽 slotId 终点（不含；固定 90）。 */
    public int markerSlotEnd() {
        return 90;
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
        // 中键点击标记槽 = 循环切换该标记的独立缓存目标
        if (slotId >= markerSlotStart() && slotId < markerSlotEnd()
                && clickType == net.minecraft.world.inventory.ClickType.PICKUP
                && button == 2) {
            feeder.cycleMarkerTarget(slotId - markerSlotStart());
            return;
        }
        // 升级槽数量上限兜底（服务端）：vanilla 容器插入不过滤 mayPlace
        if (slotId >= 90 && slotId < 99 && !getCarried().isEmpty()
                && (clickType == net.minecraft.world.inventory.ClickType.PICKUP
                    || clickType == net.minecraft.world.inventory.ClickType.QUICK_MOVE)) {
            Slot slot = getSlot(slotId);
            if (slot instanceof UpgradeSlot us && !us.mayPlace(getCarried())) {
                return; // 超限，拒绝插入
            }
        }
        // 标记槽普通放入 → 转虚拟标记（标记区不占真实存储）
        if (slotId >= markerSlotStart() && slotId < markerSlotEnd()
                && !getCarried().isEmpty()
                && clickType == net.minecraft.world.inventory.ClickType.PICKUP) {
            // 左键放入 = 标记（与右键一致）；不放物品进槽
            if (button == 0 && feeder.handleMarkerRightClick(
                    slotId - markerSlotStart(), getCarried())) {
                return;
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
        int patternEnd = 45;
        int markerEnd = 90;
        int upgradeEnd = 99;
        int invEnd = 126;   // 玩家主格 27（99..125）
        int hotbarEnd = 135; // 快捷栏 9（126..134）；总 135 格

        if (slotIndex < patternEnd) {
            // 样板槽 → 玩家背包
            if (!moveItemStackTo(stack, upgradeEnd, hotbarEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else if (slotIndex < markerEnd) {
            // 标记槽：虚拟标记不可取回——shift 点击 = 取消标记（清空 + 退缓存）
            feeder.markByKey(slotIndex - markerSlotStart(), null);
            return ItemStack.EMPTY;
        } else if (slotIndex < upgradeEnd) {
            // 升级槽 → 玩家背包
            if (!moveItemStackTo(stack, upgradeEnd, hotbarEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 样板槽（仅已编码样板）；标记槽不收真实物品（虚拟标记，
            // shift 移入会卡死被吞——2026-08-28 BUG，改为不放）
            if (PatternDetailsHelper.isEncodedPattern(stack)
                    && !moveItemStackTo(stack, 0, patternEnd, false)) {
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

    /** 相对面中文名（开关行显示用）。 */
    private static String sideName(appeng.api.orientation.RelativeSide side) {
        return switch (side) {
            case FRONT -> "正";
            case BACK -> "后";
            case LEFT -> "左";
            case RIGHT -> "右";
            case TOP -> "上";
            case BOTTOM -> "下";
        };
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
        lines.add("§7开关: §e抽取[" + (feeder.activeExtract ? "§a开" : "§c关") + "§e] "
                + "§7方向[§e" + sideName(feeder.extractSide) + "§7] §8| §e喂出["
                + (feeder.activeFeed ? "§a开" : "§c关") + "§e] §7←点击切换");
        lines.add("§7参数: 目标=" + feeder.stockTargetValue()
                + " 间隔=" + feeder.restockIntervalValue()
                + " 预算=" + feeder.feedBudgetValue());
        return lines;
    }
}
