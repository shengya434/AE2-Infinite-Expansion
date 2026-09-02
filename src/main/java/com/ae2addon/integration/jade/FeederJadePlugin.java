package com.ae2addon.integration.jade;

import net.minecraft.nbt.CompoundTag;
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
 * 服务端算好文本行 → NBT → 客户端渲染。
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

    // ── 服务端：蓄水池明细 → NBT ──

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
                    lines.add(0, "§e蓄水池 §7" + host.reservoirSummary()[0] + " 种 / 合计 "
                            + host.reservoirSummary()[1]);
                    data.putString(LINES_KEY, String.join("\n", lines));
                }
            };

    // ── 客户端：渲染文本行 ──

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
                    for (String line : data.getString(LINES_KEY).split("\n")) {
                        if (!line.isEmpty()) {
                            tooltip.add(Component.literal(line));
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
