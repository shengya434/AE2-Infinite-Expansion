package com.ae2addon.compat;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ars Nouveau（新生魔艺）配方兼容层 —— 反射实现，零硬依赖。
 * <p>
 * 2026-09-16 sensei 报「灌注室与附魔装置无法识别」；2026-09-17 全 mod 扫描又暴露四类：
 * <ul>
 *   <li><b>灌注室 / 附魔装置</b>（{@code ImbuementRecipe} / {@code EnchantingApparatusRecipe}）：
 *       标准 API 全空（{@code getResultItem} 恒为 {@code ItemStack.EMPTY}、{@code getIngredients} 没实现）
 *       → 只能读字段（见 {@link #inputSlots}）</li>
 *   <li><b>符文（glyph）81 条</b>：产物其实读得到（{@code getResultItem} 读 {@code output} 字段），
 *       缺的是**输入** —— 原料在 {@code inputs}（{@code List<Ingredient>}）字段里</li>
 *   <li><b>粉碎（crush）26 条</b>：{@code getResultItem} 返回常量 EMPTY，
 *       真数据在 {@code input}（Ingredient）+ {@code outputs}（{@code List<CrushOutput>}：
 *       {@code stack} / {@code chance} / {@code maxRange}）</li>
 *   <li><b>附魔（enchantment）96 条</b>：属于附魔装置家族（{@code EnchantmentRecipe extends
 *       EnchantingApparatusRecipe}），输入已经有了，产物是**附魔书**（{@code enchantment} + {@code level} 两个字段现造）</li>
 * </ul>
 * 已知不做：{@code caster_tome}（27 条，没有原料、产物是带法术 NBT 的书）、
 * {@code armor_upgrade} / {@code spell_write}（产物依赖输入物本身）。
 */
public final class ArsNouveauCompat {

    private static final String PREFIX = "com.hollingsworth.arsnouveau.";
    private static final String IMBUEMENT =
            "com.hollingsworth.arsnouveau.common.crafting.recipes.ImbuementRecipe";
    private static final String APPARATUS =
            "com.hollingsworth.arsnouveau.api.enchanting_apparatus.EnchantingApparatusRecipe";
    private static final String GLYPH =
            "com.hollingsworth.arsnouveau.common.crafting.recipes.GlyphRecipe";
    private static final String CRUSH =
            "com.hollingsworth.arsnouveau.common.crafting.recipes.CrushRecipe";
    private static final String ENCHANTMENT =
            "com.hollingsworth.arsnouveau.api.enchanting_apparatus.EnchantmentRecipe";
    private static final String REACTIVE_ENCHANTMENT =
            "com.hollingsworth.arsnouveau.api.enchanting_apparatus.ReactiveEnchantmentRecipe";

    private static final int MAX_OPTIONS = 32;

    private static final Map<Class<?>, Boolean> IS_FIELD_ONLY = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> IS_GLYPH = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> IS_CRUSH = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> IS_ENCHANTMENT = new ConcurrentHashMap<>();

    /** 粉碎产物：物品栈 + 几率（{@code CrushOutput} 的 stack/chance 字段） */
    public record ChancedOutput(GenericStack stack, float chance) {}

    private ArsNouveauCompat() {}

    /** 是不是「标准 API 拿不到东西、必须读字段」的那两类配方（灌注室 / 附魔装置，含子类） */
    public static boolean isFieldOnlyRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return IS_FIELD_ONLY.computeIfAbsent(recipe.getClass(), c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                String name = k.getName();
                if (IMBUEMENT.equals(name) || APPARATUS.equals(name)) return true;
            }
            return false;
        });
    }

    /** 是不是 Ars 的配方（诊断/日志用） */
    public static boolean isArsRecipe(@Nullable Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().startsWith(PREFIX);
    }

    /** 真产物：附魔装置读 {@code result}、灌注室读 {@code output}（getResultItem 恒为 EMPTY，别用） */
    public static ItemStack output(@Nullable Recipe<?> recipe) {
        if (recipe == null) return ItemStack.EMPTY;
        Object raw = readField(recipe, "result");
        if (raw instanceof ItemStack stack && !stack.isEmpty()) return stack;
        raw = readField(recipe, "output");
        return raw instanceof ItemStack stack && !stack.isEmpty() ? stack : ItemStack.EMPTY;
    }

    /** 魔源消耗：附魔装置字段 {@code sourceCost}、灌注室字段 {@code source}（字段名不一致，都要试）；没有 → 0 */
    public static int sourceCost(@Nullable Recipe<?> recipe) {
        if (recipe == null) return 0;
        Object raw = readField(recipe, "sourceCost");
        if (raw instanceof Number n && n.intValue() > 0) return n.intValue();
        raw = readField(recipe, "source");
        return raw instanceof Number n && n.intValue() > 0 ? n.intValue() : 0;
    }

    /**
     * 输入槽：中心物品（附魔装置的 {@code reagent} / 灌注室的 {@code input}）
     * + 周围基座物品（{@code pedestalItems}，两者都是 {@code List<Ingredient>}）。
     * <p>
     * 这些输入在合成时都被消耗（附魔装置的 {@code keepNbtOfReagent} 只保留 NBT，不是「不消耗」），
     * 所以一律按消耗品处理。
     */
    public static List<List<GenericStack>> inputSlots(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;
        addIngredientSlot(slots, readField(recipe, "reagent"));
        addIngredientSlot(slots, readField(recipe, "input"));
        Object pedestals = readField(recipe, "pedestalItems");
        if (pedestals instanceof Iterable<?> it) {
            for (Object element : it) addIngredientSlot(slots, element);
        }
        return slots;
    }

    // ── 符文（glyph）：产物读得到，缺的是原料 ──

    public static boolean isGlyph(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return IS_GLYPH.computeIfAbsent(recipe.getClass(), c -> GLYPH.equals(c.getName()));
    }

    /** 符文配方的原料：{@code inputs}（{@code List<Ingredient>}，就是 JSON 的 {@code inputItems}） */
    public static List<List<GenericStack>> glyphInputSlots(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;
        Object inputs = readField(recipe, "inputs");
        if (inputs instanceof Iterable<?> it) {
            for (Object element : it) addIngredientSlot(slots, element);
        }
        return slots;
    }

    // ── 粉碎（crush）：getResultItem 是常量 EMPTY，数据全在字段里 ──

    public static boolean isCrush(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return IS_CRUSH.computeIfAbsent(recipe.getClass(), c -> CRUSH.equals(c.getName()));
    }

    /** 粉碎输入（{@code input} 是单个 Ingredient，如 {@code #forge:stone}） */
    public static List<GenericStack> crushInputOptions(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        Object input = readField(recipe, "input");
        if (!(input instanceof Ingredient ingredient)) return List.of();
        return optionsOf(ingredient);
    }

    /** 粉碎产出：{@code outputs}（{@code List<CrushOutput>}，元素字段 stack/chance/maxRange） */
    public static List<ChancedOutput> crushOutputs(@Nullable Recipe<?> recipe) {
        var out = new ArrayList<ChancedOutput>();
        if (recipe == null) return out;
        Object outputs = readField(recipe, "outputs");
        if (!(outputs instanceof Iterable<?> it)) return out;
        for (Object element : it) {
            Object stack = readField(element, "stack");
            if (!(stack instanceof ItemStack itemStack) || itemStack.isEmpty()) continue;
            Object chance = readField(element, "chance");
            float p = chance instanceof Number n ? n.floatValue() : -1f;
            out.add(new ChancedOutput(new GenericStack(AEItemKey.of(itemStack),
                    Math.max(1, itemStack.getCount())), p));
        }
        return out;
    }

    // ── 附魔（enchantment / reactive_enchantment）：产物是现造的附魔书 ──

    /** 是不是附魔书那两类（{@code EnchantmentRecipe} 及其子类 {@code ReactiveEnchantmentRecipe}） */
    public static boolean isEnchantmentRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return IS_ENCHANTMENT.computeIfAbsent(recipe.getClass(), c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                String name = k.getName();
                if (ENCHANTMENT.equals(name) || REACTIVE_ENCHANTMENT.equals(name)) return true;
            }
            return false;
        });
    }

    /**
     * 附魔配方的产物 = 一本**附了指定魔咒的附魔书**（{@code enchantment} + {@code level} 两个字段）。
     * 原版 {@code ItemStack#enchant(Enchantment, int)} 会把魔咒写进 NBT，{@code AEItemKey} 认 NBT ✓。
     */
    public static ItemStack enchantmentBook(@Nullable Recipe<?> recipe) {
        if (recipe == null) return ItemStack.EMPTY;
        Object ench = readField(recipe, "enchantment");
        // ⚠2026-09-17 字段审计修正：真名是 enchantLevel（原来读 level → 恒为 null → 锋利 V 会退化成 1 级）
        Object level = readField(recipe, "enchantLevel");
        if (!(level instanceof Number)) level = readField(recipe, "level");   // 兼容别的命名
        if (!(ench instanceof net.minecraft.world.item.enchantment.Enchantment enchantment)) return ItemStack.EMPTY;
        int lvl = level instanceof Number n ? Math.max(1, n.intValue()) : 1;
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        try {
            book.enchant(enchantment, lvl);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;   // 造不出来就别硬来（宁可少一条样板，也不能给错产物）
        }
        return book;
    }

    /**
     * 附魔配方的**书**输入（2026-09-17 审计补）。
     * <p>
     * 字节码实证：{@code assemble} 先拿装置里的 reagent 物品与 {@code Items.BOOK} /
     * {@code Items.ENCHANTED_BOOK} 比对，成功才产出附魔书；而配方数据里的 reagent 字段是**空的**
     * → 千机原来只报基座物品、不索取书，等于白送一本附魔书。这里补一个「书」输入槽。
     */
    public static List<GenericStack> enchantmentBookInput() {
        var options = new ArrayList<GenericStack>();
        options.add(new GenericStack(AEItemKey.of(new ItemStack(Items.BOOK)), 1));
        options.add(new GenericStack(AEItemKey.of(new ItemStack(Items.ENCHANTED_BOOK)), 1));
        return List.copyOf(options);
    }

    // ── 反射工具 ──

    private static void addIngredientSlot(List<List<GenericStack>> slots, @Nullable Object value) {
        if (!(value instanceof Ingredient ingredient)) return;
        var options = optionsOf(ingredient);
        if (!options.isEmpty()) slots.add(options);
    }

    private static List<GenericStack> optionsOf(Ingredient ingredient) {
        var options = new ArrayList<GenericStack>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            options.add(new GenericStack(AEItemKey.of(stack), 1));
            if (options.size() >= MAX_OPTIONS) break;
        }
        return List.copyOf(options);
    }

    @Nullable
    private static Object readField(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                // 继续往上找
            }
        }
        return null;
    }
}
