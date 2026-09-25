package com.ae2addon.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 催化剂物品（基础/高级/终极）
 * <p>
 * 放入千机·阿比舒 的催化剂槽中（**产出数量倍数**，每次合成掷一次）：
 * - 基础（Tier 1）: 50% ×2 / 50% ×1（期望 ×1.5），耗电 ×4
 * - 高级（Tier 2）: ×2，耗电 ×20
 * - 终极（Tier 3）: 50% ×4 / 50% ×3（期望 ×3.5），耗电 ×400
 * <p>
 * 乘的是**命中后的产出数量**（主产物与副产物**都乘**）；几率不被催化剂改动。
 * <p>
 * 不消耗，可随时更换。
 */
public class CatalystItem extends Item {

    private final int tier;

    public CatalystItem(int tier) {
        super(new Item.Properties()
                .stacksTo(1)
                .rarity(tier >= 3 ? Rarity.EPIC : (tier == 2 ? Rarity.RARE : Rarity.UNCOMMON))
                .fireResistant());
        this.tier = tier;
    }

    public int getTier() {
        return tier;
    }

    /**
     * 掷一次「产出数量倍数」（每次合成掷一次；期望值 1.5 / 2 / 3.5）。
     * <p>
     * ⚠ 2026-09-15 sensei 定稿：回到**倍数**制（不再用 +2%/+5%/+10% 加法百分点），
     * 而且乘在**命中概率后的产出数量**上（主产物与副产物都生效）：
     * 基础 50% 翻倍、高级固定 2 倍、终极 50% 3 倍 / 50% 4 倍。
     * <p>
     * 要调就改这个方法里的数（返回 1 = 本次不倍增）。
     */
    public int rollOutputMultiplier(net.minecraft.util.RandomSource random) {
        return switch (tier) {
            case 1 -> random.nextFloat() < 0.5f ? 2 : 1;
            case 2 -> 2;
            case 3 -> random.nextFloat() < 0.5f ? 4 : 3;
            default -> 1;
        };
    }

    /**
     * 本催化剂一次掷骰会产生几种**不同**的倍率值（0/1 = 没有随机性）。
     * <p>
     * ⚠ 2026-09-18 晚：给"超大订单免逐份掷骰"用。倍数无随机性的催化剂（高级 ×2）不必掷骰；
     * 有随机性的（基础 50%×2、终极 50%×4）可以用**二项分布抽样**一次性算出
     * 「N 份里有多少份翻倍」，分布与逐份掷骰完全等价、但代价 O(1)。
     */
    public int distinctMultiplierCount() {
        return switch (tier) {
            case 1, 3 -> 2; // 50/50
            case 2 -> 1;    // 恒定 ×2
            default -> 0;   // 无催化剂 → 恒定 ×1
        };
    }

    /** 50% 翻倍那两档的「翻倍时的倍数」（基础 ×2 / 终极 ×4）；无随机性或 0 档返回 0 */
    public int doubledMultiplier() {
        return switch (tier) {
            case 1 -> 2;
            case 3 -> 4;
            default -> 0;
        };
    }

    /** 不翻倍时的倍数（基础 ×1 / 终极 ×3 / 高级 ×2） */
    public int baseMultiplier() {
        return switch (tier) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 3;
            default -> 1;
        };
    }

    @Override
    public Component getName(ItemStack stack) {
        return switch (tier) {
            case 1 -> Component.translatable("item.ae2addon.catalyst_basic");
            case 2 -> Component.translatable("item.ae2addon.catalyst_advanced");
            case 3 -> Component.translatable("item.ae2addon.catalyst_ultimate");
            default -> super.getName(stack);
        };
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        String name = switch (tier) {
            case 1 -> "§a基础";
            case 2 -> "§6高级";
            case 3 -> "§d终极";
            default -> "§7未知";
        };
        String bonus = switch (tier) {
            case 1 -> "×1.5 §8(50% ×2)";
            case 2 -> "×2";
            case 3 -> "×3.5 §8(50% ×3 / 50% ×4)";
            default -> "×1";
        };
        String powerMult = switch (tier) {
            case 1 -> "×4";
            case 2 -> "×20";
            case 3 -> "×400";
            default -> "×1";
        };

        tooltip.add(Component.literal("§7等级: " + name));
        tooltip.add(Component.literal("§7产出数量: §e" + bonus));
        tooltip.add(Component.literal("§8主产物与副产物都乘；每次合成掷一次"));
        tooltip.add(Component.literal("§7耗电倍率: §c" + powerMult));
        tooltip.add(Component.literal(""));
        tooltip.add(Component.literal("§8放入千机·阿比舒的催化剂槽使用"));
        tooltip.add(Component.literal("§8不消耗，可随时更换"));
    }
}
