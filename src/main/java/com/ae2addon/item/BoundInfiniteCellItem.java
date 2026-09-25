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
 * 无限 xxx 元件（**单物品/单流体永久绑定**的无限存储元件）。
 * <p>
 * 由「无限 xxx 精华 + 对应 ME 元件外壳」在工作台合成（见 EssenceToCellRecipe）。
 * 与 {@link UniversalStorageCell}（三模式万能元件）不同，这个元件：
 * <ul>
 *   <li>只认**一种** key（NBT 绑定的那个），其它一律拒收/不报</li>
 *   <li>该 key **无限存取**：提取永远给得出，存入多少个都收下</li>
 *   <li>绑定**永久**（没有解绑入口，符合 sensei「单物品永久绑定」的口径）</li>
 * </ul>
 * 存储实现见 {@code com.ae2addon.cell.BoundInfiniteCellInventory}。
 * 设计文档：{@code docs/infinite-essence-cell.md}
 */
public class BoundInfiniteCellItem extends Item {

    /** 绑定的目标 AEKey */
    public static final String NBT_KEY = "bind_key";

    /**
     * 元件变体（只影响**外观、命名与配方匹配**；实际存什么完全由绑定的 key 决定）。
     * 2026-09-19：加入 {@link #CHEMICAL}（Applied-Mekanistics 化学品）。
     */
    public enum Kind {
        ITEM, FLUID, CHEMICAL
    }

    private final Kind kind;

    public BoundInfiniteCellItem(Kind kind) {
        super(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** 造一个绑定到 {@code key} 的元件 */
    public static ItemStack make(Item cellItem, AEKey key) {
        ItemStack stack = new ItemStack(cellItem);
        if (key != null) {
            stack.getOrCreateTag().put(NBT_KEY, key.toTagGeneric());
        }
        return stack;
    }

    /** 读取绑定的 key（无绑定返回 null） */
    @Nullable
    public static AEKey getBoundKey(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) return null;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(NBT_KEY)) return null;
        return AEKey.fromTagGeneric(tag.getCompound(NBT_KEY));
    }

    /** 显示名：无限<目标名>元件 */
    @Override
    public Component getName(ItemStack stack) {
        AEKey key = getBoundKey(stack);
        if (key == null) {
            return Component.translatable(switch (kind) {
                case FLUID -> "item.ae2addon.infinite_fluid_cell";
                case CHEMICAL -> "item.ae2addon.infinite_chemical_cell";
                case ITEM -> "item.ae2addon.infinite_item_cell";
            });
        }
        return Component.translatable("gui.ae2addon.bound_cell.name", key.getDisplayName());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        AEKey key = getBoundKey(stack);
        if (key == null) {
            tooltip.add(Component.translatable("gui.ae2addon.bound_cell.unbound"));
            return;
        }
        tooltip.add(Component.translatable("gui.ae2addon.bound_cell.bound", key.getDisplayName()));
        tooltip.add(Component.translatable("gui.ae2addon.bound_cell.desc"));
        if (AEKeyType.fluids().equals(key.getType())) {
            tooltip.add(Component.translatable("gui.ae2addon.bound_cell.fluid_hint"));
        } else if (com.ae2addon.compat.ChemicalCompat.isChemical(key)) {
            tooltip.add(Component.translatable("gui.ae2addon.bound_cell.chemical_hint"));
        }
    }
}
