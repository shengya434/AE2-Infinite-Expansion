package com.ae2addon.gui;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.menu.AEBaseMenu;
import appeng.menu.locator.MenuLocator;
import com.ae2addon.AE2Addon;
import com.ae2addon.recipe.QianJiPatternData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 千机·样板终端菜单（2026-09-21 v249）。
 * <p>
 * 界面构成：
 * <ul>
 *   <li>**空白样板槽**（上）+ **编码样板槽**（下）：中间用一个灰色向下箭头隔开（箭头在 screen 里画）。</li>
 *   <li>**升级卡槽 + 量子缠绕奇点槽**（v274，只有 AE2WTLib 形态的宿主才有）：让终端能通过量子网络桥
 *       连到远处的网络（否则只能在无线访问点附近用）。</li>
 *   <li>玩家背包（3×9 + 快捷栏）。</li>
 * </ul>
 * "整张网络的样板长列表 + 搜索"不进菜单槽位 —— 那是**服务端按窗口推送**的数据
 * （见 {@code QianJiNetworkPatterns} 与终端的列表包），板子有几千条，绝不能当槽位同步。
 * <p>
 * 一个菜单吃**四种载体**（2026-09-21 v272/v273/v274）：
 * 线缆面板 part、自家无线终端、AE2WTLib 通用终端上的千机状态、AE2WTLib 加持的自家无线终端。
 * 载体差异全被 {@link QianJiTerminalHost} 吸收，菜单只认那个接口。
 * <p>
 * 继承 AE2 的 {@link AEBaseMenu}（v273 起）：通用终端的「打开下一个终端」按钮走 AE2WTLib 的
 * {@code CycleTerminalPacket}，它要求 {@code player.containerMenu instanceof AEBaseMenu} 且能取到 locator。
 * 顺带白拿：无线宿主的范围检查/耗电由 {@code AEBaseMenu#broadcastChanges} 自动驱动、部件被拆自动失效。
 */
public class QianJiTerminalMenu extends AEBaseMenu implements QianJiEncodedSlotHolder {

    /** 空白样板槽在菜单里的索引（相对样板区） */
    public static final int SLOT_BLANK = 0;
    /** 编码样板槽在菜单里的索引（相对样板区） */
    public static final int SLOT_ENCODED = 1;

    /** 升级卡/奇点那一行的说明文字 y（screen 画字用，加高形态才有） */
    public static final int EXTRA_LABEL_Y = 284;
    /** 升级卡槽与量子缠绕奇点槽这一行的 y */
    public static final int EXTRA_SLOT_Y = 296;
    /** 第一个升级卡槽的 x */
    private static final int EXTRA_X0 = 4;
    /** 槽间距 */
    private static final int SLOT_STEP = 18;

    /** 服务端：终端宿主（客户端为 null） */
    @Nullable
    private final QianJiTerminalHost host;
    /** 空白样板槽的库存（1 格） */
    private final IItemHandler blankInv;
    /** 编码样板槽的库存（1 格） */
    private final IItemHandler encodedInv;
    /** 是不是通用终端（AE2WTLib 的 WUT）上的一种状态 —— 客户端据此画「下一个终端」按钮 */
    private final boolean universal;
    /** 升级卡槽数量（0 = 这个形态没有升级卡槽） */
    private final int upgradeCount;
    /** 有没有量子缠绕奇点槽 */
    private final boolean hasSingularity;

    /** 空白样板槽的 Slot（服务端与客户端各一份，索引一致才能同步上） */
    private Slot blankSlot;
    /** 编码样板槽的 Slot */
    private Slot encodedSlot;
    /** 已经因为宿主失效关过一次界面（避免每 tick 重复关） */
    private boolean closedByHost;

    /** 服务端构造（part / 自家无线终端 / 通用终端状态） */
    public QianJiTerminalMenu(int id, Inventory playerInventory, QianJiTerminalHost host) {
        super(com.ae2addon.init.ModMenuTypes.QIAN_JI_TERMINAL.get(), id, playerInventory, host);
        this.host = host;
        this.universal = host.isUniversal();
        this.blankInv = host.blankInv();
        this.encodedInv = host.encodedInv();
        final IUpgradeInventory upgrades = host.upgradeInv();
        final InternalInventory singularity = host.singularityInv();
        this.upgradeCount = upgrades == null ? 0 : upgrades.size();
        this.hasSingularity = singularity != null;
        // 打开时把残留在槽里的空白样板送回网络（v282）：老存档、或者当时网络不可用留下的
        try {
            QianJiTerminalHandlers.flushBlankToNetwork(host.getTerminalGrid(), host.blankInv(), null);
        } catch (Throwable t) {
            AE2Addon.LOGGER.warn("[ae2addon][terminal] 打开时清理空白样板槽失败：{}", t.toString());
        }
        buildSlots(playerInventory, upgrades, singularity, upgradeCount, hasSingularity);
    }

    /**
     * 客户端构造（走菜单类型工厂）。
     * <p>
     * 客户端**不需要宿主**：槽的内容由原版槽位同步带过来（与面板形态完全一致的做法），
     * 网络数据一律走服务端查询包（{@code QianJiTerminalQueryPacket}）。
     * 只需要从打开包里读"有没有通用终端 / 有几个升级卡槽 / 有没有奇点槽"，
     * 好让**槽位数量与顺序和服务端完全一致**（数量不一致会直接串位）。
     */
    public QianJiTerminalMenu(int id, Inventory playerInventory, FriendlyByteBuf buf) {
        super(com.ae2addon.init.ModMenuTypes.QIAN_JI_TERMINAL.get(), id, playerInventory, null);
        this.host = null;
        boolean universal = false;
        int upgradeCount = 0;
        boolean hasSingularity = false;
        if (buf != null && buf.readableBytes() > 0) {
            // 面板形态（老路径）不写任何数据 → 三个默认值，照旧能开
            universal = buf.readBoolean();
            upgradeCount = buf.readVarInt();
            hasSingularity = buf.readBoolean();
        }
        this.universal = universal;
        this.upgradeCount = upgradeCount;
        this.hasSingularity = hasSingularity;
        this.blankInv = new ItemStackHandler(1);
        this.encodedInv = new ItemStackHandler(1);
        buildSlots(playerInventory, null, null, upgradeCount, hasSingularity);
    }

    /** IForgeMenuType 用的工厂 */
    public static QianJiTerminalMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        return new QianJiTerminalMenu(id, inv, buf);
    }

    /** 线缆面板形态的打开数据：不是通用终端、没有升级卡槽、没有奇点槽 */
    public static void writeOpenDataForPlainForm(FriendlyByteBuf buffer) {
        buffer.writeBoolean(false);
        buffer.writeVarInt(0);
        buffer.writeBoolean(false);
    }

    /**
     * 自定义 opener（注册给 AE2 的 {@code MenuOpener}）。
     * <p>
     * 无线终端（自家终端 / 通用终端）右键时走的是 AE2/AE2WTLib 的链路：
     * {@code use()} → {@code MenuOpener.open(getMenuType(...), player, locator)} → 这里
     * → {@code locator.locate(player, QianJiTerminalHost.class)} 拿到宿主
     * （自家物品由我们的 {@code IMenuItem#getMenuHost} 给，通用终端由 AE2WTLib 的
     * {@code ItemWT#getMenuHost} 按登记好的工厂创建）→ 打开这张菜单。
     * <p>
     * **必须 {@code setLocator(locator)}**：通用终端那个「下一个终端」按钮就是靠它
     * 从当前菜单找回通用终端物品的。
     */
    public static boolean openTerminal(Player player, MenuLocator locator, boolean returnedFromSubScreen) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        QianJiTerminalHost host;
        try {
            host = locator.locate(player, QianJiTerminalHost.class);
        } catch (Throwable t) {
            AE2Addon.LOGGER.warn("[ae2addon][terminal] 定位终端宿主异常：{}", t.toString());
            return false;
        }
        if (host == null) {
            AE2Addon.LOGGER.warn("[ae2addon][terminal] locator {} 没给出终端宿主，界面不开", locator);
            return false;
        }
        final Component title = host.isUniversal()
                ? Component.translatable("gui.ae2addon.qianji_terminal.wireless_title")
                : Component.translatable("gui.ae2addon.qianji_terminal.title");
        final IUpgradeInventory upgrades = host.upgradeInv();
        final int upgradeCount = upgrades == null ? 0 : upgrades.size();
        final boolean hasSingularity = host.singularityInv() != null;
        NetworkHooks.openScreen(serverPlayer, new SimpleMenuProvider((containerId, inventory, ignored) -> {
            final var menu = new QianJiTerminalMenu(containerId, inventory, host);
            menu.setLocator(locator);
            return menu;
        }, title), buffer -> {
            buffer.writeBoolean(host.isUniversal());
            buffer.writeVarInt(upgradeCount);
            buffer.writeBoolean(hasSingularity);
        });
        return true;
    }

    /**
     * 建槽：顺序 = 两个样板槽 → 升级卡/奇点槽 → 玩家背包 → 快捷栏。
     * <p>
     * **服务端与客户端的数量、顺序必须一模一样**（差一个就整体串位），所以这里用同一段代码，
     * 只是客户端没有真库存、用占位库存接同步（{@code upgrades}/{@code singularity} 传 null，
     * 改用数量参数建占位槽）。
     */
    private void buildSlots(Inventory playerInventory,
            @Nullable InternalInventory upgrades, @Nullable InternalInventory singularity,
            int placeholderUpgrades, boolean placeholderSingularity) {
        // 空白样板槽（上）：只收 AE2 空白样板
        blankSlot = addSlot(new SlotItemHandler(blankInv, 0, 80, 40) {
            @Override
            public boolean mayPlace(@NotNull ItemStack stack) {
                return QianJiTerminalHandlers.isBlankPattern(stack);
            }
        });

        // 编码样板槽（下）：**只收千机样板**（其他样板一律拒绝 —— sensei 2026-09-21 要求）
        encodedSlot = addSlot(new SlotItemHandler(encodedInv, 0, 80, 78) {
            @Override
            public boolean mayPlace(@NotNull ItemStack stack) {
                return QianJiTerminalHandlers.isQianJiPattern(stack);
            }
        });

        // 升级卡槽 + 量子缠绕奇点槽（v274）
        //
        // 为什么不用 AE2 的 RestrictedInputSlot：它的槽位**只能在构造时给不进去**（唯一构造是
        // (type, inv, index)，而原版 Slot.x/y 是 final，建完再挪会编译不过），AE2 那边是靠自己的
        // 界面样式表定位的 —— 我们的界面是自绘的。所以这里用 Forge 的 SlotItemHandler + 自己写
        // mayPlace 过滤（过滤条件与 AE2 的 PlacableItemType 完全一致）：
        //   UPGRADES      → Upgrades.isUpgradeCardItem(stack)
        //   QE_SINGULARITY→ QuantumBridgeBlockEntity.isValidEntangledSingularity(stack)
        // 库存用 InternalInventory#toItemHandler() 桥过去。
        int x = EXTRA_X0;
        if (upgrades != null) {
            final IItemHandler upgradeHandler = upgrades.toItemHandler();
            for (int i = 0; i < upgrades.size(); i++) {
                addSlot(new SlotItemHandler(upgradeHandler, i, x, EXTRA_SLOT_Y) {
                    @Override
                    public boolean mayPlace(@NotNull ItemStack stack) {
                        return appeng.api.upgrades.Upgrades.isUpgradeCardItem(stack);
                    }
                });
                x += SLOT_STEP;
            }
        } else if (placeholderUpgrades > 0) {
            // 客户端占位（内容靠原版槽位同步）
            final var dummy = new ItemStackHandler(placeholderUpgrades);
            for (int i = 0; i < placeholderUpgrades; i++) {
                addSlot(new SlotItemHandler(dummy, i, x, EXTRA_SLOT_Y));
                x += SLOT_STEP;
            }
        }
        if (singularity != null) {
            addSlot(new SlotItemHandler(singularity.toItemHandler(), 0, x, EXTRA_SLOT_Y) {
                @Override
                public boolean mayPlace(@NotNull ItemStack stack) {
                    return appeng.blockentity.qnb.QuantumBridgeBlockEntity
                            .isValidEntangledSingularity(stack);
                }
            });
        } else if (placeholderSingularity) {
            addSlot(new SlotItemHandler(new ItemStackHandler(1), 0, x, EXTRA_SLOT_Y));
        }

        // 玩家背包 3×9（2026-09-21 v255：按 sensei 要求与列表换位 → 挪到界面最下方）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 200 + row * 18));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 254));
        }
    }

    /** AE2 的空白样板（用注册名判断，避免对这个物品类的硬依赖） */
    public static boolean isBlankPattern(ItemStack stack) {
        return QianJiTerminalHandlers.isBlankPattern(stack);
    }

    /** 这个界面是不是"通用终端上的一种状态"（客户端用它决定画不画切换按钮） */
    public boolean isUniversal() {
        return universal;
    }

    /** 有没有"升级卡 / 量子缠绕奇点"那一条（界面高度随之变） */
    public boolean hasExtras() {
        return upgradeCount > 0 || hasSingularity;
    }

    // ── QianJiEncodedSlotHolder ──

    @Override
    public List<Slot> blankSlots() {
        return blankSlot == null ? List.of() : List.of(blankSlot);
    }

    @Override
    public Slot encodedSlot() {
        return encodedSlot;
    }

    @Override
    public @Nullable IGrid grid() {
        return host == null ? null : host.getTerminalGrid();
    }

    /**
     * 网络里现有多少空白样板（**模拟**抽取，不消耗）。
     * <p>
     * 终端要显示这个"家底"：因为编码时可以直接从网络取用空白样板
     * （见 {@code QianJiPatternPacket#consumeFromNetwork}，是"访问并使用"不是"抽取"）。
     *
     * @return 数量；没接入网络/客户端/查询失败一律 0
     */
    public long networkBlankPatterns() {
        var grid = grid();
        if (grid == null) return 0;
        try {
            var storageService = grid.getStorageService();
            var storage = storageService == null ? null : storageService.getInventory();
            if (storage == null) return 0;
            var key = appeng.api.stacks.AEItemKey.of(
                    new ItemStack(appeng.core.definitions.AEItems.BLANK_PATTERN.asItem()));
            if (key == null) return 0;
            return storage.extract(key, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE,
                    appeng.api.networking.security.IActionSource.empty());
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 每 tick 的广播（服务端）。
     * <p>
     * 无线宿主的**范围检查与耗电**是 AE2 的 {@code AEBaseMenu} 在 {@code broadcastChanges} 里
     * 替我们调 {@code ItemMenuHost#onBroadcastChanges} 做的（返回 false 就把菜单标记为无效），
     * 部件宿主则是检查部件还在不在。这里只负责一件 AE2 不做的事：
     * **一旦菜单被判无效（没电 / 走出范围 / 部件被拆），真的把界面关掉**。
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!closedByHost && !isValidMenu() && getPlayer() instanceof ServerPlayer serverPlayer) {
            closedByHost = true;
            AE2Addon.LOGGER.info("[ae2addon][terminal] 终端宿主已失效（没电 / 超范围 / 部件被拆），关闭界面");
            serverPlayer.closeContainer();
        }
    }

    // ── 容器 ──

    /**
     * 空白样板槽的"从网络取出来"两个操作（2026-09-22 v282，v284 修 Shift+左键）。
     * <p>
     * 空白样板槽现在是"网络里空白样板"的映射：界面把网络存量画在槽位上、往里放 = 存进网络。
     * 取出：
     * <ul>
     *   <li>左键点**空的**槽 → 从网络取一叠（64）到手上；</li>
     *   <li>Shift+左键点**空的**槽 → 从网络取一叠**直接进背包**（背包满则掉地上）。</li>
     * </ul>
     * ⚠ v284 为什么把两件事都搬到 {@code clicked} 里：**原版对空槽根本不会走到
     * {@code quickMoveStack}**（QUICK_MOVE 分支前面有 {@code slot.mayPickup(player)} 之类的门槛）
     * —— 上次只写 {@code quickMoveStack} 里，所以 Shift+左键压根没被调用（sensei 实测没生效）。
     * {@code clicked} 对**所有点击类型**都会先到我们这里，最稳。
     */
    @Override
    public void clicked(int slotId, int button, net.minecraft.world.inventory.ClickType clickType,
            Player player) {
        final var type = net.minecraft.world.inventory.ClickType.PICKUP;
        if (slotId == SLOT_BLANK && !slots.get(SLOT_BLANK).hasItem()) {
            final int playerStart = 2 + upgradeCount + (hasSingularity ? 1 : 0);
            if (clickType == type && getCarried().isEmpty()) {
                final ItemStack taken = QianJiTerminalHandlers.withdrawBlankFromNetwork(grid(), 64);
                if (!taken.isEmpty()) {
                    setCarried(taken);
                    return;
                }
            } else if (clickType == net.minecraft.world.inventory.ClickType.QUICK_MOVE) {
                final ItemStack taken = QianJiTerminalHandlers.withdrawBlankFromNetwork(grid(), 64);
                if (!taken.isEmpty()) {
                    if (!this.moveItemStackTo(taken, playerStart, this.slots.size(), true)) {
                        player.drop(taken, false);   // 背包满了就掉地上，绝不吞
                    }
                    return;
                }
            }
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null) return ItemStack.EMPTY;

        // 前面是"终端自己的槽"（样板 + 升级卡 + 奇点），后面是玩家背包
        final int terminalSlots = 2 + upgradeCount + (hasSingularity ? 1 : 0);
        final int playerStart = terminalSlots;
        final int playerEnd = this.slots.size();

        // 注：空白样板槽的"Shift+左键从网络取一叠"由 clicked() 处理（v284）——
        // 原版对空槽不会走到 quickMoveStack，写在这里没用。

        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index < SLOT_ENCODED + 1) {
            // 样板槽 → 玩家背包
            if (!this.moveItemStackTo(stack, playerStart, playerEnd, true)) return ItemStack.EMPTY;
        } else if (index < terminalSlots) {
            // 升级卡/奇点槽 → 玩家背包
            if (!this.moveItemStackTo(stack, playerStart, playerEnd, true)) return ItemStack.EMPTY;
        } else if (QianJiTerminalHandlers.isBlankPattern(stack)) {
            // 背包 → 空白样板槽（放不进就原样退回，不影响其它槽）
            if (!this.moveItemStackTo(stack, SLOT_BLANK, SLOT_BLANK + 1, false)) return ItemStack.EMPTY;
        } else if (QianJiPatternData.of(stack) != null) {
            // 背包 → 编码样板槽（只认千机样板）
            if (!this.moveItemStackTo(stack, SLOT_ENCODED, SLOT_ENCODED + 1, false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        // AE2 的 AEBaseMenu 会把"部件被拆 / 无线没电 / 超范围"记成 invalid
        return isValidMenu();
    }

    /**
     * 关界面时把宿主槽位落盘（2026-09-22 v275）。
     * <p>
     * 为什么必须在这里做：升级卡槽 / 量子缠绕奇点槽的库存是 AE2WTLib/AE2 的**内存对象**
     * （宿主每次开界面都新建、从物品 NBT 读出来），不显式写回物品 NBT 就会随宿主一起丢 ——
     * sensei 实测"奇点放进槽里，重开终端就不见了"就是这个。
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (host != null && player instanceof ServerPlayer) {
            try {
                host.saveHost();
            } catch (Throwable t) {
                AE2Addon.LOGGER.warn("[ae2addon][terminal] 关闭时保存终端槽位失败：{}", t.toString());
            }
        }
    }
}
