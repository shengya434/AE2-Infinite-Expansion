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
            source.sendFailure(Component.translatable("gui.ae2addon.cmd.player_only"));
            return 0;
        }
        ItemStack stack = player.getMainHandItem();

        if (stack.isEmpty()) {
            player.sendSystemMessage(Component.translatable("gui.ae2addon.cmd.hold_item"));
            return 0;
        }

        Item item = stack.getItem();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);

        player.sendSystemMessage(Component.translatable("gui.ae2addon.cmd.title"));

        // 注册名（id）
        player.sendSystemMessage(copyable(
                Component.translatable("gui.ae2addon.cmd.id_line", id),
                id.toString(),
                id
        ));

        // 所属 mod
        String mod = id.getNamespace();
        player.sendSystemMessage(copyable(
                Component.translatable("gui.ae2addon.cmd.mod_line", mod),
                mod,
                mod
        ));

        // 全部 tags
        List<String> tags = new ArrayList<>();
        item.builtInRegistryHolder().tags().forEach(tagKey -> tags.add(tagKey.location().toString()));
        // 排序方便阅读
        tags.sort(String::compareTo);

        if (tags.isEmpty()) {
            player.sendSystemMessage(Component.translatable("gui.ae2addon.cmd.tags_none"));
        } else {
            player.sendSystemMessage(Component.translatable("gui.ae2addon.cmd.tags_count", tags.size()));
            for (String tag : tags) {
                player.sendSystemMessage(copyable(
                        Component.translatable("gui.ae2addon.cmd.tag_line", tag),
                        tag,
                        tag
                ));
            }
        }

        // 流体支持：如果是流体桶则显示流体信息
        // (可选扩展：检测物品的流体能力)

        player.sendSystemMessage(Component.translatable("gui.ae2addon.cmd.divider"));
        return 1;
    }

    /** 生成可点击复制的消息（服务端构建 translatable，客户端本地化） */
    private static Component copyable(Component text, String copyValue, Object hoverArg) {
        return text.copy()
                .setStyle(Style.EMPTY
                        .withColor(ChatFormatting.WHITE)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copyValue))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("gui.ae2addon.cmd.copy_hover", hoverArg)
                                        .append(Component.translatable("gui.ae2addon.cmd.copy_suffix")))));
    }
}
