package com.ae2addon.recipe;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * **千机自用配方**（2026-09-19 sensei 定稿）：数据包定义、由千机自己执行的配方。
 * <p>
 * 为什么需要它：千机原本的自用配方来自"配方管理器里某条真实配方"（合成/熔炼/机器配方），
 * 而 sensei 这批配方带**流体**（熔岩/水/反物质气体）与**超大数量**（百万级物品、万桶流体），
 * 原版 `crafting_shaped` 根本表达不了。于是给我们自己一个配方类型：
 * 输入（物品/流体/化学品，可标"不消耗"）+ 确定产出 + 概率产出，数量都是 long。
 * <p>
 * JSON 形态（`data/ae2addon/recipes/*.json`）：
 * <pre>
 * {
 *   "type": "ae2addon:qianji_recipe",
 *   // machine 可省：默认就是这个配方类型 id（JEI「机器」栏显示千机本体）
 *   "modLoaded": "extendedae_plus",              // 可省；装了才注册（也支持数组）
 *   "inputs": [
 *     {"item": "ae2:sky_stone_chest", "count": 128},
 *     {"fluid": "minecraft:water", "buckets": 100},              // 也可写 "amount"（mB）
 *     {"gas": "mekanism:nuclear_waste", "buckets": 102400},      // 化学品（Applied-Mekanistics）
 *     {"item": "extendedae_plus:entity_speed_card", "count": 128,
 *      "nbt": {"EAS:mult": 16}},                                  // NBT 变体（同物品不同 NBT）
 *     {"item": "gtceu:shaped_mold", "count": 1, "catalyst": true} // catalyst = 不消耗
 *   ],
 *   "primary": [ {"item": "ae2addon:infinite_crafting_storage", "count": 1} ],
 *   "chanced": [ {"item": "ae2addon:eternal_heart", "count": 1, "chance": 0.05} ]
 * }
 * </pre>
 * ⚠ **流体单位**：`buckets` = 桶（自动 ×1000 转 mB）；`amount` = mB（AE2 内部单位）。
 * sensei 的清单里 B=桶、KB=千桶、MB=百万桶，建议直接写 `buckets`。
 */
public class QianJiRecipe implements Recipe<Container> {

    /** 我们的配方类型 id（也用作 {@link QianJiPatternData#machine()}，见 {@code isBasicType}） */
    public static final String MACHINE_ID = "ae2addon:qianji_recipe";

    private final ResourceLocation id;
    private final QianJiPatternData data;

    public QianJiRecipe(ResourceLocation id, QianJiPatternData data) {
        this.id = id;
        this.data = data;
    }

    public QianJiPatternData data() {
        return data;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return com.ae2addon.init.ModRecipes.QIANJI_RECIPE.get();
    }

    @Override
    public RecipeType<?> getType() {
        return com.ae2addon.init.ModRecipes.QIANJI_TYPE.get();
    }

    // ── 这个配方**不在任何原版工作台里合成**（没有容器语义），所以匹配恒 false ──
    // 产物仍由 getResultItem 给出，JEI/千机页读的是 data()

    @Override
    public boolean matches(Container container, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(Container container, RegistryAccess access) {
        return getResultItem(access);
    }

    /** 展示用产物：第一个确定产出（没有则第一个概率产出） */
    @Override
    public ItemStack getResultItem(RegistryAccess access) {
        GenericStack first = null;
        if (!data.primary().isEmpty()) {
            first = data.primary().get(0).stack();
        } else if (!data.chanced().isEmpty()) {
            first = data.chanced().get(0).stack();
        }
        if (first == null || !(first.what() instanceof AEItemKey itemKey)) {
            return ItemStack.EMPTY;   // 流体/化学物产物：JEI 千机页自己按 GenericStack 画
        }
        int show = (int) Math.max(1L, Math.min(64L, first.amount()));
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
        return true;   // 不进配方书
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(Container container) {
        return NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
    }

    @Override
    public ItemStack getToastSymbol() {
        return new ItemStack(com.ae2addon.init.ModItems.QIAN_JI_PATTERN.get());
    }

    // ── JSON 解析 ──

    /** 解析一条输入/产出条目（item / fluid / gas 三选一 + 数量）。**包内共用**：爆炸配方也走它。 */
    static GenericStack readStack(JsonObject o, String where) {
        long count = 1L;
        if (o.has("count")) {
            count = o.get("count").getAsLong();
        } else if (o.has("amount")) {
            count = o.get("amount").getAsLong();
        } else if (o.has("buckets")) {
            count = o.get("buckets").getAsLong() * 1000L;   // 桶 → mB
        }
        count = Math.max(1L, count);

        AEKey key = null;
        try {
            if (o.has("item")) {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(o.get("item").getAsString()));
                if (item != null) {
                    if (o.has("nbt") && o.get("nbt").isJsonObject()) {
                        // NBT 变体（例：extendedae_plus:entity_speed_card 的 EAS:mult=16）
                        ItemStack stack = new ItemStack(item);
                        stack.setTag(jsonToTag(o.getAsJsonObject("nbt")));
                        key = AEItemKey.of(stack);
                    } else {
                        key = AEItemKey.of(item);
                    }
                }
            } else if (o.has("fluid")) {
                var fluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(o.get("fluid").getAsString()));
                if (fluid != null) {
                    key = AEFluidKey.of(fluid);
                }
            } else if (o.has("gas") || o.has("chemical")) {
                // 化学品（Applied-Mekanistics）：走 compat 层的多写法尝试，不硬依赖对方
                String cid = o.has("gas") ? o.get("gas").getAsString() : o.get("chemical").getAsString();
                key = com.ae2addon.compat.ChemicalCompat.keyOf(cid, count);
            }
        } catch (Throwable t) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 千机配方条目解析异常（{}）：{} → {}",
                    where, o, t.toString());
        }
        if (key == null) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 千机配方条目解析失败（{}）：{}", where, o);
            return null;
        }
        return new GenericStack(key, count);
    }

    /** JSON 对象 → NBT。数字口径跟原版 SNBT 一致：整数不越界 → Int，越界 → Long，带小数点 → Double。 */
    static CompoundTag jsonToTag(JsonObject json) {
        var tag = new CompoundTag();
        for (var entry : json.entrySet()) {
            JsonElement v = entry.getValue();
            if (v.isJsonObject()) {
                tag.put(entry.getKey(), jsonToTag(v.getAsJsonObject()));
            } else if (v.isJsonArray()) {
                var list = new ListTag();
                for (JsonElement el : v.getAsJsonArray()) {
                    if (el.isJsonObject()) {
                        list.add(jsonToTag(el.getAsJsonObject()));
                    } else if (el.isJsonPrimitive()) {
                        list.add(primitiveToTag(el.getAsJsonPrimitive()));
                    }
                }
                tag.put(entry.getKey(), list);
            } else if (v.isJsonPrimitive()) {
                tag.put(entry.getKey(), primitiveToTag(v.getAsJsonPrimitive()));
            }
        }
        return tag;
    }

    private static Tag primitiveToTag(JsonPrimitive p) {
        if (p.isBoolean()) {
            return net.minecraft.nbt.ByteTag.valueOf(p.getAsBoolean());
        }
        if (p.isNumber()) {
            String raw = p.getAsString();
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                long l = p.getAsLong();
                return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE)
                        ? IntTag.valueOf((int) l) : LongTag.valueOf(l);
            }
            return DoubleTag.valueOf(p.getAsDouble());
        }
        return StringTag.valueOf(p.getAsString());
    }

    private static List<QianJiPatternData.Slot> readInputs(JsonObject json) {
        var out = new ArrayList<QianJiPatternData.Slot>();
        JsonArray arr = json.has("inputs") ? GsonHelper.getAsJsonArray(json, "inputs") : new JsonArray();
        for (JsonElement el : arr) {
            readSlot(el.getAsJsonObject(), "inputs", out);
        }
        // modInputs：**装了对应模组才追加**的输入（2026-09-19 sensei：
        // 「万能无限盘的配方应该只能存在一个配方，装了 EAEP/MEK 再在这条配方里加上对应的东西」）
        // 形态：[{ "modLoaded": "mekanism", "inputs": [ …同 inputs 格式… ] }, …]
        if (json.has("modInputs")) {
            for (JsonElement groupEl : GsonHelper.getAsJsonArray(json, "modInputs")) {
                JsonObject group = groupEl.getAsJsonObject();
                var missing = new StringBuilder();
                for (String mod : modIds(group)) {
                    if (!net.minecraftforge.fml.ModList.get().isLoaded(mod)) {
                        missing.append(mod).append(' ');
                    }
                }
                if (missing.length() > 0) {
                    com.ae2addon.AE2Addon.LOGGER.info(
                            "[ae2addon] 千机配方的追加输入跳过（未装 {}）", missing.toString().trim());
                    continue;
                }
                JsonArray groupInputs = group.has("inputs")
                        ? GsonHelper.getAsJsonArray(group, "inputs") : new JsonArray();
                for (JsonElement el : groupInputs) {
                    readSlot(el.getAsJsonObject(), "modInputs", out);
                }
            }
        }
        return out;
    }

    /** 解析一条输入槽并追加（含 `catalyst` 标记） */
    private static void readSlot(JsonObject o, String where, List<QianJiPatternData.Slot> out) {
        GenericStack stack = readStack(o, where);
        if (stack == null) {
            return;
        }
        boolean catalyst = o.has("catalyst") && o.get("catalyst").getAsBoolean();
        // 一个槽一个候选（要"任一候选"就写多条输入槽 —— 与 AE2 样板"槽位独立"的语义一致）
        out.add(new QianJiPatternData.Slot(List.of(stack), catalyst));
    }

    private static List<QianJiPatternData.Out> readPrimary(JsonObject json) {
        var out = new ArrayList<QianJiPatternData.Out>();
        JsonArray arr = json.has("primary") ? GsonHelper.getAsJsonArray(json, "primary") : new JsonArray();
        for (JsonElement el : arr) {
            GenericStack stack = readStack(el.getAsJsonObject(), "primary");
            if (stack != null) {
                out.add(new QianJiPatternData.Out(stack));
            }
        }
        return out;
    }

    private static List<QianJiPatternData.Chanced> readChanced(JsonObject json) {
        var out = new ArrayList<QianJiPatternData.Chanced>();
        JsonArray arr = json.has("chanced") ? GsonHelper.getAsJsonArray(json, "chanced") : new JsonArray();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            GenericStack stack = readStack(o, "chanced");
            if (stack == null) {
                continue;
            }
            float chance = o.has("chance") ? o.get("chance").getAsFloat() : -1f;   // <0 = 必出
            out.add(new QianJiPatternData.Chanced(stack, chance));
        }
        return out;
    }

    /** `modLoaded` 字段（字符串或数组）。**包内共用**：爆炸配方也用。 */
    static List<String> modIds(JsonObject json) {
        if (!json.has("modLoaded")) {
            return List.of();
        }
        JsonElement el = json.get("modLoaded");
        var out = new ArrayList<String>();
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                out.add(e.getAsString());
            }
        } else {
            out.add(el.getAsString());
        }
        return out;
    }

    /** 序列化器：JSON / 网络两边都能走（数据包加载 + 客户端 JEI 用） */
    public static class Serializer implements RecipeSerializer<QianJiRecipe> {

        @Override
        public QianJiRecipe fromJson(ResourceLocation id, JsonObject json) {
            // mod 存在性门控：没装的模组专用变体 → 注册成"空数据"（JEI 与编码器都会跳过它），
            // 而不是抛异常或留一条半残配方
            for (String mod : modIds(json)) {
                if (!net.minecraftforge.fml.ModList.get().isLoaded(mod)) {
                    com.ae2addon.AE2Addon.LOGGER.info(
                            "[ae2addon] 千机配方 {} 跳过（未装 {}）", id, mod);
                    return new QianJiRecipe(id, new QianJiPatternData(
                            QianJiRecipe.MACHINE_ID, id.toString(), List.of(), List.of(), List.of()));
                }
            }
            String machine = json.has("machine")
                    ? json.get("machine").getAsString()
                    : QianJiRecipe.MACHINE_ID;
            var data = new QianJiPatternData(machine, id.toString(),
                    readInputs(json), readPrimary(json), readChanced(json));
            return new QianJiRecipe(id, data);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, QianJiRecipe recipe) {
            buf.writeNbt(recipe.data().toTag());
        }

        @Override
        public QianJiRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            var tag = buf.readNbt();
            QianJiPatternData data = tag == null
                    ? new QianJiPatternData(QianJiRecipe.MACHINE_ID, id.toString(), List.of(), List.of(), List.of())
                    : QianJiPatternData.fromTag(tag);
            return new QianJiRecipe(id, data);
        }
    }
}
