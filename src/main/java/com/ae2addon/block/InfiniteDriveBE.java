package com.ae2addon.block;

import appeng.api.networking.IGridNode;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.util.AECableType;
import appeng.blockentity.storage.DriveBlockEntity;
import com.ae2addon.gui.InfiniteDriveMenu;
import com.ae2addon.init.ModBlockEntities;
import com.ae2addon.util.ChatLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * 驱动器（无限级）方块实体 —— **512 格元件槽**。
 * <p>
 * 设计路线（2026-09-15 第二次重写，修「元件插进去但不生效」）：
 * **不再手搓挂载链**，改为 {@code extends DriveBlockEntity} 只覆写 {@link #getCellCount()}。
 * <p>
 * 为什么这样就能 512 格：AE2 的 {@code DriveBlockEntity} 构造函数里是
 * {@code new AppEngCellInventory(this, getCellCount())} + {@code new DriveWatcher[getCellCount()]}
 * —— {@code getCellCount()} 是**虚调用**，子类覆写即可把整套「构造函数注册
 * IStorageProvider 服务 / 槽位筛选 / DriveWatcher / mountInventories / 待机功耗 /
 * 客户端可视化状态」的成熟机制原样继承下来。
 * <p>
 * 前一版（2026-09-14 手写 AENetworkInvBlockEntity + 自建库存）在实机上「元件插了不工作」，
 * 那条路要自己复刻 AE2 全部初始化细节，风险高——放弃。
 * <p>
 * 差异点：
 * <ul>
 *   <li>{@link #getCellCount()} = {@value #CELL_SLOTS}（其余全继承）</li>
 *   <li>formed 门控：未成型时对 AE 网络完全不可见</li>
 *   <li>GUI 换成本模组的 512 格分页面板（覆盖 {@code openMenu}，不打开 AE2 原版 10 格面板）</li>
 *   <li>{@link #writeToStream}/{@link #readFromStream} 覆写为空：不做 LED 状态同步
 *       （512 格全量同步没必要，且包会很大）</li>
 * </ul>
 */
public class InfiniteDriveBE extends DriveBlockEntity implements Formable, MenuProvider {

    /** 元件槽数量（512 格 = 54 格/页 × 10 页，见 InfiniteDriveMenu） */
    public static final int CELL_SLOTS = 512;

    private boolean formed = false;
    /** 多方块朝向（成型时方向检测得出；见 {@link #getFacing()}） */
    private Direction facing = Direction.NORTH;

    public InfiniteDriveBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INFINITE_DRIVE.get(), pos, state);
    }

    /** 512 格：父类构造函数里就用这个值建库存与 watcher 数组（虚调用，必须在这里覆写） */
    @Override
    public int getCellCount() {
        return CELL_SLOTS;
    }

    // ── formed 门控（保留：未成型对网络完全不可见）──

    @Override
    public boolean isFormed() { return formed; }

    @Override
    public void setFormed(boolean formed) {
        if (this.formed == formed) return;
        this.formed = formed;
        if (level != null && !level.isClientSide) {
            updateSideExposure();
            IStorageProvider.requestUpdate(getMainNode());
            ChatLog.info(level, worldPosition, formed
                    ? "驱动器已成型，接入 AE 网络（" + CELL_SLOTS + " 格）"
                    : "驱动器解除成型，断开 AE 网络");
        }
        setChanged();
    }

    private void updateSideExposure() {
        getMainNode().setExposedOnSides(
                formed ? EnumSet.allOf(Direction.class) : EnumSet.noneOf(Direction.class));
    }

    /** 多方块朝向（成型时方向检测得出；创造变体按放置时玩家的水平朝向） */
    public Direction getFacing() { return facing; }

    public void setFacing(@Nullable Direction facing) {
        if (facing == null || !facing.getAxis().isHorizontal()) return;
        if (this.facing == facing) return;
        this.facing = facing;
        setChanged();
    }

    @Override
    public void applyCreativeFormed(@Nullable Player player) {
        if (player != null) setFacing(player.getDirection().getOpposite());
        setFormed(true);
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

    @Override
    public void onReady() {
        super.onReady();
        updateSideExposure();
    }

    /**
     * 挂载时打一行诊断日志（元件到底有没有挂进网络一眼可见）。
     * 2026-09-15 第一版自建挂载链在实机上“元件插了不工作”排查了一轮，
     * 这里留一行日志，以后再出问题不用靠猜。
     */
    @Override
    public void mountInventories(IStorageMounts mounts) {
        super.mountInventories(mounts);
        if (level != null && !level.isClientSide) {
            int installed = 0;
            var inv = getInternalInventory();
            for (int i = 0; i < inv.size(); i++) {
                if (!inv.getStackInSlot(i).isEmpty()) installed++;
            }
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon] 驱动器挂载 pos={} formed={} online={} 已装元件={}",
                    worldPosition, formed, getMainNode().isOnline(), installed);
        }
    }

    // ── 不做 LED 状态同步（512 格全量同步没必要；避免大包与无谓开销）──

    @Override
    protected void writeToStream(FriendlyByteBuf data) {
        // 覆写为空：GUI 由我们的菜单槽位同步，不需要驱动器那套逐格 LED 状态
    }

    @Override
    protected boolean readFromStream(FriendlyByteBuf data) {
        return false;
    }

    // ── NBT（父类已存库存与优先级，这里只补 formed）──

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("formed", formed);
        tag.putString("facing", facing.getName());
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        formed = tag.getBoolean("formed");
        facing = Direction.byName(tag.getString("facing"));
        if (facing == null || !facing.getAxis().isHorizontal()) facing = Direction.NORTH;
        if (level != null && !level.isClientSide) {
            updateSideExposure();
        }
    }

    // ── GUI：本模组的 512 格分页面板 ──

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

    /** 右键打开 512 格元件面板（覆盖父类：避免打开 AE2 原版 10 格 DriveMenu） */
    @Override
    public void openMenu(Player player) {
        if (player instanceof ServerPlayer serverPlayer && formed) {
            NetworkHooks.openScreen(serverPlayer, this, worldPosition);
        }
    }
}
