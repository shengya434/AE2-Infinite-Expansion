package com.ae2addon.item;

import com.ae2addon.recipe.QianJiPatternData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 千机配方样板（自有样板体系，2026-09-15 sensei 定稿）。
 * <p>
 * 把「输入 → 主产物 + 概率产出（含几率）」完整写在物品数据上：
 * 千机读到即**精确执行**，不再反推各 mod 配方去猜几率。
 * 由 JEI 页「千机·可处理配方」的编码按钮产出（当前也支持 `/qianji make <配方id>` 生成，便于测试）。
 */
public class QianJiPatternItem extends Item {

    private static final Logger LOGGER = LogManager.getLogger("ae2addon");

    public QianJiPatternItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        QianJiPatternData data = QianJiPatternData.of(stack);
        if (data == null) {
            tooltip.add(Component.literal("§c空样板（未编码）"));
            return;
        }
        for (String line : data.describe()) {
            tooltip.add(Component.literal(line));
        }
        tooltip.add(Component.literal("§8潜行+右键空气 = 还原为空白样板"));
    }

    /** 潜行 + 右键（空气或方块）→ 还原成普通 AE2 空白样板（误编码/回收用） */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!isRestoreRequest(player)) return InteractionResultHolder.pass(stack);
        return doRestore(level, player, stack, "空气");
    }

    @Override
    public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !isRestoreRequest(player)) return InteractionResult.PASS;
        var result = doRestore(context.getLevel(), player, context.getItemInHand(), "方块");
        return result.getResult().consumesAction() ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /** 潜行判定：isShiftKeyDown 是即时输入标志（服务端立刻为 true），isCrouching 是姿势（会晚一 tick） */
    private static boolean isRestoreRequest(Player player) {
        return player.isShiftKeyDown() || player.isCrouching();
    }

    private static InteractionResultHolder<ItemStack> doRestore(Level level, Player player, ItemStack stack,
                                                                 String source) {
        if (level.isClientSide) return InteractionResultHolder.sidedSuccess(stack, true);

        LOGGER.info("[ae2addon] 样板还原请求({}): shiftDown={} crouch={}",
                source, player.isShiftKeyDown(), player.isCrouching());

        ItemStack blank = blankPattern();
        if (blank.isEmpty()) {
            LOGGER.warn("[ae2addon] 样板还原失败：取不到 ae2:blank_pattern");
            player.displayClientMessage(Component.literal("§c找不到空白样板（ae2:blank_pattern），无法还原"), false);
            return InteractionResultHolder.fail(stack);
        }
        stack.shrink(1);
        if (!player.getInventory().add(blank)) {
            player.drop(blank, false);
        }
        LOGGER.info("[ae2addon] 样板已还原为空白样板（{}）", source);
        player.displayClientMessage(Component.literal("§7已还原为空白样板"), true);
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    private static ItemStack blankPattern() {
        // 用 AE2 自己的常量（比按 id 查注册表稳）
        try {
            var item = appeng.core.definitions.AEItems.BLANK_PATTERN.asItem();
            if (item != null && item != net.minecraft.world.item.Items.AIR) return new ItemStack(item);
        } catch (Throwable ignored) {
            // 退化到注册名查找
        }
        var fallback = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                new net.minecraft.resources.ResourceLocation("ae2", "blank_pattern"));
        return fallback == null ? ItemStack.EMPTY : new ItemStack(fallback);
    }
}
