package com.ae2addon.network;

import com.ae2addon.AE2Addon;
import com.ae2addon.block.QianJiBE;
import com.ae2addon.gui.QianJiTerminalMenu;
import com.ae2addon.init.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 千机·样板终端的"取 / 放"动作（客户端 → 服务端，2026-09-21 v256）。
 * <p>
 * 终端的长列表原本**只能看**（sensei：「没有取出和插入样板的方式」）。这个包给它接上两个动作：
 * <ul>
 *   <li>{@code insert=false}（左键点某行）：把**那一行对应的槽位**里的样板取出来 → 进玩家背包（满则掉落）。</li>
 *   <li>{@code insert=true}（右键点某行）：把**手上/背包里的千机样板**放进**那一行所在的那台千机**（找第一个空槽）。</li>
 * </ul>
 * 插入一律走 {@link QianJiBE} 的样板处理器，所以"只收千机样板"那条规则自动生效
 * （规则在 handler 里，不靠调用方自觉）。
 */
public final class QianJiTerminalActionPacket {

    /** 目标千机坐标 */
    public final BlockPos pos;
    /** 目标槽位（取出时用；插入时忽略） */
    public final int slot;
    /** true = 插入，false = 取出 */
    public final boolean insert;

    public QianJiTerminalActionPacket(BlockPos pos, int slot, boolean insert) {
        this.pos = pos;
        this.slot = slot;
        this.insert = insert;
    }

    public static void encode(QianJiTerminalActionPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(msg.slot);
        buf.writeBoolean(msg.insert);
    }

    public static QianJiTerminalActionPacket decode(FriendlyByteBuf buf) {
        return new QianJiTerminalActionPacket(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(QianJiTerminalActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null) return;
            // 门槛：必须开着终端（菜单归属），且目标坐标上确实是一台千机
            if (!(player.containerMenu instanceof QianJiTerminalMenu)) return;
            if (!(player.level().getBlockEntity(msg.pos) instanceof QianJiBE qianji)) {
                player.displayClientMessage(Component.literal("§c那台千机不在了"), false);
                return;
            }
            var handler = qianji.getPatternHandler();

            if (!msg.insert) {
                // ── 取出：那一行对应的槽位 → 玩家背包 ──
                ItemStack taken = handler.extractItem(msg.slot, 1, false);
                if (taken.isEmpty()) {
                    player.displayClientMessage(Component.literal("§c那个样板槽是空的"), false);
                    return;
                }
                if (!player.getInventory().add(taken)) player.drop(taken, false);
                AE2Addon.LOGGER.info("[ae2addon][terminal] 取出样板槽 {} @ {}", msg.slot, msg.pos);
                // 2026-09-22 v286：这两条"已取出/已放入"的聊天栏提示去掉 ——
                // 界面里列表本来就会立刻变化（还会跳到那一行高亮），聊天栏纯属多话。
                return;
            }

            // ── 插入：手上优先，其次背包里找一张千机样板 ──
            var carried = player.containerMenu.getCarried();
            int fromInventory = -1;
            ItemStack source = ItemStack.EMPTY;
            if (!carried.isEmpty() && carried.getItem() == ModItems.QIAN_JI_PATTERN.get()) {
                source = carried;
            } else {
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    ItemStack s = player.getInventory().getItem(i);
                    if (!s.isEmpty() && s.getItem() == ModItems.QIAN_JI_PATTERN.get()) {
                        source = s;
                        fromInventory = i;
                        break;
                    }
                }
            }
            if (source.isEmpty()) {
                player.displayClientMessage(
                        Component.literal("§c手上和背包里都没有千机样板（放不进别的样板）"), false);
                return;
            }

            ItemStack moving = source.copy();
            moving.setCount(1);
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (!handler.getStackInSlot(slot).isEmpty()) continue;
                ItemStack rest = handler.insertItem(slot, moving, false);
                if (rest.isEmpty()) {
                    // **成功后**才扣来源（别先扣了再发现放不进）
                    if (fromInventory >= 0) {
                        player.getInventory().removeItem(fromInventory, 1);
                    } else {
                        carried.shrink(1);
                    }
                    AE2Addon.LOGGER.info("[ae2addon][terminal] 放入样板到槽 {} @ {}", slot, msg.pos);
                    // 2026-09-22 v286：放入成功的聊天栏提示去掉（列表会自己跳过去高亮）
                    // 让终端列表跳过去并高亮这个栏位（2026-09-22 v277）
                    sendFocus(player, msg.pos, slot);
                    return;
                }
            }
            player.displayClientMessage(Component.literal("§c那台千机的样板槽满了"), false);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 通知客户端"样板落在哪个栏位了"（列表跳转 + 高亮 3 秒，2026-09-22 v277）。
     * <p>
     * 玩家还开着终端菜单才发（关掉了就没必要）。
     */
    private static void sendFocus(net.minecraft.server.level.ServerPlayer player, BlockPos pos, int slot) {
        if (!(player.containerMenu instanceof QianJiTerminalMenu)) return;
        AE2Addon.NETWORK.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new QianJiTerminalFocusPacket(pos, slot));
    }
}
