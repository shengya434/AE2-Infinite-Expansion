package com.ae2addon.item;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.ae2addon.init.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 物质球：取消无限状态时，把大量物品打包成单个球体临时存放。
 * <p>
 * NBT：key = AEKey 完整 NBT，amount = 打包数量（long）。
 * 右键展开：优先放入背包，放不下的剩余部分保留在球内；球内数量为 0 时消耗物品。
 * 永不产生海量掉落物——背包满时只掉落 1 个球实体。
 */
public class MatterBallItem extends Item {

    public static final String NBT_KEY = "ae2addon_ball_key";
    public static final String NBT_AMOUNT = "ae2addon_ball_amount";

    public MatterBallItem() {
        super(new Item.Properties().stacksTo(64).rarity(Rarity.EPIC));
    }

    /** 创建物质球：记录物品 key + 数量 */
    public static ItemStack makeBall(AEItemKey itemKey, long amount) {
        ItemStack ball = new ItemStack(ModItems.MATTER_BALL.get());
        CompoundTag tag = ball.getOrCreateTag();
        tag.put(NBT_KEY, itemKey.toTagGeneric());
        tag.putLong(NBT_AMOUNT, amount);
        return ball;
    }

    /** 读取球内物品 key（无则 null） */
    @Nullable
    public static AEItemKey getKey(ItemStack ball) {
        if (ball.isEmpty() || !ball.hasTag()) return null;
        CompoundTag tag = ball.getTag();
        if (tag == null || !tag.contains(NBT_KEY)) return null;
        AEKey key = AEKey.fromTagGeneric(tag.getCompound(NBT_KEY));
        return key instanceof AEItemKey itemKey ? itemKey : null;
    }

    /** 读取球内数量（无则 0） */
    public static long getAmount(ItemStack ball) {
        if (ball.isEmpty() || !ball.hasTag()) return 0;
        CompoundTag tag = ball.getTag();
        if (tag == null || !tag.contains(NBT_AMOUNT)) return 0;
        return tag.getLong(NBT_AMOUNT);
    }

    // ── 右键展开 ──

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack ball = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(ball);
        }

        AEItemKey key = getKey(ball);
        long amount = getAmount(ball);
        if (key == null || amount <= 0) {
            // 空球/损坏球：消耗掉并提示
            player.sendSystemMessage(Component.translatable("gui.ae2addon.matter_ball.empty"));
            ball.shrink(1);
            return InteractionResultHolder.success(ball);
        }

        // 尽可能放入背包，剩余保留在球内
        long remaining = amount;
        int maxStackSize = key.getItem().getMaxStackSize();
        while (remaining > 0) {
            int count = (int) Math.min(remaining, maxStackSize);
            ItemStack out = key.toStack(count);
            if (!player.addItem(out)) {
                break; // 背包满了，剩余留在球内
            }
            remaining -= count;
        }

        if (remaining <= 0) {
            // 全部取出 → 消耗球
            ball.shrink(1);
            player.sendSystemMessage(Component.translatable(
                    "gui.ae2addon.matter_ball.unpacked", amount, key.getDisplayName()));
        } else {
            // 部分取出 → 更新球内数量
            ball.getOrCreateTag().putLong(NBT_AMOUNT, remaining);
            player.sendSystemMessage(Component.translatable(
                    "gui.ae2addon.matter_ball.partial", amount - remaining, key.getDisplayName(), remaining));
        }
        return InteractionResultHolder.success(ball);
    }

    // ── Tooltip ──

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        AEItemKey key = getKey(stack);
        long amount = getAmount(stack);
        if (key != null && amount > 0) {
            tooltip.add(Component.translatable("gui.ae2addon.matter_ball.tooltip",
                    key.getDisplayName(), amount));
        } else {
            tooltip.add(Component.translatable("gui.ae2addon.matter_ball.tooltip_empty"));
        }
        tooltip.add(Component.translatable("gui.ae2addon.matter_ball.hint"));
    }
}
