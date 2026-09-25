package com.ae2addon.init;

import com.ae2addon.AE2Addon;
import com.ae2addon.recipe.EssenceToCellRecipe;
import com.ae2addon.recipe.ExplosionRecipe;
import com.ae2addon.recipe.QianJiRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 自定义配方注册。
 * <p>
 * <ul>
 *   <li>{@code essence_to_cell}：无限精华 + ME 元件外壳 → 无限 xxx 元件（特殊配方）</li>
 *   <li>{@code qianji_recipe}：**千机自用配方**（2026-09-19）——数据包定义、千机自己执行，
 *       支持流体/化学品/NBT 与百万级数量，见 {@link QianJiRecipe}</li>
 *   <li>{@code explosion_recipe}：**爆炸配方**（2026-09-19 sensei 方案 A）——东西扔地上炸一下变产物，
 *       见 {@link ExplosionRecipe}（AE2 原生 {@code ae2:transform} 不支持数量，所以自研）</li>
 * </ul>
 */
public class ModRecipes {

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, AE2Addon.MODID);

    public static final RegistryObject<RecipeSerializer<EssenceToCellRecipe>> ESSENCE_TO_CELL =
            SERIALIZERS.register("essence_to_cell", EssenceToCellRecipe.Serializer::new);

    public static final RegistryObject<RecipeSerializer<QianJiRecipe>> QIANJI_RECIPE =
            SERIALIZERS.register("qianji_recipe", QianJiRecipe.Serializer::new);

    public static final RegistryObject<RecipeSerializer<ExplosionRecipe>> EXPLOSION_RECIPE =
            SERIALIZERS.register("explosion_recipe", ExplosionRecipe.Serializer::new);

    /**
     * 配方类型注册器。
     * <p>
     * ⚠⚠ **2026-09-19 实锤教训**：一开始我用的是官方的 `RecipeType.register("ae2addon:qianji_recipe")`
     * （它在类加载时就往 `BuiltInRegistries.RECIPE_TYPE` **直接写**）。结果是**启动即崩**：
     * 这行代码在 `AE2Addon` 构造阶段才第一次执行，而那个时刻内置注册表**已经冻结**，
     * `NamespacedWrapper` 直接抛异常 → `ExceptionInInitializerError` → 整包加载失败
     * （crash-report: `com.ae2addon.init.ModRecipes.<clinit>(ModRecipes.java:47)`）。
     * 正确姿势就是跟别的注册表一样走 {@link DeferredRegister}：Forge 在 `RegisterEvent`
     * 阶段替我们注册，那时注册表还没冻结。
     * <p>
     * 1.20.1 的 {@link RecipeType} 是接口、没有公开实现类（官方那个 `register` 内部也是 new 一个匿名类），
     * 所以这里自己写一个匿名实现；`toString()` 与官方生成的行为一致（都返回 `命名空间:路径`）。
     */
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(ForgeRegistries.Keys.RECIPE_TYPES, AE2Addon.MODID);

    /** 千机自用配方类型 */
    public static final RegistryObject<RecipeType<QianJiRecipe>> QIANJI_TYPE =
            RECIPE_TYPES.register("qianji_recipe", () -> new RecipeType<QianJiRecipe>() {
                @Override
                public String toString() {
                    return "ae2addon:qianji_recipe";
                }
            });

    /** 爆炸配方类型 */
    public static final RegistryObject<RecipeType<ExplosionRecipe>> EXPLOSION_TYPE =
            RECIPE_TYPES.register("explosion_recipe", () -> new RecipeType<ExplosionRecipe>() {
                @Override
                public String toString() {
                    return "ae2addon:explosion_recipe";
                }
            });
}
