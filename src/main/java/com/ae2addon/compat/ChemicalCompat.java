package com.ae2addon.compat;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 化学品（Applied-Mekanistics）**零硬依赖**兼容层。
 * <p>
 * 2026-09-19 sensei：无限 xxx 元件要支持化学品。做法与其它 compat 一致：
 * 只按**注册名**取，取不到就当这个扩展没装 —— 不 import 对方的类，
 * 对方缺席时我们照常启动（技能书里的既有规矩）。
 * <p>
 * 涉及的注册名：
 * <ul>
 *   <li>键类型 {@code appmek:chemical}（`AEKeyTypes.get(id)`；取不到返回 null）</li>
 *   <li>外壳物品 {@code appmek:chemical_cell_housing}</li>
 * </ul>
 * ⚠ 这两个 id 是从对方 jar 的 lang/model 文件名推出来的（`item.appmek.chemical_cell_housing`、
 * `assets/appmek/models/item/chemical_cell_housing.json`）。万一是别的 id，
 * 启动日志里那行"已注册的 AE 键类型"会直接把真实 id 摊出来（见 {@code AE2Addon} 的启动日志）。
 */
public final class ChemicalCompat {

    private static final ResourceLocation CHEMICAL_TYPE_ID =
            new ResourceLocation("appmek", "chemical");
    private static final ResourceLocation CHEMICAL_HOUSING_ID =
            new ResourceLocation("appmek", "chemical_cell_housing");

    /** 缓存（-1 未查 / 0 无 / 1 有）：键类型与外壳要么都在，要么都不在 */
    private static volatile int available = -1;
    private static volatile AEKeyType chemicalType;
    private static volatile Item chemicalHousing;

    private ChemicalCompat() {
    }

    private static void probe() {
        if (available >= 0) {
            return;
        }
        chemicalType = AEKeyTypes.get(CHEMICAL_TYPE_ID);
        Item housing = BuiltInRegistries.ITEM.get(CHEMICAL_HOUSING_ID);
        chemicalHousing = (housing == null || housing == Items.AIR) ? null : housing;
        available = (chemicalType != null) ? 1 : 0;
    }

    /** 装了 Applied-Mekanistics（且键类型 id 与我们的猜测一致） */
    public static boolean available() {
        probe();
        return available == 1;
    }

    /** 化学品键类型（未装返回 null） */
    public static AEKeyType chemicalType() {
        probe();
        return chemicalType;
    }

    /** 这个 key 是化学品吗（用于精华类型判定与配方外壳匹配） */
    public static boolean isChemical(AEKey key) {
        AEKeyType type = chemicalType();
        return key != null && type != null && type.equals(key.getType());
    }

    /** 化学品元件外壳物品（未装返回 null） */
    public static Item chemicalHousing() {
        probe();
        return chemicalHousing;
    }

    /** 诊断用：把探测结果写成一行（挂到启动日志里，便于核对 id 猜得对不对） */
    public static String describe() {
        probe();
        return "化学品兼容: 键类型 " + CHEMICAL_TYPE_ID + "=" + (chemicalType == null ? "未找到" : "ok")
                + "，外壳 " + CHEMICAL_HOUSING_ID + "=" + (chemicalHousing == null ? "未找到" : "ok");
    }

    /**
     * 取一个"代表性的化学品键"（Mekanism 氧气 / 氢气 / 蒸汽），取不到返回 null。
     * <p>
     * 用途：JEI 示意图与 {@code /ae2essence selftest} 需要一个真实化学品来演示 ——
     * 这里走**键类型的通用 NBT 解析**（{@code AEKeyType.loadKeyFromTag}），
     * 不 import Mekanism 的类（可选依赖纪律）。
     * <p>
     * ⚠ 2026-09-19 sensei 实测："我加 appmek 了，为啥自检说没加" —— 真相是
     * **键类型与外壳都探测到了**（启动日志 `appmek:chemical=ok`），
     * 挂在这一步：我原以为 NBT 是 {@code {id, amount}}，但 Mekanism 那边读不出来
     * （解析成 EMPTY → {@code MekanismKey.of(EMPTY)} → null）。
     * 现在**把几种可能的写法都试一遍**（`id` / `gas` / 带 `t` 子类型字节），
     * 并且失败时把尝试过程写进日志 —— 下次实测日志会直接告诉我们哪种对。
     */
    public static AEKey exampleChemicalKey() {
        for (String id : new String[]{"mekanism:oxygen", "mekanism:hydrogen", "mekanism:steam"}) {
            AEKey key = keyOf(id, 1L);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    /**
     * 按注册名 + 数量造一个化学品键。
     * <p>
     * ⚠⚠ **2026-09-19 实锤：别猜 NBT**。我一开始用 {@code AEKeyType.loadKeyFromTag} 试了
     * `id` / `gas` / `chemical` × 带不带 `t` 共 6 种写法，sensei 实测**全部返回空**
     * （日志：`化学品键构造失败 mekanism:nuclear_waste×102400000（尝试：id→空 id+t→空 gas→空 …）`，
     * 于是万能无限元件配方里那两条化学品输入整条被丢掉、JEI 上根本看不见）。
     * 反编译 appmek 的 {@code MekanismKeyType.loadKeyFromTag} 才看清：它按 `t` 字节分支调用
     * {@code GasStack/InfusionStack/PigmentStack/SlurryStack.readFromNBT(tag)} ——
     * 真正决定格式的是 Mekanism 那边，版本一变就废，不值得猜。
     * <p>
     * 现在改走**各家自己的构造链**（纯反射，零硬依赖，不 import 对方任何类）：
     * <pre>
     * MekanismAPI.&lt;gasRegistry&gt;().getValue(id) → new GasStack(gas, amount)
     *   → me.ramidzkh.mekae2.ae2.MekanismKey.of(ChemicalStack)
     * </pre>
     * 四类化学物（气体 / 灌注 / 颜料 / 浆液）依次试；Stack 的构造器按
     * 「第一个参数是 `mekanism.api.providers.*` 接口、第二个是 long」来挑 —— 不写死接口名。
     *
     * @return 造不出来返回 null（并写一行日志说明卡在哪）
     */
    public static AEKey keyOf(String chemicalId, long amount) {
        if (chemicalType() == null || chemicalId == null || chemicalId.isEmpty()) {
            return null;
        }
        Method of = mekanismKeyOf();
        if (of == null) {
            return null;
        }
        try {
            ResourceLocation id = new ResourceLocation(chemicalId);
            Class<?> api = Class.forName("mekanism.api.MekanismAPI");
            Method getValue = Class.forName("net.minecraftforge.registries.IForgeRegistry")
                    .getMethod("getValue", ResourceLocation.class);
            for (String[] entry : CHEMICAL_TYPES) {
                Object registry;
                try {
                    registry = api.getMethod(entry[0]).invoke(null);
                } catch (Throwable t) {
                    continue;   // 这个版本没有这类化学物
                }
                if (registry == null) {
                    continue;
                }
                Object chemical = getValue.invoke(registry, id);
                if (chemical == null) {
                    continue;   // 不属于这一类 → 试下一类
                }
                Constructor<?> ctor = stackConstructor(entry[1]);
                if (ctor == null) {
                    continue;
                }
                Object stack = ctor.newInstance(chemical, amount);
                Object key = of.invoke(null, stack);
                if (key instanceof AEKey aeKey) {
                    return aeKey;
                }
            }
        } catch (Throwable t) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] 化学品键构造异常 {}×{}：{}", chemicalId, amount, t.toString());
            return null;
        }
        com.ae2addon.AE2Addon.LOGGER.warn(
                "[ae2addon] 化学品键构造失败 {}×{}：Mekanism 四个注册表里都查不到这个 id",
                chemicalId, amount);
        return null;
    }

    /** 四类化学物：{@code MekanismAPI} 的取注册表方法名 + 对应的 Stack 类名 */
    private static final String[][] CHEMICAL_TYPES = {
            {"gasRegistry", "mekanism.api.chemical.gas.GasStack"},
            {"infuseTypeRegistry", "mekanism.api.chemical.infuse.InfusionStack"},
            {"pigmentRegistry", "mekanism.api.chemical.pigment.PigmentStack"},
            {"slurryRegistry", "mekanism.api.chemical.slurry.SlurryStack"},
    };

    private static volatile Method mekKeyOf;
    private static volatile boolean mekKeyOfProbed;

    /** {@code me.ramidzkh.mekae2.ae2.MekanismKey.of(ChemicalStack)}（appmek 没装返回 null） */
    private static Method mekanismKeyOf() {
        if (!mekKeyOfProbed) {
            try {
                mekKeyOf = Class.forName("me.ramidzkh.mekae2.ae2.MekanismKey")
                        .getMethod("of", Class.forName("mekanism.api.chemical.ChemicalStack"));
            } catch (Throwable t) {
                mekKeyOf = null;
            }
            mekKeyOfProbed = true;
        }
        return mekKeyOf;
    }

    /** Stack 的 {@code (IProvider, long)} 构造器：按参数类型挑，不写死接口名 */
    private static Constructor<?> stackConstructor(String className) {
        try {
            for (Constructor<?> c : Class.forName(className).getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && p[1] == long.class
                        && p[0].getName().startsWith("mekanism.api.providers")) {
                    return c;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
