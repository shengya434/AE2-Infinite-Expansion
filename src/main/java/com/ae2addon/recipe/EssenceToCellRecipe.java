package com.ae2addon.recipe;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.core.definitions.AEItems;
import com.ae2addon.init.ModItems;
import com.ae2addon.item.BoundInfiniteCellItem;
import com.ae2addon.item.InfiniteEssenceItem;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.level.Level;

/**
 * 无限 xxx 元件的合成配方：**无限精华 + 对应 ME 元件外壳 → 元件**。
 * <p>
 * 2026-09-19 sensei 定稿，见 {@code docs/infinite-essence-cell.md}：
 * <ul>
 *   <li>物品精华只能配 {@code ITEM_CELL_HOUSING}（→ 物品元件）</li>
 *   <li>流体精华只能配 {@code FLUID_CELL_HOUSING}（→ 流体元件）</li>
 *   <li>类型不匹配 / 有多余物品 / 出现两个精华或两个外壳 → 不匹配</li>
 * </ul>
 * 因为精华与元件的身份都在 NBT 里（一个物品代表上万种可能），这里必须用**特殊配方**
 * （{@link CustomRecipe}，不进配方书、结果由输入动态推导），
 * 并由一个数据包 JSON（只有 {@code type}、无参数）提供实例。
 */
public class EssenceToCellRecipe extends CustomRecipe {

    public EssenceToCellRecipe(ResourceLocation id) {
        // 1.20.1 的 CustomRecipe 构造器带"配方书分类"（CraftingBookCategory）
        super(id, net.minecraft.world.item.crafting.CraftingBookCategory.MISC);
    }

    /** 外壳是不是物品元件外壳 */
    private static boolean isItemHousing(ItemStack s) {
        return s.is(AEItems.ITEM_CELL_HOUSING.asItem());
    }

    /** 外壳是不是流体元件外壳 */
    private static boolean isFluidHousing(ItemStack s) {
        return s.is(AEItems.FLUID_CELL_HOUSING.asItem());
    }

    /** 外壳 → 元件变体（null = 不是我们认识的外壳）。化学品外壳来自 Applied-Mekanistics（可选）。 */
    private static BoundInfiniteCellItem.Kind housingKind(ItemStack s) {
        if (isItemHousing(s)) return BoundInfiniteCellItem.Kind.ITEM;
        if (isFluidHousing(s)) return BoundInfiniteCellItem.Kind.FLUID;
        Item chem = com.ae2addon.compat.ChemicalCompat.chemicalHousing();
        if (chem != null && s.is(chem)) return BoundInfiniteCellItem.Kind.CHEMICAL;
        return null;
    }

    /** 精华绑定的 key → 它需要哪种外壳（化学品优先判，避免与物品/流体混淆） */
    private static BoundInfiniteCellItem.Kind neededKind(AEKey key) {
        if (AEKeyType.fluids().equals(key.getType())) return BoundInfiniteCellItem.Kind.FLUID;
        if (com.ae2addon.compat.ChemicalCompat.isChemical(key)) {
            return BoundInfiniteCellItem.Kind.CHEMICAL;
        }
        return BoundInfiniteCellItem.Kind.ITEM;
    }

    /** 变体 → 成品元件物品 */
    private static Item cellItemFor(BoundInfiniteCellItem.Kind kind) {
        return switch (kind) {
            case ITEM -> ModItems.INFINITE_ITEM_CELL.get();
            case FLUID -> ModItems.INFINITE_FLUID_CELL.get();
            case CHEMICAL -> ModItems.INFINITE_CHEMICAL_CELL.get();
        };
    }

    /**
     * 由合成格推导结果（不匹配返回空）。
     * <p>
     * 判定规则：格子里的非空物品**恰好**是「1 个精华 + 1 个外壳」，且外壳类型与精华绑定类型一致。
     */
    private static ItemStack craftResult(CraftingContainer inv) {
        ItemStack essence = null;
        ItemStack housing = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            if (s.getItem() instanceof InfiniteEssenceItem) {
                if (essence != null) return ItemStack.EMPTY;   // 两个精华 → 不匹配
                essence = s;
            } else if (housingKind(s) != null) {
                if (housing != null) return ItemStack.EMPTY;   // 两个外壳 → 不匹配
                housing = s;
            } else {
                return ItemStack.EMPTY;                        // 混入其它物品 → 不匹配
            }
        }
        if (essence == null || housing == null) {
            return ItemStack.EMPTY;
        }
        AEKey key = InfiniteEssenceItem.getBoundKey(essence);
        if (key == null) {
            return ItemStack.EMPTY;
        }
        BoundInfiniteCellItem.Kind need = neededKind(key);
        if (need != housingKind(housing)) {
            return ItemStack.EMPTY;   // 精华类型与外壳类型必须一致
        }
        return BoundInfiniteCellItem.make(cellItemFor(need), key);
    }

    @Override
    public boolean matches(CraftingContainer inv, Level level) {
        return !craftResult(inv).isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingContainer inv, RegistryAccess registryAccess) {
        return craftResult(inv);
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registryAccess) {
        // 动态结果：静态结果为空（JEI 走我们自己的信息页，见文档 §5）
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    /** 精华与外壳各消耗 1 个（默认实现就是不返还，这里显式写明语义） */
    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingContainer inv) {
        return NonNullList.withSize(inv.getContainerSize(), ItemStack.EMPTY);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return com.ae2addon.init.ModRecipes.ESSENCE_TO_CELL.get();
    }

    /**
     * 序列化器：配方没有任何参数（一条实例匹配所有精华/外壳组合），
     * 所以 JSON 只有 {@code {"type": "ae2addon:essence_to_cell"}}，网络同步也不传数据。
     */
    public static class Serializer implements RecipeSerializer<EssenceToCellRecipe> {
        @Override
        public EssenceToCellRecipe fromJson(ResourceLocation id, JsonObject json) {
            return new EssenceToCellRecipe(id);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, EssenceToCellRecipe recipe) {
            // 无参数
        }

        @Override
        public EssenceToCellRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            return new EssenceToCellRecipe(id);
        }
    }
}
