package com.ae2addon.network;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEFluidKey;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.item.UniversalStorageCell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 模式2配置数据包：
 * type 0 = 设置阈值
 * type 1 = 加入白名单（传完整ItemStack，自动检测流体容器）
 * type 2 = 移除白名单项
 * type 3 = 请求面板数据（客户端→服务端）
 * type 4 = 面板数据响应（服务端→客户端，支持分包）
 * type 5 = 切换无限状态（客户端→服务端，传完整AEKey NBT）
 */
public class Mode2ConfigPacket {

    private final int type;
    private final long threshold;
    private final String itemId;           // type 2 用
    private final ItemStack clickedStack;  // type 1 用
    private final List<UnlimitedCellInventory.PanelItem> panelItems; // type 4
    private final CompoundTag keyTag;      // type 5 用
    private final int workMode;            // type 6 用
    /** 分包支持：当前 chunk 索引 / 总 chunk 数，非分包时 (0, 1) */
    private final int chunkIndex;
    private final int totalChunks;

    private static final int MAX_ITEMS_PER_CHUNK = 50;

    // type 3 请求
    public Mode2ConfigPacket(int type) {
        this.type = type;
        this.threshold = 0;
        this.itemId = "";
        this.clickedStack = ItemStack.EMPTY;
        this.panelItems = null;
        this.keyTag = null;
        this.workMode = 0;
        this.chunkIndex = 0;
        this.totalChunks = 1;
    }

    // type 0 / 2
    public Mode2ConfigPacket(int type, long threshold, String itemId) {
        this.type = type;
        this.threshold = threshold;
        this.itemId = itemId == null ? "" : itemId;
        this.clickedStack = ItemStack.EMPTY;
        this.panelItems = null;
        this.keyTag = null;
        this.workMode = 0;
        this.chunkIndex = 0;
        this.totalChunks = 1;
    }

    // type 6 设置工作模式
    public Mode2ConfigPacket(int type, int workMode) {
        this.type = 6;
        this.threshold = 0;
        this.itemId = "";
        this.clickedStack = ItemStack.EMPTY;
        this.panelItems = null;
        this.keyTag = null;
        this.workMode = workMode;
        this.chunkIndex = 0;
        this.totalChunks = 1;
    }

    // type 1 加入白名单（传完整ItemStack）
    public Mode2ConfigPacket(ItemStack clickedStack) {
        this.type = 1;
        this.threshold = 0;
        this.itemId = "";
        this.clickedStack = clickedStack;
        this.panelItems = null;
        this.keyTag = null;
        this.workMode = 0;
        this.chunkIndex = 0;
        this.totalChunks = 1;
    }

    /** type 4 面板数据响应（分包） */
    public Mode2ConfigPacket(List<UnlimitedCellInventory.PanelItem> panelItems, int chunkIndex, int totalChunks) {
        this.type = 4;
        this.threshold = 0;
        this.itemId = "";
        this.clickedStack = ItemStack.EMPTY;
        this.panelItems = panelItems;
        this.keyTag = null;
        this.workMode = 0;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
    }

    // type 5 切换无限
    public Mode2ConfigPacket(CompoundTag keyTag) {
        this.type = 5;
        this.threshold = 0;
        this.itemId = "";
        this.clickedStack = ItemStack.EMPTY;
        this.panelItems = null;
        this.keyTag = keyTag;
        this.workMode = 0;
        this.chunkIndex = 0;
        this.totalChunks = 1;
    }

    public static void encode(Mode2ConfigPacket p, FriendlyByteBuf buf) {
        buf.writeByte(p.type);

        if (p.type == 1) {
            buf.writeItem(p.clickedStack);
        } else if (p.type == 4 && p.panelItems != null) {
            buf.writeVarInt(p.chunkIndex);
            buf.writeVarInt(p.totalChunks);
            buf.writeVarInt(p.panelItems.size());
            for (var entry : p.panelItems) {
                buf.writeNbt(entry.key.toTagGeneric());
                buf.writeVarLong(entry.amount);
                buf.writeBoolean(entry.isInfinite);
                buf.writeVarLong(entry.bytes);
            }
        } else if (p.type == 5 && p.keyTag != null) {
            buf.writeNbt(p.keyTag);
        } else if (p.type == 6) {
            buf.writeVarInt(p.workMode);
        } else {
            buf.writeLong(p.threshold);
            buf.writeUtf(p.itemId == null ? "" : p.itemId);
        }
    }

    public static Mode2ConfigPacket decode(FriendlyByteBuf buf) {
        int type = buf.readByte();

        if (type == 1) {
            return new Mode2ConfigPacket(buf.readItem());
        }

        if (type == 4) {
            int chunkIndex = buf.readVarInt();
            int totalChunks = buf.readVarInt();
            int count = buf.readVarInt();
            List<UnlimitedCellInventory.PanelItem> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                CompoundTag tag = buf.readNbt();
                if (tag == null) continue;
                AEKey key = AEKey.fromTagGeneric(tag);
                if (key == null) continue;
                long amount = buf.readVarLong();
                boolean isInfinite = buf.readBoolean();
                long bytes = buf.readVarLong();
                items.add(new UnlimitedCellInventory.PanelItem(key, amount, isInfinite, bytes));
            }
            return new Mode2ConfigPacket(items, chunkIndex, totalChunks);
        }

        if (type == 5) {
            CompoundTag tag = buf.readNbt();
            return new Mode2ConfigPacket(tag);
        }

        if (type == 6) {
            return new Mode2ConfigPacket(6, buf.readVarInt());
        }

        return new Mode2ConfigPacket(type, buf.readLong(), buf.readUtf());
    }

    public static void handle(Mode2ConfigPacket p, Supplier<NetworkEvent.Context> ctx) {
        var side = ctx.get().getDirection().getReceptionSide();

        if (side.isClient() && p.type == 4) {
            ctx.get().enqueueWork(() -> {
                com.ae2addon.gui.Mode2ConfigScreen.handlePanelDataChunk(
                        p.panelItems, p.chunkIndex, p.totalChunks);
            });
            ctx.get().setPacketHandled(true);
            return;
        }

        if (!side.isServer()) {
            ctx.get().setPacketHandled(true);
            return;
        }

        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof UniversalStorageCell)) {
                stack = player.getOffhandItem();
                if (!(stack.getItem() instanceof UniversalStorageCell)) return;
            }

            // 通过 UnlimitedCellInventory 操作数据（不再直接读写 NBT）
            UnlimitedCellInventory inv = new UnlimitedCellInventory(stack, null);
            int mode = inv.getMode();

            if (p.type == 0) {
                // 设置阈值
                inv.setThreshold(p.threshold);

            } else if (p.type == 1 && !p.clickedStack.isEmpty()) {
                // 加入白名单（自动检测流体容器）
                handleAddToWhitelist(inv, p.clickedStack);

            } else if (p.type == 2 && !p.itemId.isEmpty()) {
                // 移除白名单项
                handleRemoveFromWhitelist(inv, p.itemId);

            } else if (p.type == 3) {
                sendPanelRefresh(inv, player);
                return;

            } else if (p.type == 5 && p.keyTag != null) {
                handleToggleInfinite(inv, p.keyTag, player);
                return;
            } else if (p.type == 6) {
                inv.setWorkMode(p.workMode);
                sendPanelRefresh(inv, player);
                return;
            }

            // type 0/1/2 都重刷面板
            if (p.type == 0 || p.type == 1 || p.type == 2) {
                sendPanelRefresh(inv, player);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /** 分包发送面板刷新数据：每包最多 MAX_ITEMS_PER_CHUNK 个 item */
    private static void sendPanelRefresh(UnlimitedCellInventory inv, ServerPlayer player) {
        List<UnlimitedCellInventory.PanelItem> allItems = inv.getPanelItems();
        int total = allItems.size();
        int totalChunks = (total + MAX_ITEMS_PER_CHUNK - 1) / MAX_ITEMS_PER_CHUNK;
        if (totalChunks == 0) totalChunks = 1;

        for (int i = 0; i < totalChunks; i++) {
            int from = i * MAX_ITEMS_PER_CHUNK;
            int to = Math.min(from + MAX_ITEMS_PER_CHUNK, total);
            List<UnlimitedCellInventory.PanelItem> chunk = allItems.subList(from, to);
            com.ae2addon.AE2Addon.NETWORK.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new Mode2ConfigPacket(chunk, i, totalChunks)
            );
        }
    }

    // ── 白名单操作（通过 UnlimitedCellInventory） ──

    /** 将物品加入白名单（自动检测流体容器） */
    private static void handleAddToWhitelist(UnlimitedCellInventory inv, ItemStack itemStack) {
        // 1. 检测是否是流体容器
        FluidStack fluid = getFluidFromContainer(itemStack);
        if (!fluid.isEmpty() && fluid.getFluid() != Fluids.EMPTY) {
            AEFluidKey fluidKey = AEFluidKey.of(fluid.getFluid());
            if (fluidKey != null && !inv.getWl().contains(fluidKey)) {
                inv.addWl(fluidKey);
            }
            return;
        }

        // 2. 不是流体容器 → 加入物品本身（带 NBT）
        if (itemStack.isEmpty()) return;
        AEItemKey itemKey = AEItemKey.of(itemStack);
        if (itemKey == null) return;

        if (!inv.getWl().contains(itemKey)) {
            inv.addWl(itemKey);
        }
    }

    /** 检测物品是否有流体容器能力 */
    private static FluidStack getFluidFromContainer(ItemStack stack) {
        Optional<IFluidHandlerItem> handler = stack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).resolve();
        if (handler.isPresent()) {
            return handler.get().getFluidInTank(0);
        }
        return FluidStack.EMPTY;
    }

    /** 移除白名单项 */
    private static void handleRemoveFromWhitelist(UnlimitedCellInventory inv, String itemId) {
        // 遍历白名单，按 id 匹配移除
        for (AEKey key : inv.getWl()) {
            CompoundTag tag = key.toTagGeneric();
            if (itemId.equals(tag.getString("id"))) {
                inv.removeWl(key);
                return;
            }
        }
    }

    // ── 无限状态切换 + 输出 ──

    private static void handleToggleInfinite(UnlimitedCellInventory inv,
                                              CompoundTag keyTag, ServerPlayer player) {
        if (keyTag == null) return;

        AEKey key = AEKey.fromTagGeneric(keyTag);
        if (key == null) return;

        boolean wasInfinite = inv.getUl().contains(key) || inv.getWl().contains(key);
        boolean hasCa = inv.hasCommitedAmount(key);
        long committed = hasCa ? inv.getCommitedAmount(key) : 0;

        inv.togglePanelInfinite(key);

        if (wasInfinite && committed > 0 && key instanceof AEItemKey) {
            outputItems(player, key, committed);
        }

        sendPanelRefresh(inv, player);
    }

    /** 输出物品：优先背包，溢出则掉落 */
    private static void outputItems(ServerPlayer player, AEKey key, long amount) {
        if (!(key instanceof AEItemKey itemKey)) return;

        int maxStackSize = itemKey.getItem().getMaxStackSize();
        long remaining = amount;

        while (remaining > 0) {
            int count = (int) Math.min(remaining, maxStackSize);
            ItemStack outStack = itemKey.toStack(count);
            remaining -= count;

            if (!player.addItem(outStack)) {
                if (!outStack.isEmpty()) {
                    ItemEntity entity = new ItemEntity(
                            player.level(),
                            player.getX(), player.getY() + 0.5, player.getZ(),
                            outStack
                    );
                    entity.setPickUpDelay(10);
                    player.level().addFreshEntity(entity);
                }
            }
        }
    }
}
