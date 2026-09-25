package com.ae2addon.recipe;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * **爆炸配方**（2026-09-19 sensei 选定方案 A）：把东西扔在地上炸一下 → 变成产物。
 * <p>
 * 为什么不用 AE2 原生的 {@code ae2:transform}（就是 JEI 里 AE2「世界交互 · 爆炸」那一页）：
 * 它的 {@code ingredients} 是**原版 Ingredient**（序列化器只读 {@code ingredients}/{@code result}/{@code circumstance}，
 * 没有数量字段），而且 {@code TransformLogic} 里**一条原料只吃一个掉落物实体**
 * （命中后把该实体从候选表移除、只取 1 个物品）。所以清单第 1 行的
 * 「64x样板供应器+64xME接口+16奇点」用原生表达不出来 —— 数量既写不进去，
 * 也没法靠"堆叠 64 个"满足（堆叠会合并成一个实体）。
 * <p>
 * 于是我们自己做一套：数量按**物品个数**跨堆叠统计（见
 * {@link com.ae2addon.crafting.ExplosionRecipeHandler}），JSON 形态：
 * <pre>
 * {
 *   "type": "ae2addon:explosion_recipe",
 *   "inputs": [
 *     {"item": "ae2:pattern_provider", "count": 64},
 *     {"item": "ae2:interface", "count": 64},
 *     {"item": "ae2:singularity", "count": 16}
 *   ],
 *   "result": {"item": "ae2addon:qianji", "count": 1}
 * }
 * </pre>
 * ⚠ 只支持**物品**输入：世界里的爆炸只能碰到掉落物实体。写了流体/化学品会被忽略并在日志里提示。
 */
public class ExplosionRecipe implements Recipe<Container> {

    /** 配方类型 id（同时当样板数据里的 machine 用，仅供 JEI 展示） */
    public static final String TYPE_ID = "ae2addon:explosion_recipe";

    private final ResourceLocation id;
    private final List<GenericStack> inputs;
    private final GenericStack result;

    public ExplosionRecipe(ResourceLocation id, List<GenericStack> inputs, GenericStack result) {
        this.id = id;
        this.inputs = List.copyOf(inputs);
        this.result = result;
    }

    public List<GenericStack> inputs() {
        return inputs;
    }

    /** 产物（数量可能 > 64，由处理器拆成多个掉落物实体） */
    public GenericStack result() {
        return result;
    }

    /** 给 JEI 千机页展示用：转成样板数据（machine = 本类型 id → JEI 用 TNT 图标）。 */
    public QianJiPatternData toPatternData() {
        var slots = new ArrayList<QianJiPatternData.Slot>();
        for (GenericStack in : inputs) {
            slots.add(new QianJiPatternData.Slot(List.of(in)));
        }
        var primary = result == null ? List.<QianJiPatternData.Out>of()
                : List.of(new QianJiPatternData.Out(result));
        return new QianJiPatternData(TYPE_ID, id.toString(), slots, primary, List.of());
    }

    // ── Recipe 必备方法（这个配方不在任何容器里合成）──

    @Override
    public boolean matches(Container container, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(Container container, RegistryAccess access) {
        return getResultItem(access);
    }

    @Override
    public ItemStack getResultItem(RegistryAccess access) {
        if (result == null || !(result.what() instanceof AEItemKey itemKey)) {
            return ItemStack.EMPTY;
        }
        int show = (int) Math.max(1L, Math.min(64L, result.amount()));
        ItemStack stack = itemKey.toStack(show);
        stack.setCount(show);
        return stack;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(Container container) {
        return NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
    }

    @Override
    public ItemStack getToastSymbol() {
        return new ItemStack(Items.TNT);
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return com.ae2addon.init.ModRecipes.EXPLOSION_RECIPE.get();
    }

    @Override
    public RecipeType<?> getType() {
        return com.ae2addon.init.ModRecipes.EXPLOSION_TYPE.get();
    }

    // ── 序列化 ──

    private static List<GenericStack> readInputs(JsonObject json, ResourceLocation id) {
        var out = new ArrayList<GenericStack>();
        JsonArray arr = json.has("inputs")
                ? net.minecraft.util.GsonHelper.getAsJsonArray(json, "inputs") : new JsonArray();
        for (JsonElement el : arr) {
            GenericStack stack = QianJiRecipe.readStack(el.getAsJsonObject(), "explosion/inputs");
            if (stack == null) {
                continue;
            }
            if (!(stack.what() instanceof AEItemKey)) {
                // 爆炸只能碰到掉落物实体 → 非物品输入直接跳过（否则配方永远匹配不上）
                com.ae2addon.AE2Addon.LOGGER.warn(
                        "[ae2addon] 爆炸配方 {} 的输入 {} 不是物品，已忽略（爆炸只支持物品）",
                        id, stack.what().getDisplayName().getString());
                continue;
            }
            out.add(stack);
        }
        return out;
    }

    private static GenericStack readResult(JsonObject json) {
        if (!json.has("result")) {
            return null;
        }
        return QianJiRecipe.readStack(json.getAsJsonObject("result"), "explosion/result");
    }

    /** JSON / 网络双向（数据包 + 客户端 JEI 都要） */
    public static class Serializer implements RecipeSerializer<ExplosionRecipe> {

        @Override
        public ExplosionRecipe fromJson(ResourceLocation id, JsonObject json) {
            for (String mod : QianJiRecipe.modIds(json)) {
                if (!net.minecraftforge.fml.ModList.get().isLoaded(mod)) {
                    com.ae2addon.AE2Addon.LOGGER.info("[ae2addon] 爆炸配方 {} 跳过（未装 {}）", id, mod);
                    return new ExplosionRecipe(id, List.of(), null);
                }
            }
            var inputs = readInputs(json, id);
            GenericStack result = readResult(json);
            if (result == null) {
                com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 爆炸配方 {} 没有有效产物，已作废", id);
            }
            if (inputs.isEmpty()) {
                com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 爆炸配方 {} 没有有效输入，已作废", id);
            }
            return new ExplosionRecipe(id, inputs, result);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, ExplosionRecipe recipe) {
            buf.writeVarInt(recipe.inputs.size());
            for (GenericStack in : recipe.inputs) {
                GenericStack.writeBuffer(in, buf);
            }
            buf.writeBoolean(recipe.result != null);
            if (recipe.result != null) {
                GenericStack.writeBuffer(recipe.result, buf);
            }
        }

        @Override
        public ExplosionRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            var inputs = new ArrayList<GenericStack>(n);
            for (int i = 0; i < n; i++) {
                GenericStack in = GenericStack.readBuffer(buf);
                if (in != null) {
                    inputs.add(in);
                }
            }
            GenericStack result = buf.readBoolean() ? GenericStack.readBuffer(buf) : null;
            return new ExplosionRecipe(id, inputs, result);
        }
    }
}
