package com.ae2addon.block;

import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import appeng.api.util.AECableType;
import appeng.blockentity.grid.AENetworkInvBlockEntity;
import appeng.blockentity.inventory.AppEngCellInventory;
import appeng.helpers.IPriorityHost;
import appeng.me.storage.DriveWatcher;
import appeng.menu.ISubMenu;
import appeng.util.inv.filter.IAEItemFilter;
import com.ae2addon.gui.InfiniteDriveMenu;
import com.ae2addon.init.ModBlockEntities;
import com.ae2addon.init.ModBlocks;
import com.ae2addon.util.ChatLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * 驱动器（无限级）方块实体 —— **512 格元件槽**（2026-09-14 重写）。
 * <p>
 * 历史：7/21 那版只有一个空壳 GUI（连用都用不了），8/11 的备份里退化成了
 * {@code extends DriveBlockEntity}（AE2 原版 10 格，槽数写在它私有 inventory 里，改不动）。
 * 重写路线：直接继承 {@link AENetworkInvBlockEntity}（与 AE2 驱动器同层），自建
 * {@link AppEngCellInventory}(512) + {@link DriveWatcher} 数组，自己 mount 进网格。
 * <p>
 * 与 AE2 原版驱动器的差异：
 * <ul>
 *   <li>槽位 512 个（54 格/页 × 10 页，见 {@link InfiniteDriveMenu}）</li>
 *   <li>未成型时对 AE 网络完全不可见（继承先前的 formed 门控）</li>
 *   <li>只收「AE2 认得的元件」——原件照旧可用（Qb 2026-09-14）；我们的无限元件
 *       在别的驱动器里插不进去（见 InfiniteCellSlotGuardMixin）</li>
 * </ul>
 */
public class InfiniteDriveBE extends AENetworkInvBlockEntity
        implements IStorageProvider, IChestOrDrive, IPriorityHost, MenuProvider, Formable {

    /** 元件槽数量（512 格） */
    public static final int CELL_SLOTS = 512;

    /** 只接受「AE2 认得的元件」——与 AE2 自家驱动器的 CellValidInventoryFilter 同规则 */
    private static final IAEItemFilter CELL_FILTER = new IAEItemFilter() {
        @Override
        public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
            return !stack.isEmpty() && StorageCells.isCellHandled(stack);
        }
    };

    private boolean formed = false;
    private int priority = 0;
    /** 槽位状态缓存是否有效（无效时下次 mount 重建 watcher） */
    private boolean isCached = false;

    private final AppEngCellInventory inv = new AppEngCellInventory(this, CELL_SLOTS);
    private final DriveWatcher[] watchers = new DriveWatcher[CELL_SLOTS];

    public InfiniteDriveBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INFINITE_DRIVE.get(), pos, state);
        // ⚠ 关键：AE2 的驱动器是在**构造函数里**把「存储提供者」注册到节点上的
        // （DriveBlockEntity 同款：addService(IStorageProvider) + REQUIRE_CHANNEL）。
        // 漏了这一步，网格永远不会调 mountInventories → 元件插进去也不工作
        // （2026-09-14 sensei 实测：插入后无法正常使用）。
        getMainNode()
                .addService(IStorageProvider.class, this)
                .setFlags(GridFlags.REQUIRE_CHANNEL);
    }

    // ── 生命周期 ──

    @Override
    public void onReady() {
        super.onReady();
        this.inv.setFilter(CELL_FILTER);
        updateSideExposure();
    }

    public boolean isFormed() { return formed; }

    public void setFormed(boolean formed) {
        if (this.formed == formed) return;
        this.formed = formed;
        if (level != null && !level.isClientSide) {
            updateSideExposure();
            // 成型状态变化会改变网络可见性 → 让网格重建挂载
            IStorageProvider.requestUpdate(getMainNode());
            ChatLog.info(level, worldPosition, formed
                    ? "驱动器已成型，接入 AE 网络（" + CELL_SLOTS + " 格）"
                    : "驱动器解除成型，断开 AE 网络");
        }
        setChanged();
    }

    /** 未成型时对 AE 网络完全不可见（节点不暴露 + getGridNode 返回 null 双保险） */
    private void updateSideExposure() {
        getMainNode().setExposedOnSides(
                formed ? EnumSet.allOf(Direction.class) : EnumSet.noneOf(Direction.class));
    }

    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        return formed ? super.getGridNode(dir) : null;
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return formed ? super.getCableConnectionType(dir) : AECableType.NONE;
    }

    // ── 库存 ──

    @Override
    public InternalInventory getInternalInventory() {
        return this.inv;
    }

    /** 公开给 GUI / 调试用的元件库存 */
    public AppEngCellInventory getCellInventory() {
        return this.inv;
    }

    @Override
    public void onChangeInventory(InternalInventory changed, int slot) {
        if (changed == this.inv) {
            this.isCached = false;
            updateState();
            IStorageProvider.requestUpdate(getMainNode());
            markForUpdate();
        }
    }

    /** 重建槽位状态缓存（含待机功耗累加） */
    private void updateState() {
        if (this.isCached) return;
        double idlePower = 0;
        for (int i = 0; i < this.inv.size(); i++) {
            idlePower += updateStateForSlot(i);
        }
        getMainNode().setIdlePowerUsage(idlePower);
        this.isCached = true;
    }

    /** 单个槽的元件 → watcher；返回该元件的待机功耗 */
    private double updateStateForSlot(int slot) {
        ItemStack stack = this.inv.getStackInSlot(slot);
        StorageCell cell = stack.isEmpty() ? null : StorageCells.getCellInventory(stack, this::saveChanges);
        this.inv.setHandler(slot, cell);

        if (cell == null) {
            this.watchers[slot] = null;
            return 0;
        }
        this.watchers[slot] = new DriveWatcher(cell, () -> updateStateForSlot(slot));
        return cell.getIdleDrain();
    }

    @Override
    public void mountInventories(IStorageMounts mounts) {
        if (!getMainNode().isOnline()) return;
        updateState();
        for (DriveWatcher watcher : this.watchers) {
            if (watcher != null) mounts.mount(watcher, this.priority);
        }
    }

    // ── IChestOrDrive ──

    @Override
    public int getCellCount() { return CELL_SLOTS; }

    @Override
    public Item getCellItem(int slot) {
        if (slot < 0 || slot >= CELL_SLOTS) return net.minecraft.world.item.Items.AIR;
        return this.inv.getStackInSlot(slot).getItem();
    }

    @Override
    public CellState getCellStatus(int slot) {
        if (slot < 0 || slot >= CELL_SLOTS) return CellState.ABSENT;
        // 客户端不同步槽位状态（我们不做面板上的 LED 动画）→ 一律 ABSENT，避免客户端读到 null
        if (isClientSide()) return CellState.ABSENT;
        DriveWatcher watcher = this.watchers[slot];
        return watcher == null ? CellState.ABSENT : watcher.getStatus();
    }

    @Override
    public boolean isPowered() { return getMainNode().isActive(); }

    @Override
    public boolean isCellBlinking(int slot) { return false; }

    @Nullable
    @Override
    public MEStorage getCellInventory(int slot) {
        if (slot < 0 || slot >= CELL_SLOTS) return null;
        return this.watchers[slot];
    }

    @Nullable
    @Override
    public StorageCell getOriginalCellInventory(int slot) {
        if (slot < 0 || slot >= CELL_SLOTS) return null;
        DriveWatcher watcher = this.watchers[slot];
        return watcher == null ? null : watcher.getCell();
    }

    // ── IPriorityHost（存储优先级） ──

    @Override
    public int getPriority() { return this.priority; }

    @Override
    public void setPriority(int priority) {
        this.priority = priority;
        saveChanges();
        IStorageProvider.requestUpdate(getMainNode());
    }

    @Override
    public ItemStack getMainMenuIcon() { return new ItemStack(ModBlocks.INFINITE_DRIVE.get()); }

    @Override
    public void returnToMainMenu(Player player, ISubMenu submenu) { openMenu(player); }

    // ── NBT ──

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("formed", formed);
        tag.putInt("priority", this.priority);
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        formed = tag.getBoolean("formed");
        this.priority = tag.getInt("priority");
        this.isCached = false;
        if (level != null && !level.isClientSide) {
            updateSideExposure();
        }
    }

    // ── GUI ──

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.ae2addon.infinite_drive");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        if (!formed) return null;
        return new InfiniteDriveMenu(containerId, playerInv, worldPosition);
    }

    /** 右键打开 512 格元件面板 */
    public void openMenu(Player player) {
        if (player instanceof ServerPlayer serverPlayer && formed) {
            NetworkHooks.openScreen(serverPlayer, this, worldPosition);
        }
    }
}
