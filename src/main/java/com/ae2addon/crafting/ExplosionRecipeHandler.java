package com.ae2addon.crafting;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2addon.AE2Addon;
import com.ae2addon.init.ModRecipes;
import com.ae2addon.recipe.ExplosionRecipe;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 爆炸配方处理器（2026-09-19 sensei 方案 A）。
 * <p>
 * 挂在 {@link ExplosionEvent.Detonate}（爆炸**即将**破坏方块/实体之前触发）：
 * 把爆炸范围内的 {@link ItemEntity} 收集起来，按**物品个数跨堆叠统计**，
 * 满足某条 {@code ae2addon:explosion_recipe} 就扣除对应数量、在掉落物位置吐出产物。
 * <p>
 * 与 AE2 原生 {@code ae2:transform} 的区别（为什么不用它，见 {@link ExplosionRecipe} 的类注释）：
 * 原生是「一条原料 = 一个掉落物实体、只取 1 个物品」；我们是「按个数统计、跨堆叠扣除」，
 * 所以清单第 1 行的 64/64/16 才表达得出来。
 * <p>
 * 规则：
 * <ul>
 *   <li>一次爆炸**只做一条**配方（按注册顺序第一个满足的），避免一份料被多条配方重复吃</li>
 *   <li>被吃空的掉落物实体直接 {@code discard} 并从受影响列表里移除（不再掉落残渣）</li>
 *   <li>产物数量 > 64 会拆成多个掉落物实体</li>
 *   <li>整段 try/catch：爆炸里出任何意外都不能把爆炸本身带崩</li>
 * </ul>
 */
public final class ExplosionRecipeHandler {

    private static final String TAG = "[ae2addon][爆炸]";

    private ExplosionRecipeHandler() {
    }

    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<Entity> affected = event.getAffectedEntities();
        if (affected == null || affected.isEmpty()) {
            return;
        }
        var items = new ArrayList<ItemEntity>();
        for (Entity e : affected) {
            if (e instanceof ItemEntity ie && !ie.getItem().isEmpty()) {
                items.add(ie);
            }
        }
        if (items.isEmpty()) {
            return;
        }

        List<ExplosionRecipe> recipes;
        try {
            recipes = level.getRecipeManager().getAllRecipesFor(ModRecipes.EXPLOSION_TYPE.get());
        } catch (Throwable t) {
            return;
        }
        if (recipes == null || recipes.isEmpty()) {
            return;
        }

        for (ExplosionRecipe recipe : recipes) {
            try {
                if (tryApply(level, recipe, items, affected)) {
                    return;   // 一次爆炸只做一条
                }
            } catch (Throwable t) {
                AE2Addon.LOGGER.warn("{} 配方 {} 执行异常：{}", TAG, recipe.getId(), t.toString());
            }
        }
    }

    /** 试一条配方；成功（已扣料已产出）返回 true */
    private static boolean tryApply(ServerLevel level, ExplosionRecipe recipe,
                                    List<ItemEntity> items, List<Entity> affected) {
        if (recipe.result() == null || recipe.inputs().isEmpty()) {
            return false;
        }

        // 1) 统计可用量（AEKey 精确匹配，含 NBT）
        Map<AEKey, Long> have = new HashMap<>();
        for (ItemEntity ie : items) {
            AEKey key = AEItemKey.of(ie.getItem());
            if (key == null) {
                continue;
            }
            have.merge(key, (long) ie.getItem().getCount(), Long::sum);
        }
        for (GenericStack in : recipe.inputs()) {
            Long got = have.get(in.what());
            if (got == null || got < in.amount()) {
                return false;   // 物料不足 → 这条不成立
            }
        }

        // 2) 扣料（跨堆叠、跨实体）
        var consumedOrder = new ArrayList<ItemEntity>();
        for (GenericStack in : recipe.inputs()) {
            long need = in.amount();
            for (ItemEntity ie : items) {
                if (need <= 0) {
                    break;
                }
                ItemStack stack = ie.getItem();
                if (stack.isEmpty() || !in.what().equals(AEItemKey.of(stack))) {
                    continue;
                }
                int take = (int) Math.min(need, stack.getCount());
                stack.shrink(take);
                need -= take;
                if (!consumedOrder.contains(ie)) {
                    consumedOrder.add(ie);
                }
                if (stack.isEmpty()) {
                    ie.discard();
                    affected.remove(ie);
                }
            }
            if (need > 0) {
                return false;   // 理论上到不了这里（第 1 步已校验），留个保险
            }
        }

        // 3) 产出：落在第一个被吃掉的掉落物位置
        if (!(recipe.result().what() instanceof AEItemKey outKey)) {
            AE2Addon.LOGGER.warn("{} {} 的产物不是物品，跳过（爆炸只能吐物品）", TAG, recipe.getId());
            return false;
        }
        ItemEntity anchor = consumedOrder.isEmpty() ? null : consumedOrder.get(0);
        if (anchor == null) {
            return false;
        }
        double x = anchor.getX();
        double y = anchor.getY();
        double z = anchor.getZ();
        long remaining = recipe.result().amount();
        while (remaining > 0) {
            int n = (int) Math.min(64L, remaining);
            ItemStack out = outKey.toStack(n);
            out.setCount(n);
            level.addFreshEntity(new ItemEntity(level, x + 0.5, y + 0.5, z + 0.5, out));
            remaining -= n;
        }

        AE2Addon.LOGGER.info("{} 触发 {}：{} → {}×{}（吃掉 {} 个掉落物实体）",
                TAG, recipe.getId(), describeInputs(recipe),
                recipe.result().what().getDisplayName().getString(), recipe.result().amount(),
                consumedOrder.size());
        return true;
    }

    private static String describeInputs(ExplosionRecipe recipe) {
        var sb = new StringBuilder();
        for (GenericStack in : recipe.inputs()) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(in.what().getDisplayName().getString()).append('×').append(in.amount());
        }
        return sb.toString();
    }
}
