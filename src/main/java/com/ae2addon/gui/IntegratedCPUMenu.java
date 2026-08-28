package com.ae2addon.gui;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.guisync.GuiSync;
import appeng.menu.locator.MenuLocator;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.crafting.CraftingCPUMenu;
import com.ae2addon.block.IntegratedCPUBE;
import com.ae2addon.init.ModMenuTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkHooks;

/**
 * 集成 CPU 状态菜单：继承原版 CraftingCPUMenu（保留全部原版功能：
 * 任务状态同步、取消、调度模式），额外同步量子分裂线程（虚拟 lane）列表。
 * <p>
 * 继承方案（非 Mixin）：broadcastChanges 是虚方法，子类 override 必然被调用，
 * 不存在 Mixin 注入点不生效的问题。
 */
public class IntegratedCPUMenu extends CraftingCPUMenu {

    private static final String ACTION_SELECT_LANE = "select_lane";
    private static final String ACTION_CANCEL_ORDER = "cancel_order";

    @GuiSync(50)
    public boolean formed;
    @GuiSync(51)
    public int laneCount;
    @GuiSync(52)
    public int activeJobs;
    @GuiSync(53)
    public int selectedLaneIndex = -1;
    @GuiSync(60)
    public String lane0 = "";
    @GuiSync(61)
    public String lane1 = "";
    @GuiSync(62)
    public String lane2 = "";
    @GuiSync(63)
    public String lane3 = "";
    @GuiSync(64)
    public String lane4 = "";
    @GuiSync(65)
    public String lane5 = "";
    @GuiSync(66)
    public String lane6 = "";
    @GuiSync(67)
    public String lane7 = "";

    /** 客户端：完整 lane 列表（LaneListPacket 同步，突破 8 槽位限制） */
    public volatile java.util.List<String> fullLanes = java.util.List.of();

    /** 客户端：巨型订单列表（OrderListPacket 同步） */
    public volatile java.util.List<String> fullOrders = java.util.List.of();

    /** 服务端：上次发送的 lane 列表指纹（变化检测，防高频刷包） */
    private String lastLanesKey = "";

    /** 服务端：上次发送的订单列表指纹 */
    private String lastOrdersKey = "";

    private final IntegratedCPUBE core;

    private static boolean DIAG_LOGGED;

    // 服务端/客户端统一构造：host 为集成 CPU 方块（原版构造逻辑会 setCPU 主簇 + grid）
    public IntegratedCPUMenu(int id, Inventory playerInventory, IntegratedCPUBE core) {
        super(ModMenuTypes.INTEGRATED_CPU.get(), id, playerInventory, core);
        this.core = core;
        registerClientAction(ACTION_SELECT_LANE, Integer.class, this::selectLaneServer);
        registerClientAction(ACTION_CANCEL_ORDER, Integer.class, this::cancelOrderServer);
    }

    // 客户端构造（IForgeMenuType 工厂）：从网络包读 locator 定位 host
    public static IntegratedCPUMenu fromNetwork(int id, Inventory playerInventory,
            FriendlyByteBuf buffer) {
        var locator = MenuLocators.readFromPacket(buffer);
        var host = locator.locate(playerInventory.player, IntegratedCPUBE.class);
        if (host == null) {
            throw new IllegalStateException("Could not locate IntegratedCPUBE host");
        }
        return new IntegratedCPUMenu(id, playerInventory, host);
    }

    /**
     * 自定义 opener（MenuOpener.addOpener 注册）：服务端打开菜单并写 locator 协议。
     */
    public static boolean openMenu(Player player, MenuLocator locator,
            boolean returnedFromSubScreen) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        var host = locator.locate(player, IntegratedCPUBE.class);
        if (host == null) {
            return false;
        }
        var provider = new SimpleMenuProvider((containerId, inventory, ignored) -> {
            var menu = new IntegratedCPUMenu(containerId, inventory, host);
            menu.setLocator(locator);
            return menu;
        }, Component.translatable("gui.ae2addon.cpu.title"));
        NetworkHooks.openScreen(serverPlayer, provider, buffer -> {
            MenuLocators.writeToPacket(buffer, locator);
            buffer.writeBoolean(returnedFromSubScreen);
        });
        return true;
    }

    public IntegratedCPUBE getCore() {
        return core;
    }

    /**
     * 客户端请求：切换到指定线程（lane）的合成界面。
     */
    public void selectLane(int index) {
        if (isClientSide()) {
            sendClientAction(ACTION_SELECT_LANE, index);
        }
    }

    /**
     * 客户端请求：取消指定索引的巨型订单（整个订单，含所有批次）。
     */
    public void cancelOrder(int index) {
        if (isClientSide()) {
            sendClientAction(ACTION_CANCEL_ORDER, index);
        }
    }

    /**
     * 服务端处理：setCPU 到目标 lane，原版状态同步机制会自动刷新任务列表。
     */
    private void selectLaneServer(int index) {
        var owner = com.ae2addon.block.IntegratedCPURegistry.ownerOf(core.getCluster());
        if (owner == null) {
            return;
        }
        var cpus = owner.allCpus();
        if (index >= 0 && index < cpus.size()) {
            setCPU(cpus.get(index));
            selectedLaneIndex = index;
        }
    }

    /**
     * 服务端处理：取消巨型订单（按订单面板索引）。
     */
    private void cancelOrderServer(int index) {
        com.ae2addon.crafting.BatchedCraftingQueue.cancelOrder(index);
    }

    @Override
    public void broadcastChanges() {
        // 先刷新 lane 状态（在 super 的 GuiSync 发送之前）
        var primary = core.getCluster();
        var owner = com.ae2addon.block.IntegratedCPURegistry.ownerOf(primary);
        if (!DIAG_LOGGED) {
            DIAG_LOGGED = true;
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon] IntegratedCPUMenu.broadcastChanges: primary={}, owner={}, formed={}, laneCount={}",
                    primary == null ? "null" : "set",
                    owner == null ? "null" : "found",
                    owner == null ? "-" : owner.isFormed(),
                    owner == null ? "-" : owner.allCpus().size());
        }
        if (owner != null) {
            var cpus = owner.allCpus();
            int active = 0;
            var lanes = new java.util.ArrayList<String>(cpus.size());
            for (int index = 0; index < cpus.size(); index++) {
                var lane = cpus.get(index);
                if (lane.isBusy()) {
                    active++;
                }
                lanes.add(describeLane(index, lane));
            }
            formed = owner.isFormed();
            laneCount = cpus.size();
            activeJobs = active;
            // 完整列表变化检测：状态类型变化（空闲/忙碌/销毁）才发，避免每 tick 刷包
            String key = String.join("\u0000", lanes);
            if (!key.equals(lastLanesKey)) {
                lastLanesKey = key;
                if (getPlayer() instanceof net.minecraft.server.level.ServerPlayer sp) {
                    com.ae2addon.AE2Addon.NETWORK.send(
                            net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                            new com.ae2addon.network.LaneListPacket(
                                    lanes, laneCount, active, formed, selectedLaneIndex));
                }
            }
            // 兼容：同步前 8 个到 @GuiSync 字段（旧字段保留，客户端优先用 fullLanes）
            setLaneField(0, lanes.size() > 0 ? lanes.get(0) : "");
            setLaneField(1, lanes.size() > 1 ? lanes.get(1) : "");
            setLaneField(2, lanes.size() > 2 ? lanes.get(2) : "");
            setLaneField(3, lanes.size() > 3 ? lanes.get(3) : "");
            setLaneField(4, lanes.size() > 4 ? lanes.get(4) : "");
            setLaneField(5, lanes.size() > 5 ? lanes.get(5) : "");
            setLaneField(6, lanes.size() > 6 ? lanes.get(6) : "");
            setLaneField(7, lanes.size() > 7 ? lanes.get(7) : "");
        }

        // 巨型订单列表同步（变化检测：订单数/进度/状态变化才发）
        syncOrders();
        super.broadcastChanges();
    }

    /** 构建订单描述列表并发送（变化检测节流）。按当前集成 CPU 所属网格过滤（2026-08-27：
     *  不同网络的 CPU 终端不应显示同一巨型订单——此前全局列表导致所有 CPU 同步显示）。 */
    private void syncOrders() {
        appeng.api.networking.IGrid myGrid = null;
        try {
            if (core != null && core.getMainNode() != null && core.getMainNode().getNode() != null) {
                myGrid = core.getMainNode().getNode().getGrid();
            }
        } catch (RuntimeException ignored) {
            // 网格未就绪：myGrid=null → 显示全部（兼容旧行为）
        }
        var orderList = com.ae2addon.crafting.BatchedCraftingQueue.getOrders(myGrid);
        var descs = new java.util.ArrayList<String>(orderList.size());
        for (var order : orderList) {
            descs.add(describeOrder(order));
        }
        String key = String.join("\u0000", descs);
        if (!key.equals(lastOrdersKey)) {
            lastOrdersKey = key;
            if (getPlayer() instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.ae2addon.AE2Addon.NETWORK.send(
                        net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                        new com.ae2addon.network.OrderListPacket(descs));
            }
        }
    }

    /** 订单面板行描述：物品 + 进度 + 状态（JSON 序列化 Component，客户端本地化）。 */
    private static String describeOrder(com.ae2addon.crafting.BatchedCraftingOrder order) {
        String itemName = "?";
        try {
            itemName = order.getWhat().getDisplayName().getString();
        } catch (RuntimeException ignored) {
            // 显示兜底
        }
        var statusKey = switch (order.getStatus()) {
            case QUEUED -> "gui.ae2addon.order.queued";
            case RUNNING -> "gui.ae2addon.order.running";
            case DONE -> "gui.ae2addon.order.done";
            case FAILED -> "gui.ae2addon.order.failed";
            case CANCELLED -> "gui.ae2addon.order.cancelled";
        };
        Component desc = Component.translatable("gui.ae2addon.order.line",
                Component.literal(itemName),
                order.getCompletedCount(), order.getBatchCount(),
                Component.translatable(statusKey));
        return Component.Serializer.toJson(desc);
    }

    private void setLaneField(int index, String value) {
        switch (index) {
            case 0 -> lane0 = value;
            case 1 -> lane1 = value;
            case 2 -> lane2 = value;
            case 3 -> lane3 = value;
            case 4 -> lane4 = value;
            case 5 -> lane5 = value;
            case 6 -> lane6 = value;
            default -> lane7 = value;
        }
    }

    public String lane(int index) {
        // 优先完整列表（LaneListPacket 同步）；兜底 @GuiSync 字段
        if (index >= 0 && index < fullLanes.size()) {
            return fullLanes.get(index);
        }
        return switch (index) {
            case 0 -> lane0;
            case 1 -> lane1;
            case 2 -> lane2;
            case 3 -> lane3;
            case 4 -> lane4;
            case 5 -> lane5;
            case 6 -> lane6;
            default -> lane7;
        };
    }

    /**
     * 生成 lane 状态描述：服务端构建 translatable Component 并 JSON 序列化，
     * 客户端 Screen 反序列化后按玩家语言本地化渲染。
     * 状态词通过固定 key 标记（"gui.ae2addon.cpu.lane.idle"），客户端据此判断空闲/忙碌配色。
     */
    private static String describeLane(int index, CraftingCPUCluster lane) {
        Component name = index == 0
                ? Component.translatable("gui.ae2addon.cpu.lane.main")
                : Component.translatable("gui.ae2addon.cpu.lane.thread", index);
        Component desc;
        if (lane == null || lane.isDestroyed()) {
            desc = Component.translatable("gui.ae2addon.cpu.lane.destroyed", name);
        } else if (!lane.isBusy()) {
            desc = Component.translatable("gui.ae2addon.cpu.lane.idle", name);
        } else {
            var status = lane.getJobStatus();
            if (status == null) {
                desc = Component.translatable("gui.ae2addon.cpu.lane.working", name);
            } else {
                String itemName = "?";
                if (status.crafting() != null && status.crafting().what() != null) {
                    try {
                        itemName = status.crafting().what().getDisplayName().getString();
                    } catch (RuntimeException ignored) {
                        // 显示兜底
                    }
                }
                // 注意：不带百分比——进度每 tick 变化会导致 LaneListPacket 节流失效
                desc = Component.translatable("gui.ae2addon.cpu.lane.working", name, itemName);
            }
        }
        return Component.Serializer.toJson(desc);
    }
}
