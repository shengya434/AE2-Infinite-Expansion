package com.ae2addon.data;

import appeng.api.stacks.AEKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 巨型订单持久化（WorldSavedData）：退出重进存档后自动恢复订单队列。
 * <p>
 * 保存每个订单的完整状态（物品/总量/批次/进度/网格锚点），
 * 世界重进后由 BatchedCraftingQueue 懒加载恢复，从断点继续跑。
 */
public class MegaOrderSavedData extends SavedData {

    private static final String DATA_NAME = "ae2addon_mega_orders";

    private final List<OrderSnapshot> orders = new ArrayList<>();

    public static MegaOrderSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                MegaOrderSavedData::load, MegaOrderSavedData::new, DATA_NAME);
    }

    public void setOrders(List<OrderSnapshot> snapshots) {
        orders.clear();
        orders.addAll(snapshots);
        setDirty();
    }

    public List<OrderSnapshot> getOrders() {
        return orders;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
        ListTag list = new ListTag();
        for (OrderSnapshot order : orders) {
            list.add(order.save());
        }
        tag.put("orders", list);
        return tag;
    }

    public static MegaOrderSavedData load(CompoundTag tag) {
        MegaOrderSavedData data = new MegaOrderSavedData();
        if (tag.contains("orders", Tag.TAG_LIST)) {
            for (Tag t : tag.getList("orders", Tag.TAG_COMPOUND)) {
                OrderSnapshot snap = OrderSnapshot.load((CompoundTag) t);
                if (snap != null) {
                    data.orders.add(snap);
                }
            }
        }
        return data;
    }

    /** 单个订单的存档快照 */
    public static final class OrderSnapshot {
        public final AEKey what;
        public final long totalAmount;
        public final long perBatch;
        public final int completedCount;
        public final int nextBatchIndex;
        public final BlockPos anchor;
        public final ResourceKey<Level> dimension;

        public OrderSnapshot(AEKey what, long totalAmount, long perBatch,
                             int completedCount, int nextBatchIndex,
                             BlockPos anchor, ResourceKey<Level> dimension) {
            this.what = what;
            this.totalAmount = totalAmount;
            this.perBatch = perBatch;
            this.completedCount = completedCount;
            this.nextBatchIndex = nextBatchIndex;
            this.anchor = anchor;
            this.dimension = dimension;
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            if (what != null) {
                tag.put("what", what.toTagGeneric());
            }
            tag.putLong("total", totalAmount);
            tag.putLong("perBatch", perBatch);
            tag.putInt("done", completedCount);
            tag.putInt("next", nextBatchIndex);
            if (anchor != null) {
                tag.putInt("ax", anchor.getX());
                tag.putInt("ay", anchor.getY());
                tag.putInt("az", anchor.getZ());
            }
            tag.putString("dim", dimension.location().toString());
            return tag;
        }

        public static OrderSnapshot load(CompoundTag tag) {
            try {
                AEKey what = AEKey.fromTagGeneric(tag.getCompound("what"));
                if (what == null) {
                    return null;
                }
                long total = tag.getLong("total");
                long perBatch = tag.getLong("perBatch");
                if (total <= 0 || perBatch <= 0) {
                    return null;
                }
                int done = tag.getInt("done");
                int next = tag.getInt("next");
                BlockPos anchor = tag.contains("ax")
                        ? new BlockPos(tag.getInt("ax"), tag.getInt("ay"), tag.getInt("az"))
                        : null;
                ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                        new ResourceLocation(tag.getString("dim")));
                return new OrderSnapshot(what, total, perBatch, done, next, anchor, dim);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
