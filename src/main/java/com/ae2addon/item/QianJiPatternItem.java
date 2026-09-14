package com.ae2addon.item;

import com.ae2addon.recipe.QianJiPatternData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
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

    /** 潜行 + 右键空气 → 还原成普通 AE2 空白样板（误编码/回收用） */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isCrouching()) return InteractionResultHolder.pass(stack);
        if (level.isClientSide) return InteractionResultHolder.sidedSuccess(stack, true);

        ItemStack blank = blankPattern();
        if (blank.isEmpty()) {
            player.displayClientMessage(Component.literal("§c找不到 ae2:blank_pattern，无法还原"), false);
            return InteractionResultHolder.fail(stack);
        }
        stack.shrink(1);
        if (!player.getInventory().add(blank)) {
            player.drop(blank, false);
        }
        player.displayClientMessage(Component.literal("§7已还原为空白样板"), true);
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    private static ItemStack blankPattern() {
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                new net.minecraft.resources.ResourceLocation("ae2", "blank_pattern"));
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
}
