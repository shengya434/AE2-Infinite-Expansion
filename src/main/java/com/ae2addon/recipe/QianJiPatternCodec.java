package com.ae2addon.recipe;

import appeng.api.stacks.GenericStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

/**
 * 千机样板与 AE2 样板之间的桥（2026-09-15，sensei 的 B 方案）。
 * <p>
 * ME 样板编码器（AE2 的 Pattern Encoding Terminal）编码时，若这条配方属于千机可处理的归一化配方，
 * 我们就把「主产物 + 概率产出元数据」写进那张 AE2 处理样板的 NBT：
 * <pre>
 * in  / out : AE2 原生输入/输出（out 重写为**只含主产物** → AE2 永不为概率副产等待）
 * ***       : 我们自己的元数据（几率表 + 来源配方 id），千机读到就精确执行
 * </pre>
 * 编码发生在服务端（菜单逻辑），所以当前世界从 {@link ServerLifecycleHooks} 取，不需要额外上下文。
 */
public final class QianJiPatternCodec {

    private QianJiPatternCodec() {}

    /** 编码时调用：命中千机配方则返回我们的数据，否则 null（保持 AE2 原生行为） */
    @Nullable
    public static QianJiPatternData matchCurrent(@Nullable GenericStack[] inputs,
                                                 @Nullable GenericStack[] outputs) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;   // 编码在服务端菜单里进行；客户端预览时不处理
        return QianJiRecipeModel.match(server.getRecipeManager(), server.registryAccess(), inputs, outputs);
    }

    /** 当前服务端（编码时无 Level 上下文，但服务端本身能提供配方表与注册表） */
    @Nullable
    public static net.minecraft.server.MinecraftServer currentServer() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    /** 任何样板（自有样板物品 或 AE2 样板 + 我们的 NBT）上读到的千机数据 */
    @Nullable
    public static QianJiPatternData dataOf(ItemStack stack) {
        return QianJiPatternData.of(stack);
    }

    /** 把「主产物（覆盖 out）+ 元数据」写进样板 NBT */
    public static void attachTo(CompoundTag tag, QianJiPatternData data) {
        var primaryList = new ListTag();
        for (var out : data.primary()) {
            primaryList.add(GenericStack.writeTag(out.stack()));
        }
        tag.put("out", primaryList);
        tag.put(QianJiPatternData.TAG_KEY, data.toTag());
    }
}
