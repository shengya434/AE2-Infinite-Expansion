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
 * 放入千机·阿比舒 的催化剂槽中：
 * - 基础（Tier 1）: 副产物概率 +2%，耗电 ×4
 * - 高级（Tier 2）: 副产物概率 +5%，耗电 ×20
 * - 终极（Tier 3）: 副产物概率 +10%，耗电 ×400
 * <p>
 * 加成是**加法百分点**（叠在配方自带几率上）：配方 15% → 17%/20%/25%，不会变必然。
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
     * 副产物概率加成（加法百分点：基础 +2% / 高级 +5% / 终极 +10%）。
     * <p>
     * ⚠ 2026-09-15 从「倍率（×2/×5/×10）」改成加法：倍率会把配方自带的 15% 副产
     * 在高级/终极催化剂下顶成 75%/100%（sensei 实测 15% 副产 5 中 5）。
     */
    public double getByproductBonus() {
        return switch (tier) {
            case 1 -> 0.02;
            case 2 -> 0.05;
            case 3 -> 0.10;
            default -> 0.0;
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
            case 1 -> "+2%";
            case 2 -> "+5%";
            case 3 -> "+10%";
            default -> "+0%";
        };
        String powerMult = switch (tier) {
            case 1 -> "×4";
            case 2 -> "×20";
            case 3 -> "×400";
            default -> "×1";
        };

        tooltip.add(Component.literal("§7等级: " + name));
        tooltip.add(Component.literal("§7副产物概率: §e" + bonus
                + " §8（在配方自带几率上叠加）"));
        tooltip.add(Component.literal("§7耗电倍率: §c" + powerMult));
        tooltip.add(Component.literal(""));
        tooltip.add(Component.literal("§8放入千机·阿比舒的催化剂槽使用"));
        tooltip.add(Component.literal("§8不消耗，可随时更换"));
    }
}
