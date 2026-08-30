package com.ae2addon.gui;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.menu.locator.MenuLocators;
import com.ae2addon.AE2Addon;
import com.ae2addon.block.InfiniteInterfaceBE;
import com.ae2addon.init.ModMenuTypes;
import com.ae2addon.network.FeederStatusPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
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

    /** 网络工具 3×3 卡槽栏位置（GUI 右下角，与右缘对齐；2026-08-30 sensei）。 */
    public static final int TOOLBOX_X = 157;
    public static final int TOOLBOX_Y = 101;

    /** 网络工具联动：背包有网络工具时显示其 3×3 卡槽栏（null=无工具）。 */
    private final appeng.items.contents.NetworkToolMenuHost toolHost;
    /** 网络工具所在玩家背包槽位（-1=无）。 */
    private final int toolSlot;

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
                    // 页授权（服务端兜底 + 客户端即时提示）：越界页拒绝放入
                    if (index >= 9 + feeder.capacityCards() * 9) {
                        return false;
                    }
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
            addSlot(new PageSlot(markerInv, i, 96 + col * 18, gridY + row * 18, page) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    // 页授权：越界页拒绝放入；标记槽只收虚拟标记（WGS）
                    if (index >= 9 + feeder.capacityCards() * 9) {
                        return false;
                    }
                    return stack.isEmpty()
                            || stack.getItem() instanceof appeng.items.misc.WrappedGenericStack;
                }
            });
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
        // 网络工具 3×3 卡槽栏（右下角）：背包里有网络工具时出现，可放升级卡
        // （AE2 原版行为：打开机器 GUI 时显示工具内的卡，方便换卡）
        var foundTool = appeng.items.tools.NetworkToolItem.findNetworkToolInv(opener);
        this.toolHost = foundTool;
        Integer ts = foundTool == null ? null : foundTool.getSlot();
        this.toolSlot = ts == null ? -1 : ts;
        if (toolHost != null) {
            var toolContainer = toolHost.getInternalInventory().toContainer();
            for (int i = 0; i < 9; i++) {
                int col = i % 3;
                int row = i / 3;
                addSlot(new ToolboxSlot(toolContainer, i,
                        TOOLBOX_X + col * 18, TOOLBOX_Y + row * 18));
            }
        }
    }

    /** 网络工具卡槽：只收升级卡（任意 mod 注册的卡；AE2 工具箱同款规则）。 */
    private static class ToolboxSlot extends Slot {
        ToolboxSlot(net.minecraft.world.Container inv, int index, int x, int y) {
            super(inv, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.isEmpty()
                    || appeng.api.upgrades.Upgrades.isUpgradeCardItem(stack.getItem());
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

    /** 背包是否有网络工具（客户端据此画 3×3 卡槽栏底框）。 */
    public boolean hasToolbox() {
        return toolHost != null;
    }

    /** Screen 渲染槽位底框用（slots 是 protected，外部不可见）。 */
    public List<Slot> getSlotList() {
        return slots;
    }

    @Override
    public boolean stillValid(Player player) {
        if (feeder.isRemoved()) {
            return false;
        }
        if (toolHost != null && toolSlot >= 0) {
            // 网络工具被移动/移除 → 关闭菜单（AE2 同款行为）
            if (player.getInventory().getItem(toolSlot) != toolHost.getItemStack()) {
                return false;
            }
        }
        return true;
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
        int invEnd = 126;    // 玩家主格 27（99..125）
        int hotbarEnd = 135; // 快捷栏 9（126..134）
        int toolboxEnd = 144; // 网络工具 9（135..143）；总 144 格

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
        } else if (slotIndex < toolboxEnd) {
            // 网络工具卡槽：优先 → 接口升级槽（90..98）；其次 → 玩家背包
            if (!moveItemStackTo(stack, upgradeEnd - 9, upgradeEnd, false)
                    && !moveItemStackTo(stack, upgradeEnd, invEnd, false)
                    && !moveItemStackTo(stack, invEnd, hotbarEnd, false)) {
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

    /** 服务端构建蓄水池状态行（Component 序列化 JSON，客户端按语言渲染）。 */
    private List<String> buildStatusLines() {
        List<String> lines = new ArrayList<>();
        var summary = feeder.reservoirSummary();
        lines.add(json(Component.translatable("gui.ae2addon.feeder.status",
                summary[0], summary[1],
                com.ae2addon.block.InfiniteInterfaceBE.fmt(feeder.totalFed()),
                com.ae2addon.block.InfiniteInterfaceBE.fmt(
                        java.math.BigInteger.valueOf(feeder.feedRatePerSecond())))));
        var front = feeder.getFront();
        net.minecraft.network.chat.MutableComponent machine =
                Component.translatable("gui.ae2addon.feeder.no_machine");
        if (front != null && feeder.getLevel() != null) {
            var target = feeder.getLevel().getBlockEntity(feeder.getBlockPos().relative(front));
            if (target != null) {
                machine = Component.literal("§a")
                        .append(target.getBlockState().getBlock().getName())
                        .append(Component.literal(" §7(" + front.getName() + ")"));
            }
        }
        long rejects = feeder.rejectRatePerSecond();
        if (rejects > 0) {
            machine.append(Component.translatable("gui.ae2addon.feeder.reject", rejects));
        }
        lines.add(json(Component.translatable("gui.ae2addon.feeder.target", machine)));
        lines.add(json(Component.translatable("gui.ae2addon.feeder.switch",
                Component.translatable(feeder.activeExtract
                        ? "gui.ae2addon.feeder.on" : "gui.ae2addon.feeder.off"),
                Component.translatable("gui.ae2addon.side."
                        + feeder.extractSide.name().toLowerCase(java.util.Locale.ROOT)),
                Component.translatable(feeder.activeFeed
                        ? "gui.ae2addon.feeder.on" : "gui.ae2addon.feeder.off"))));
        lines.add(json(Component.translatable("gui.ae2addon.feeder.params",
                feeder.stockTargetValue(), feeder.restockIntervalValue(), feeder.feedBudgetValue())));
        return lines;
    }

    /** Component → JSON（客户端反序列化按语言渲染）。 */
    private static String json(net.minecraft.network.chat.Component c) {
        return net.minecraft.network.chat.Component.Serializer.toJson(c);
    }
}
