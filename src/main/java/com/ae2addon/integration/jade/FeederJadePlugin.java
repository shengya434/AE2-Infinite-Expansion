package com.ae2addon.integration.jade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.parts.IPartHost;

import com.ae2addon.AE2Addon;
import com.ae2addon.block.FeederHost;
import com.ae2addon.block.InfiniteInterfaceBlock;

import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * 玉（Jade）集成（2026-09-03 sensei）：显示供料站蓄水池全部条目
 * （物品/流体/化学物每种都列，不截断）+ 面板 part 同样支持。
 * 服务端构造 translatable Component → JSON 序列化进 NBT → 客户端反序列化渲染，
 * 语言在客户端解析（中/英随游戏语言，2026-09-03 i18n）。
 */
@WailaPlugin(AE2Addon.MODID)
public class FeederJadePlugin implements IWailaPlugin {

    private static final String LINES_KEY = "ae2addon:lines";

    /** 从方块 BE 或 cable bus 的 part 找供料站宿主。 */
    private static FeederHost findHost(BlockEntity be) {
        if (be instanceof FeederHost fh) {
            return fh;
        }
        if (be instanceof IPartHost host) {
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                var part = host.getPart(d);
                if (part instanceof FeederHost fh) {
                    return fh;
                }
            }
        }
        return null;
    }

    // ── 服务端：蓄水池明细 → translatable Component JSON 列表 → NBT ──

    private static final IServerDataProvider<BlockAccessor> SERVER_PROVIDER =
            new IServerDataProvider<>() {
                @Override
                public ResourceLocation getUid() {
                    return new ResourceLocation(AE2Addon.MODID, "feeder_data");
                }

                @Override
                public void appendServerData(CompoundTag data, BlockAccessor accessor) {
                    BlockEntity be = accessor.getBlockEntity();
                    FeederHost host = be == null ? null : findHost(be);
                    if (host == null) {
                        return;
                    }
                    var lines = host.reservoirTooltipLines();
                    var summary = host.reservoirSummary();
                    lines.add(0, Component.translatable("ae2addon.jade.header",
                            summary[0], summary[1]));
                    ListTag list = new ListTag();
                    for (Component c : lines) {
                        list.add(StringTag.valueOf(Component.Serializer.toJson(c)));
                    }
                    data.put(LINES_KEY, list);
                }
            };

    // ── 客户端：反序列化渲染（translatable 按客户端语言解析） ──

    private static final IBlockComponentProvider CLIENT_PROVIDER =
            new IBlockComponentProvider() {
                @Override
                public ResourceLocation getUid() {
                    return new ResourceLocation(AE2Addon.MODID, "feeder_view");
                }

                @Override
                public void appendTooltip(ITooltip tooltip, BlockAccessor accessor,
                        IPluginConfig config) {
                    CompoundTag data = accessor.getServerData();
                    if (data == null || !data.contains(LINES_KEY)) {
                        return;
                    }
                    ListTag list = data.getList(LINES_KEY, Tag.TAG_STRING);
                    for (Tag t : list) {
                        Component c = Component.Serializer.fromJson(t.getAsString());
                        if (c != null) {
                            tooltip.add(c);
                        }
                    }
                }
            };

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(SERVER_PROVIDER,
                com.ae2addon.block.InfiniteInterfaceBE.class);
        registration.registerBlockDataProvider(SERVER_PROVIDER,
                appeng.blockentity.networking.CableBusBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(CLIENT_PROVIDER, InfiniteInterfaceBlock.class);
        registration.registerBlockComponent(CLIENT_PROVIDER,
                appeng.block.networking.CableBusBlock.class);
    }
}
