package com.ae2addon.compat;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.ae2addon.AE2Addon;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Reflection only crosses optional-mod classes; Minecraft and Forge APIs are called directly. */
final class ForeignCompatSupport {
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ForeignCompatSupport() {}

    /** 配方类型 id 兜底用的正则（见 {@link #type}：有些 mod 把 toString 拼成了别的样子） */
    private static final java.util.regex.Pattern TYPE_ID =
            java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /**
     * 配方类型的注册名（{@code namespace:path}）。
     * <p>
     * ⚠ <b>2026-10-11 实机教训：不能用 {@code String.valueOf(recipe.getType())} 当 id。</b>
     * PneumaticCraft 的 {@code PneumaticCraftRecipeType} 在构造器里就把 {@code typeName} 拼成
     * {@code "PneumaticCraftRecipeType[" + id + "]"}（字节码是 {@code makeConcatWithConstants}，
     * 类里还有那个字面量），于是 `startsWith("pneumaticcraft:")` 永远为假 →
     * **PNC 的 8 个类型整批落进「提取失败」**（实机 scan：thermo_plant 24 / pressure_chamber 17 全红）。
     * <p>
     * 所以：**注册名优先**；注册表查不到时再从 {@code toString()} 里抠最后一个 {@code namespace:path}
     * （PNC 那种带方括号的也能救回来）。物品/流体注册名的同类坑见 {@code ForgeRegistries.getValue} 的注释。
     */
    static String type(Recipe<?> recipe) {
        try {
            var key = net.minecraftforge.registries.ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
            if (key != null) return key.toString();
        } catch (Throwable error) {
            warn("recipe type registry", error);
        }
        try {
            String raw = String.valueOf(recipe.getType());
            var matcher = TYPE_ID.matcher(raw);
            String last = null;
            while (matcher.find()) last = matcher.group();
            return last != null ? last : raw;
        } catch (Throwable error) {
            warn("recipe type", error);
            return "";
        }
    }

    /**
     * 配方**序列化器**的注册名（{@code namespace:path} = 配方 JSON 里写的 {@code "type"}）。
     * <p>
     * 为什么需要它：**一个 RecipeType 底下可以挂多个序列化器**。PneumaticCraft 的
     * {@code pressure_chamber_enchanting} / {@code _disenchanting} 就是这种 —— 它们的
     * {@code getType()} 仍是 {@code pneumaticcraft:pressure_chamber}（附魔/祛魔是
     * {@code PressureChamberRecipeImpl} 的子类），所以只按类型 id 做的「按设计排除」抓不到它们，
     * 会让这 2 条永远挂在「提取失败」列（2026-10-11 实机 scan 实测）。
     * 判"某类配方要不要管"时，**类型 id 与序列化器 id 都要看**。
     */
    static String serializer(Recipe<?> recipe) {
        try {
            var key = net.minecraftforge.registries.ForgeRegistries.RECIPE_SERIALIZERS
                    .getKey(recipe.getSerializer());
            return key != null ? key.toString() : "";
        } catch (Throwable error) {
            warn("recipe serializer", error);
            return "";
        }
    }

    static boolean isA(@Nullable Object value, String className) {
        if (value == null) return false;
        try {
            return Class.forName(className, false, value.getClass().getClassLoader()).isInstance(value);
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Throwable error) {
            warn(className, error);
            return false;
        }
    }

    @Nullable
    static Object field(@Nullable Object target, String name) {
        if (target == null) return null;
        for (Class<?> owner = target.getClass(); owner != null && owner != Object.class;
             owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // The field may be declared by a superclass.
            } catch (Throwable error) {
                warn(owner.getName() + "." + name, error);
                return null;
            }
        }
        warn(target.getClass().getName() + "." + name, new NoSuchFieldException(name));
        return null;
    }

    @Nullable
    static Object call(@Nullable Object target, String name) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true); // Some public methods are implemented by package-private classes.
            return method.invoke(target);
        } catch (Throwable error) {
            warn(target.getClass().getName() + "." + name, error);
            return null;
        }
    }

    static List<?> elements(@Nullable Object value) {
        if (value == null) return List.of();
        if (value instanceof Collection<?> collection) return new ArrayList<>(collection);
        if (value instanceof Iterable<?> iterable) {
            var out = new ArrayList<>();
            for (Object element : iterable) out.add(element);
            return out;
        }
        if (value.getClass().isArray()) {
            var out = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) out.add(Array.get(value, i));
            return out;
        }
        return List.of(value);
    }

    static int integer(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    static float decimal(@Nullable Object value, float fallback) {
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    static List<GenericStack> items(@Nullable Object value, long amount) {
        try {
            var out = new ArrayList<GenericStack>();
            if (value instanceof Ingredient ingredient) {
                if (ingredient.isEmpty()) return List.of();
                for (ItemStack stack : ingredient.getItems()) addItem(out, stack, amount);
            } else if (value instanceof Ingredient.Value ingredientValue) {
                for (ItemStack stack : ingredientValue.getItems()) addItem(out, stack, amount);
            } else if (value instanceof ItemStack stack) {
                addItem(out, stack, amount);
            } else {
                for (Object candidate : elements(value)) {
                    if (candidate instanceof ItemStack stack) addItem(out, stack, amount);
                }
            }
            return List.copyOf(out);
        } catch (Throwable error) {
            warn("item options", error);
            return List.of();
        }
    }

    private static void addItem(List<GenericStack> out, ItemStack stack, long amount) {
        if (stack == null || stack.isEmpty()) return;
        GenericStack option = new GenericStack(AEItemKey.of(stack),
                amount > 0 ? amount : Math.max(1, stack.getCount()));
        for (GenericStack existing : out) {
            if (existing.what().equals(option.what()) && existing.amount() == option.amount()) return;
        }
        out.add(option);
    }

    static List<GenericStack> fluids(@Nullable Object value, long amount) {
        var out = new ArrayList<GenericStack>();
        try {
            for (Object candidate : elements(value)) {
                if (candidate instanceof FluidStack fluid && !fluid.isEmpty()) {
                    var option = new GenericStack(AEFluidKey.of(fluid),
                            amount > 0 ? amount : Math.max(1, fluid.getAmount()));
                    if (out.stream().noneMatch(old -> old.what().equals(option.what())
                            && old.amount() == option.amount())) out.add(option);
                }
            }
        } catch (Throwable error) {
            warn("fluid options", error);
        }
        return List.copyOf(out);
    }

    @Nullable
    static GenericStack stack(@Nullable Object value) {
        try {
            if (value instanceof ItemStack item && !item.isEmpty())
                return new GenericStack(AEItemKey.of(item), Math.max(1, item.getCount()));
            if (value instanceof FluidStack fluid && !fluid.isEmpty())
                return new GenericStack(AEFluidKey.of(fluid), Math.max(1, fluid.getAmount()));
            if (value instanceof Block block) {
                ItemStack item = new ItemStack(block);
                return item.isEmpty() ? null : new GenericStack(AEItemKey.of(item), 1);
            }
        } catch (Throwable error) {
            warn("output stack", error);
        }
        return null;
    }

    static void warn(String member, Throwable error) {
        String key = member + ':' + error.getClass().getName();
        if (WARNED.add(key)) AE2Addon.LOGGER.warn("[ae2addon][qianji-compat] {}: {}", member, error.toString());
    }
}
