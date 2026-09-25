package com.ae2addon.item;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.ae2addon.init.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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

import java.math.BigInteger;
import java.util.List;

/**
 * 物质球：取消无限状态时，把大量物品打包成单个球体临时存放。
 * <p>
 * NBT：key = AEKey 完整 NBT，amount = 打包数量（**BigInteger，byte[] 存储**）。
 * 右键展开：优先放入背包，放不下的剩余部分保留在球内；球内数量为 0 时消耗物品。
 * 永不产生海量掉落物——背包满时只掉落 1 个球实体。
 * <p>
 * ⚠ 2026-09-19（sensei：「Mode 2 取消无限排出数量也是 9.2E，即使真实存储量大得多」）：
 * 数量从 long 改成 **BigInteger**。万能元件的 Mode 2 存储本来就是 BigInteger，
 * 但"额度/打包/显示"这条链一路用 long，第一刀 `clampToLong` 就把真实数量截成
 * Long.MAX（9.22e18），后面显示和物质球只是照抄那个假数字。
 * 现在 byte[] 存真值，并**兼容读取旧版 long 存档**。
 */
public class MatterBallItem extends Item {

    public static final String NBT_KEY = "ae2addon_ball_key";
    public static final String NBT_AMOUNT = "ae2addon_ball_amount";

    public MatterBallItem() {
        super(new Item.Properties().stacksTo(64).rarity(Rarity.EPIC));
    }

    /** 创建物质球：记录物品 key + 数量（BigInteger，永不截断） */
    public static ItemStack makeBall(AEItemKey itemKey, BigInteger amount) {
        ItemStack ball = new ItemStack(ModItems.MATTER_BALL.get());
        CompoundTag tag = ball.getOrCreateTag();
        tag.put(NBT_KEY, itemKey.toTagGeneric());
        tag.putByteArray(NBT_AMOUNT, amount.max(BigInteger.ZERO).toByteArray());
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

    /** 读取球内数量（无则 0）。兼容旧版 long 存档。 */
    public static BigInteger getAmount(ItemStack ball) {
        if (ball.isEmpty() || !ball.hasTag()) return BigInteger.ZERO;
        CompoundTag tag = ball.getTag();
        if (tag == null || !tag.contains(NBT_AMOUNT)) return BigInteger.ZERO;
        if (tag.contains(NBT_AMOUNT, Tag.TAG_BYTE_ARRAY)) {
            return new BigInteger(tag.getByteArray(NBT_AMOUNT));
        }
        // 旧格式：long
        return BigInteger.valueOf(tag.getLong(NBT_AMOUNT));
    }

    // ── 右键展开 ──

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack ball = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(ball);
        }

        AEItemKey key = getKey(ball);
        BigInteger amount = getAmount(ball);
        if (key == null || amount.signum() <= 0) {
            // 空球/损坏球：消耗掉并提示
            player.sendSystemMessage(Component.translatable("gui.ae2addon.matter_ball.empty"));
            ball.shrink(1);
            return InteractionResultHolder.success(ball);
        }

        // 尽可能放入背包，剩余保留在球内
        // ⚠ BigInteger：真实数量可能天文数字，但循环一定会在"背包塞满"时 break，
        //   不会因为数量大而空转（每次至少取 1 个堆叠，addItem 失败即退出）。
        BigInteger remaining = amount;
        int maxStackSize = Math.max(1, key.getItem().getMaxStackSize());
        BigInteger stackBI = BigInteger.valueOf(maxStackSize);
        while (remaining.signum() > 0) {
            int count = remaining.min(stackBI).intValue();
            ItemStack out = key.toStack(count);
            if (!player.addItem(out)) {
                break; // 背包满了，剩余留在球内
            }
            remaining = remaining.subtract(BigInteger.valueOf(count));
        }

        if (remaining.signum() <= 0) {
            // 全部取出 → 消耗球
            ball.shrink(1);
            player.sendSystemMessage(Component.translatable(
                    "gui.ae2addon.matter_ball.unpacked", amount, key.getDisplayName()));
        } else {
            // 部分取出 → 更新球内数量
            ball.getOrCreateTag().putByteArray(NBT_AMOUNT, remaining.toByteArray());
            player.sendSystemMessage(Component.translatable(
                    "gui.ae2addon.matter_ball.partial", amount.subtract(remaining),
                    key.getDisplayName(), remaining));
        }
        return InteractionResultHolder.success(ball);
    }

    // ── Tooltip ──

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        AEItemKey key = getKey(stack);
        BigInteger amount = getAmount(stack);
        if (key != null && amount.signum() > 0) {
            tooltip.add(Component.translatable("gui.ae2addon.matter_ball.tooltip",
                    key.getDisplayName(), amount));
        } else {
            tooltip.add(Component.translatable("gui.ae2addon.matter_ball.tooltip_empty"));
        }
        tooltip.add(Component.translatable("gui.ae2addon.matter_ball.hint"));
    }
}
