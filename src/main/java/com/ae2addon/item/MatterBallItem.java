package com.ae2addon.item;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.ae2addon.compat.MekanismGasCompat;
import com.ae2addon.init.ModItems;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
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

    /** 创建物质球：记录任意 AEKey + 数量（BigInteger，永不截断）。 */
    public static ItemStack makeBall(AEKey key, BigInteger amount) {
        ItemStack ball = new ItemStack(ModItems.MATTER_BALL.get());
        CompoundTag tag = ball.getOrCreateTag();
        tag.put(NBT_KEY, key.toTagGeneric());
        tag.putByteArray(NBT_AMOUNT, amount.max(BigInteger.ZERO).toByteArray());
        return ball;
    }

    /** 读取球内 AEKey（无则 null），兼容旧物品球的 NBT。 */
    @Nullable
    public static AEKey getKey(ItemStack ball) {
        if (ball.isEmpty() || !ball.hasTag()) return null;
        CompoundTag tag = ball.getTag();
        if (tag == null || !tag.contains(NBT_KEY)) return null;
        return AEKey.fromTagGeneric(tag.getCompound(NBT_KEY));
    }

    /** 仅在需要将球内物品取回背包时使用。 */
    @Nullable
    public static AEItemKey getItemKey(ItemStack ball) {
        AEKey key = getKey(ball);
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

        AEItemKey key = getItemKey(ball);
        BigInteger amount = getAmount(ball);
        if (getKey(ball) == null || amount.signum() <= 0) {
            // 空球/损坏球：消耗掉并提示
            player.sendSystemMessage(Component.translatable("gui.ae2addon.matter_ball.empty"));
            ball.shrink(1);
            return InteractionResultHolder.success(ball);
        }
        if (key == null) return InteractionResultHolder.pass(ball); // 非物品球只能灌入对应容器。

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
            keepRemainder(player, ball, key, remaining);
            player.sendSystemMessage(Component.translatable(
                    "gui.ae2addon.matter_ball.partial", amount.subtract(remaining),
                    key.getDisplayName(), remaining));
        }
        return InteractionResultHolder.success(ball);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        ItemStack ball = context.getItemInHand();
        AEKey key = getKey(ball);
        BigInteger amount = getAmount(ball);
        if (key == null || amount.signum() <= 0) {
            player.sendSystemMessage(Component.translatable("gui.ae2addon.matter_ball.empty"));
            return InteractionResult.SUCCESS;
        }
        BlockEntity target = level.getBlockEntity(context.getClickedPos());
        Direction side = context.getClickedFace();
        if (target == null || !supports(target, side, key)) {
            player.sendSystemMessage(Component.translatable("gui.ae2addon.matter_ball.pour_unsup"));
            return InteractionResult.SUCCESS;
        }

        BigInteger remaining = pour(target, side, key, amount);
        BigInteger inserted = amount.subtract(remaining);
        if (inserted.signum() == 0) {
            player.sendSystemMessage(Component.translatable("gui.ae2addon.matter_ball.pour_none"));
        } else if (remaining.signum() == 0) {
            ball.shrink(1);
            player.sendSystemMessage(Component.translatable("gui.ae2addon.matter_ball.pour_ok",
                    key.getDisplayName(), inserted));
        } else {
            keepRemainder(player, ball, key, remaining);
            player.sendSystemMessage(Component.translatable("gui.ae2addon.matter_ball.pour_partial",
                    key.getDisplayName(), inserted, remaining));
        }
        return InteractionResult.SUCCESS;
    }

    private static boolean supports(BlockEntity target, Direction side, AEKey key) {
        if (key instanceof AEItemKey) return target.getCapability(ForgeCapabilities.ITEM_HANDLER, side).isPresent();
        if (key instanceof AEFluidKey) return target.getCapability(ForgeCapabilities.FLUID_HANDLER, side).isPresent();
        return MekanismGasCompat.supports(target, side, key);
    }

    /** 返回剩余量。每次按能力接口可表达的大小灌入；上限防止虚空容器处理天文数量时卡住服务器。 */
    private static BigInteger pour(BlockEntity target, Direction side, AEKey key, BigInteger amount) {
        BigInteger remaining = amount;
        BigInteger intMax = BigInteger.valueOf(Integer.MAX_VALUE);
        for (int attempts = 0; attempts < 4096 && remaining.signum() > 0; attempts++) {
            long inserted;
            if (key instanceof AEItemKey itemKey) {
                IItemHandler handler = target.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
                if (handler == null) break;
                int chunk = remaining.min(BigInteger.valueOf(Math.max(1, itemKey.getItem().getMaxStackSize()))).intValue();
                ItemStack leftover = itemKey.toStack(chunk);
                for (int slot = 0; slot < handler.getSlots() && !leftover.isEmpty(); slot++) {
                    leftover = handler.insertItem(slot, leftover, false);
                }
                inserted = chunk - leftover.getCount();
            } else if (key instanceof AEFluidKey fluidKey) {
                IFluidHandler handler = target.getCapability(ForgeCapabilities.FLUID_HANDLER, side).orElse(null);
                if (handler == null) break;
                int chunk = remaining.min(intMax).intValue();
                inserted = handler.fill(fluidKey.toStack(chunk), IFluidHandler.FluidAction.EXECUTE);
            } else {
                long chunk = remaining.min(intMax).longValue();
                inserted = MekanismGasCompat.insert(target, side, key, chunk);
            }
            if (inserted <= 0) break;
            remaining = remaining.subtract(BigInteger.valueOf(inserted));
        }
        return remaining;
    }

    /** 同种球可堆叠；部分取出时只修改当前一颗球，避免改掉整堆球的 NBT。 */
    private static void keepRemainder(Player player, ItemStack ball, AEKey key, BigInteger remaining) {
        if (ball.getCount() == 1) {
            ball.getOrCreateTag().putByteArray(NBT_AMOUNT, remaining.toByteArray());
            return;
        }
        ball.shrink(1);
        ItemStack leftoverBall = makeBall(key, remaining);
        if (!player.addItem(leftoverBall) && !leftoverBall.isEmpty()) {
            player.drop(leftoverBall, false); // 最多一颗球实体。
        }
    }

    // ── Tooltip ──

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        AEKey key = getKey(stack);
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
