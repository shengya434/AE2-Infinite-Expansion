package com.ae2addon.network;

import com.ae2addon.init.ModItems;
import com.ae2addon.recipe.QianJiPatternData;
import com.ae2addon.recipe.QianJiRecipeModel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 编码请求（客户端 → 服务端）：把某个配方 id 编成千机配方样板。
 * <p>
 * 由 JEI 页「千机·自用配方」点击编码发出（2026-09-15）。
 * 成本与 AE2 编码样板一致：创造模式免费，否则消耗 1 个空白样板（{@code ae2:blank_pattern}）。
 */
public class QianJiPatternPacket {

    private final String recipeId;

    public QianJiPatternPacket(String recipeId) {
        this.recipeId = recipeId;
    }

    public static void encode(QianJiPatternPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.recipeId);
    }

    public static QianJiPatternPacket decode(FriendlyByteBuf buf) {
        return new QianJiPatternPacket(buf.readUtf());
    }

    public static void handle(QianJiPatternPacket msg, Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getDirection().getReceptionSide().isServer()) {
            ctx.get().enqueueWork(() -> {
                var player = ctx.get().getSender();
                if (player == null) return;
                var level = player.level();

                ResourceLocation id = ResourceLocation.tryParse(msg.recipeId);
                if (id == null) return;
                var found = level.getRecipeManager().byKey(id);
                if (found.isEmpty()) {
                    player.displayClientMessage(Component.literal("§c找不到配方: " + msg.recipeId), false);
                    return;
                }
                QianJiPatternData data = QianJiRecipeModel.fromRecipe(found.get(), level);
                if (data == null || data.primary().isEmpty()) {
                    player.displayClientMessage(Component.literal("§c该配方提取不出主产物，无法编码"), false);
                    return;
                }

                // 成本：与 AE2 编码样板一致（创造免费，否则消耗 1 个空白样板）
                if (!player.isCreative()) {
                    ItemStack blank = findBlankPattern(player);
                    if (blank.isEmpty()) {
                        player.displayClientMessage(
                                Component.literal("§c需要一个空白样板（ae2:blank_pattern）"), false);
                        return;
                    }
                    blank.shrink(1);
                }

                ItemStack pattern = new ItemStack(ModItems.QIAN_JI_PATTERN.get());
                data.writeTo(pattern);
                if (!player.getInventory().add(pattern)) {
                    player.drop(pattern, false);
                }
                player.displayClientMessage(Component.literal("§a已编码千机样板: §7" + msg.recipeId
                        + " §8(主产物 " + data.primary().size()
                        + " / 概率产出 " + data.chanced().size() + ")"), false);
            });
        }
        ctx.get().setPacketHandled(true);
    }

    private static ItemStack findBlankPattern(net.minecraft.world.entity.player.Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getKey(stack.getItem())).equals("ae2:blank_pattern")) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
