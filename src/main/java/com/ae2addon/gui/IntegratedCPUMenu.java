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

    /** 客户端：订单 id 列表（与 fullOrders 同序同长；取消时回传 id 而不是行索引，见 2026-09-15 修复） */
    public volatile java.util.List<Integer> fullOrderIds = java.util.List.of();

    // ── 一键成型（2026-09-24 sensei 第 3 阶段）────────────────────────────────
    // 构建状态/进度同步给界面：按钮文案与「已放置 N/M」都用它们。

    @GuiSync(70)
    public String buildState = "IDLE";
    @GuiSync(71)
    public String buildMessage = "";
    @GuiSync(72)
    public int buildPlaced;
    @GuiSync(73)
    public int buildTotal;

    /** 客户端按钮 → 服务端：请求一键成型 */
    public void requestBuild() {
        sendClientAction(ACTION_BUILD, 0);
    }

    private static final String ACTION_BUILD = "build_structure";

    private void buildServer(int ignored) {
        if (core != null) {
            // 服务端权威：直接让 BE 跑构建（它会自己取料/预检/摆放）
            core.startBuild(getPlayer());
        }
        broadcastChanges();
    }

    // ── 一键回收（2026-09-25 sensei）──────────────────────────────────────────
    // 界面按一次 = 变成「确定回收」；3 秒内再按一次才真的拆。
    // 双击判定放在客户端（IntegratedCPUScreen$RecycleButton），这里只负责执行。

    /** 是否正在回收（界面显示进度） */
    @GuiSync(74)
    public boolean recycling;
    @GuiSync(75)
    public int recycleDone;
    @GuiSync(76)
    public int recycleTotal;
    /** 上一次回收是否已结束（true 且 total>0 → 按钮显示"已回收 N"） */
    @GuiSync(77)
    public boolean recycleFinished;

    // ── 结构形态选择（2026-09-25 sensei 选 A1：界面加形态切换）──────────────
    // 决定「一键成型放哪一份结构」：false = 含拓展（31×53×41）、true = 无拓展（27×44×42）。
    // 结构**判定**两份都认（matchAny），这里只管"一键成型放哪份"。

    /** 当前选定的形态是不是"无拓展单元" */
    @GuiSync(78)
    public boolean noExpandForm;

    /** -1 automatic; 0 south, 1 west, 2 north, 3 east. */
    @GuiSync(79)
    public int buildFacing = -1;

    private static final String ACTION_BUILD_FACING = "build_facing";

    public void requestBuildFacing(int facing) {
        sendClientAction(ACTION_BUILD_FACING, facing);
    }

    private void buildFacingServer(int facing) {
        if (core != null) core.setBuildFacing(facing);
        broadcastChanges();
    }

    /** 客户端按钮 → 服务端：切换结构形态（true = 无拓展） */
    public void requestFormVariant(boolean noExpand) {
        sendClientAction(ACTION_FORM_VARIANT, noExpand ? 1 : 0);
    }

    private static final String ACTION_FORM_VARIANT = "form_variant";

    private void formVariantServer(int flag) {
        if (core != null) {
            core.setNoExpandForm(flag != 0);
        }
        broadcastChanges();
    }

    /** 客户端按钮 → 服务端：确认回收（拆掉除控制器以外的结构方块） */
    public void requestRecycle() {
        sendClientAction(ACTION_RECYCLE, 0);
    }

    private static final String ACTION_RECYCLE = "recycle_structure";

    private void recycleServer(int ignored) {
        if (core != null) {
            int n = core.startRecycle(getPlayer());
            var p = getPlayer();
            if (p != null) {
                p.sendSystemMessage(Component.literal(n > 0
                        ? "§b[集成CPU] 开始回收：" + n + " 块结构方块将退回（控制器保留）"
                        : "§e[集成CPU] 没有可回收的结构方块（或正在摆放/回收中）"));
            }
        }
        broadcastChanges();
    }

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
        registerClientAction(ACTION_HALT, Integer.class, this::haltServer);
        registerClientAction(ACTION_CANCEL_ALL, Integer.class, this::cancelAllServer);
        registerClientAction(ACTION_BUILD, Integer.class, this::buildServer);
        registerClientAction(ACTION_RECYCLE, Integer.class, this::recycleServer);
        registerClientAction(ACTION_FORM_VARIANT, Integer.class, this::formVariantServer);
        registerClientAction(ACTION_BUILD_FACING, Integer.class, this::buildFacingServer);
    }

    // ── 急停（2026-09-19 sensei：AE2-VM 超 Long.MAX 会报错，需要强行停工按钮）──

    private static final String ACTION_HALT = "halt";

    /** 同步给界面的急停状态（渲染用） */
    @GuiSync(54)
    public boolean halted;

    /** 界面按下的急停/恢复：切到目标状态并广播（1=急停，0=恢复） */
    public void requestHalt(boolean target) {
        sendClientAction(ACTION_HALT, target ? 1 : 0);
    }

    private void haltServer(int flag) {
        boolean want = flag != 0;
        com.ae2addon.crafting.CraftingCompat.setCpuHalted(want);
        this.halted = want;
        broadcastChanges();
    }

    /**
     * **强制取消所有订单**（2026-09-19 sensei：急停后左按钮显示「删除」，点它就是强制取消订单）。
     * <p>
     * 做的事：取消我们自己的巨型订单队列 + 取消当前 CPU 簇的任务。
     * **注意：不清除急停状态** —— 想恢复线程工作请点右侧「恢复」按钮（sensei 定义的两个按钮）。
     */
    public void cancelAllOrders() {
        sendClientAction(ACTION_CANCEL_ALL, 1);
    }

    private static final String ACTION_CANCEL_ALL = "cancel_all";

    private void cancelAllServer(int flag) {
        int cancelled = 0;
        try {
            // ① 我们自己的巨型订单队列（一次性全下取消；会 cancel 各批次的 CraftingLink）
            cancelled += com.ae2addon.crafting.BatchedCraftingQueue.cancelAll();
            // ② 本机**所有线程**的任务：主簇 + 全部量子分裂 lane
            //  ⚠ 2026-09-19（sensei：「删除后订单没有全部取消完，仍然会有线程在工作」）：
            //  原来这里只 `core.getCluster().cancelJob()` —— 只取消主簇，
            //  几十条 lane 上的任务一个都没动。改用 IntegratedCPUBE.cancelAllLanes()。
            if (core != null) {
                cancelled += core.cancelAllLanes();
            }
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] 强制取消所有订单：已清理 {} 项（主簇 + 全部 lane；急停状态保持不变）",
                    cancelled);
        } catch (Throwable t) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 强制取消所有订单时异常: {}", t.toString());
        }
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
     * 客户端请求：取消指定 **id** 的巨型订单（整个订单，含所有批次）。
     * <p>
     * 2026-09-15 修复：此前传的是面板**行索引**，而面板列表是按网格过滤的、
     * 服务端却按全局列表取索引 → 多网络/多玩家时索引错位，会取消到别人的订单。
     * 现在传订单 id（{@code fullOrderIds} 与 {@code fullOrders} 同序下发）。
     */
    public void cancelOrderById(int orderId) {
        if (isClientSide()) {
            sendClientAction(ACTION_CANCEL_ORDER, orderId);
        }
    }

    /**
     * 服务端处理：setCPU 到目标 lane，原版状态同步机制会自动刷新任务列表。
     * <p>
     * ⚠ 2026-09-24 sensei：「没办法在量子分裂线程列表内切换线程」—— 根因同 `broadcastChanges`：
     * 这里也是 {@code ownerOf(core.getCluster())}，而我们的结构建不出 AE2 主簇 →
     * `getCluster()` 恒 null → owner 恒 null → **直接 return，点了没反应**。
     * 现在同样回退成 core 自己。
     */
    private void selectLaneServer(int index) {
        var owner = com.ae2addon.block.IntegratedCPURegistry.ownerOf(core.getCluster());
        if (owner == null) {
            owner = core;
        }
        if (owner == null) {
            return;
        }
        var cpus = owner.allCpus();
        if (index >= 0 && index < cpus.size()) {
            setCPU(cpus.get(index));
            selectedLaneIndex = index;
            // 选中项变化也要下发：LaneListPacket 的发送条件是"lane 文本变化"，
            // 单纯点一下不会发 → 客户端的高亮/选中行不会更新（2026-09-24）。
            resendLaneList(owner);
        }
    }

    /** 立刻把当前 lane 列表（含选中项）下发给客户端 */
    private void resendLaneList(com.ae2addon.block.IntegratedCPUBE owner) {
        if (!(getPlayer() instanceof net.minecraft.server.level.ServerPlayer sp)) {
            return;
        }
        var cpus = owner.allCpus();
        var lanes = new java.util.ArrayList<String>(cpus.size());
        int active = 0;
        for (int i = 0; i < cpus.size(); i++) {
            var lane = cpus.get(i);
            if (lane.isBusy()) {
                active++;
            }
            lanes.add(describeLane(i, lane));
        }
        lastLanesKey = String.join("\u0000", lanes);
        com.ae2addon.AE2Addon.NETWORK.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                new com.ae2addon.network.LaneListPacket(
                        lanes, cpus.size(), active, owner.isFormed(), selectedLaneIndex));
    }

    /**
     * 服务端处理：按**订单 id** 取消巨型订单（2026-09-15：不再按行索引，防跨网络错位）。
     */
    private void cancelOrderServer(int orderId) {
        boolean hit = com.ae2addon.crafting.BatchedCraftingQueue.cancelOrderById(orderId);
        if (!hit) {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon] 订单取消请求未命中（可能已完成/已移除）id={}", orderId);
        }
    }

    @Override
    public void broadcastChanges() {
        // 先刷新 lane 状态（在 super 的 GuiSync 发送之前）
        //
        // ⚠ 2026-09-24 修「GUI 里线程数显示 0」：不能再靠 `ownerOf(core.getCluster())` 找归属。
        //   我们的多方块结构**永远建不出 AE2 主簇**（CraftingCPUCalculator 要求 ≤17³ 实心，
        //   见 IntegratedCPUBE#refreshLanes 的注释），所以 getCluster() 恒为 null →
        //   ownerOf(null) 恒为 null → 整段同步被跳过，界面永远显示 0 个 lane。
        //   这个菜单**本来就是某个控制器的菜单**，所以「主人就是它自己」，直接用 core。
        var primary = core.getCluster();
        var owner = com.ae2addon.block.IntegratedCPURegistry.ownerOf(primary);
        if (owner == null) {
            owner = core;
        }
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
            // 急停状态同步（服务端权威，客户端据此显示"急停中/恢复"）
            halted = com.ae2addon.crafting.CraftingCompat.cpuHalted;
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
        } else {
            // ⚠ 2026-09-24：菜单现在**未成型也能打开**（sensei：界面始终可打开、成型状态放进 GUI）。
            // 未成型时 core.getCluster() 为 null、owner 自然为 null —— 必须显式把状态同步成
            // 「未成型 + 0 线程」，否则界面会一直显示上一次的旧值（甚至以为已成型）。
            if (core != null) {
                formed = core.isFormed();
                if (!formed) {
                    laneCount = 0;
                    activeJobs = 0;
                }
            }
        }

        // 一键成型状态同步（未成型/成型都要显示，所以放在 if/else 外面）
        if (core != null) {
            buildState = core.getBuildState().name();
            buildMessage = core.getBuildMessage();
            buildPlaced = core.getBuildPlaced();
            buildTotal = core.getBuildTotal();
            // 一键回收进度（2026-09-25）
            recycling = core.isRecycling();
            recycleDone = core.getRecycleProgress();
            recycleTotal = core.getRecycleTotal();
            recycleFinished = core.isRecycleFinished();
            // 结构形态（2026-09-25 A1）：界面按钮显示当前选定的是哪一份
            noExpandForm = core.isNoExpandForm();
            buildFacing = core.getBuildFacing();
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
        // 与描述**同序同长**地下发订单 id（客户端取消时回传 id，避免行索引在多网络下错位）
        var ids = new java.util.ArrayList<Integer>(orderList.size());
        for (var order : orderList) {
            descs.add(describeOrder(order));
            ids.add(order.getOrderId());
        }
        String key = String.join("\u0000", descs);
        if (!key.equals(lastOrdersKey)) {
            lastOrdersKey = key;
            if (getPlayer() instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.ae2addon.AE2Addon.NETWORK.send(
                        net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                        new com.ae2addon.network.OrderListPacket(ids, descs));
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
