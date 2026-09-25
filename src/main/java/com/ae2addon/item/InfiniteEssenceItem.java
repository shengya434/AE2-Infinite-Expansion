package com.ae2addon.item;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 无限 xxx 精华：由千机「**输入全是催化剂（不消耗）**」的配方额外产出，
 * 与对应的 ME 元件外壳在工作台合成「无限 xxx 元件」。
 * <p>
 * ⚠ 不注册成千上万种物品：**一个物品 + NBT 绑定目标 key**
 * （与物质球同思路），显示名按 NBT 动态拼成「无限石头精华」/「无限岩浆精华」。
 * <p>
 * 设计见 {@code docs/infinite-essence-cell.md}（2026-09-19 sensei 定稿）。
 */
public class InfiniteEssenceItem extends Item {

    /** 绑定的目标 AEKey（CompoundTag，{@code AEKey.toTagGeneric()}） */
    public static final String NBT_KEY = "ess_key";

    public InfiniteEssenceItem() {
        super(new Item.Properties().stacksTo(64).rarity(Rarity.RARE));
    }

    /** 造一个绑定到 {@code key} 的精华（count 个） */
    public static ItemStack make(AEKey key, long count) {
        ItemStack stack = new ItemStack(com.ae2addon.init.ModItems.INFINITE_ESSENCE.get(),
                (int) Math.max(1L, Math.min(64L, count)));
        if (key != null) {
            stack.getOrCreateTag().put(NBT_KEY, key.toTagGeneric());
        }
        return stack;
    }

    /** 读取绑定的 key（无绑定/损坏返回 null） */
    @Nullable
    public static AEKey getBoundKey(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) return null;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(NBT_KEY)) return null;
        return AEKey.fromTagGeneric(tag.getCompound(NBT_KEY));
    }

    /** 绑定的是流体吗（决定要用流体元件外壳合成） */
    public static boolean isFluidBound(ItemStack stack) {
        AEKey key = getBoundKey(stack);
        return key != null && AEKeyType.fluids().equals(key.getType());
    }

    /** 显示名：无限<目标名>精华 */
    @Override
    public Component getName(ItemStack stack) {
        AEKey key = getBoundKey(stack);
        if (key == null) {
            return Component.translatable("item.ae2addon.infinite_essence");
        }
        return Component.translatable("gui.ae2addon.essence.name", key.getDisplayName());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        AEKey key = getBoundKey(stack);
        if (key == null) {
            tooltip.add(Component.translatable("gui.ae2addon.essence.unbound"));
            return;
        }
        tooltip.add(Component.translatable("gui.ae2addon.essence.bound", key.getDisplayName()));
        tooltip.add(Component.translatable("gui.ae2addon.essence.from"));
        tooltip.add(Component.translatable("gui.ae2addon.essence.hint",
                Component.translatable(isFluidBound(stack)
                        ? "gui.ae2addon.essence.housing_fluid"
                        : "gui.ae2addon.essence.housing_item")));
    }
}
