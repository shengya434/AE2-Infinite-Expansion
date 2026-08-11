package com.ae2addon.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 专属指令：/ae2info
 * <p>
 * 查看手持物品的信息：注册名(id)、所属 mod、全部 tags。
 * 每条信息都可以点击复制到剪贴板。
 */
public class AE2InfoCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("ae2info")
                .executes(AE2InfoCommand::execute)
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c该指令只能由玩家执行"));
            return 0;
        }
        ItemStack stack = player.getMainHandItem();

        if (stack.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c✗ 请手持一个物品"));
            return 0;
        }

        Item item = stack.getItem();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);

        player.sendSystemMessage(Component.literal("§e§l═══ 物品信息 ═══"));

        // 注册名（id）
        player.sendSystemMessage(copyable(
                "§bID: §f" + id,
                id.toString(),
                "§7点击复制: " + id
        ));

        // 所属 mod
        String mod = id.getNamespace();
        player.sendSystemMessage(copyable(
                "§dMOD: §f" + mod,
                mod,
                "§7点击复制: " + mod
        ));

        // 全部 tags
        List<String> tags = new ArrayList<>();
        item.builtInRegistryHolder().tags().forEach(tagKey -> tags.add(tagKey.location().toString()));
        // 排序方便阅读
        tags.sort(String::compareTo);

        if (tags.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7Tags: §8(无)"));
        } else {
            player.sendSystemMessage(Component.literal("§aTags: §7共 " + tags.size() + " 个，点击复制 ↓"));
            for (String tag : tags) {
                player.sendSystemMessage(copyable(
                        "§7  - §f" + tag,
                        tag,
                        "§7点击复制: " + tag
                ));
            }
        }

        // 流体支持：如果是流体桶则显示流体信息
        // (可选扩展：检测物品的流体能力)

        player.sendSystemMessage(Component.literal("§8────────────────"));
        return 1;
    }

    /** 生成可点击复制的消息 */
    private static Component copyable(String text, String copyValue, String hover) {
        return Component.literal(text)
                .setStyle(Style.EMPTY
                        .withColor(ChatFormatting.WHITE)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copyValue))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal(hover + "\n§7(点击复制)"))));
    }
}
