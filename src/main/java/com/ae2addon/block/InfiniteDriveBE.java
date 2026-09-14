package com.ae2addon.block;

import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.blockentity.storage.DriveBlockEntity;
import com.ae2addon.init.ModBlockEntities;
import com.ae2addon.util.ChatLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * 无限驱动器方块实体。
 * 复用 DriveBlockEntity 的 10 格细胞系统 + AE2 原版驱动器面板。
 */
public class InfiniteDriveBE extends DriveBlockEntity implements MenuProvider {

    private boolean formed = false;

    public InfiniteDriveBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INFINITE_DRIVE.get(), pos, state);
    }

    public boolean isFormed() { return formed; }

    public void setFormed(boolean formed) {
        if (this.formed == formed) return;
        this.formed = formed;
        if (level != null && !level.isClientSide) {
            updateSideExposure();
            ChatLog.info(level, worldPosition, formed ? "驱动器已成型，接入 AE 网络" : "驱动器解除成型，断开 AE 网络");
        }
        setChanged();
    }

    @Override
    public void onReady() {
        super.onReady();
        updateSideExposure();
    }

    /** 未成型时对 AE 网络完全不可见（双保险：节点不暴露 + getGridNode 返回 null） */
    private void updateSideExposure() {
        getMainNode().setExposedOnSides(
                formed ? EnumSet.allOf(Direction.class) : EnumSet.noneOf(Direction.class));
    }

    // ── AE 网格控制 ──

    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        if (!formed) return null;
        return super.getGridNode(dir);
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return formed ? super.getCableConnectionType(dir) : AECableType.NONE;
    }

    // ── NBT ──

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("formed", formed);
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        formed = tag.getBoolean("formed");
        if (level != null && !level.isClientSide) {
            updateSideExposure();
        }
    }

    // ── MenuProvider（通过 AE2 原版驱动器面板访问） ──

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.ae2addon.infinite_drive");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        // 实际面板由 DriveBlockEntity.openMenu(Player) 打开（AE2 原版 DriveMenu）
        return null;
    }

    // DriveBlockEntity 已提供 openMenu(Player)，无需额外实现
}
