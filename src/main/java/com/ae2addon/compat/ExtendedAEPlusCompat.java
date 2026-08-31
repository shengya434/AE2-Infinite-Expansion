package com.ae2addon.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * ExtendedAE+ 可选集成（2026-08-28 sensei）：
 * - 频道卡（channel_card）：接口无线连网（无线收发器主端），无需接线
 * - 虚拟合成卡（virtual_crafting_card）：最后一次发配瞬间结束任务
 * <p>
 * compileOnly 依赖（libs/extendedae_plus jar），运行时未装时短路。
 */
public final class ExtendedAEPlusCompat {

    private static boolean checked;
    private static boolean loaded;

    private ExtendedAEPlusCompat() {
    }

    public static boolean isLoaded() {
        if (!checked) {
            checked = true;
            loaded = ModList.get().isLoaded("extendedae_plus");
        }
        return loaded;
    }

    /** 频道卡物品（注册表查询，未装返回 null）。 */
    public static Item channelCard() {
        if (!isLoaded()) {
            return null;
        }
        return ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("extendedae_plus", "channel_card"));
    }

    /** 虚拟合成卡物品（注册表查询，未装返回 null）。 */
    public static Item virtualCraftingCard() {
        if (!isLoaded()) {
            return null;
        }
        return ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("extendedae_plus", "virtual_crafting_card"));
    }

    /**
     * 无线链路持有者：内部用 {@code Object} 惰性持有 WirelessSlaveLink。
     * 2026-09-01 sensei 的实例缺 extendedae_plus 启动崩溃（ModBlockEntities 注册 lambda
     * 加载 BE 类时字段类型直接解析 IWirelessEndpoint → NoClassDefFoundError）。
     * 字段类型改 Object 后，extendedae_plus 类只在方法体内引用 → 惰性加载，缺依赖不崩。
     */
    public static final class ChannelLink {

        /** WirelessSlaveLink（仅 isLoaded() 时非 null）。 */
        private Object link;

        /** 按卡刷新链路（无卡/无效卡自动断开）；卡栈为空也断开。 */
        public void update(com.ae2addon.block.InfiniteInterfaceBE be, net.minecraft.world.item.ItemStack cardStack) {
            if (!isLoaded()) {
                return;
            }
            long channel = -1;
            java.util.UUID owner = null;
            if (cardStack != null && !cardStack.isEmpty()) {
                channel = com.extendedae_plus.items.materials.ChannelCardItem.getChannel(cardStack);
                owner = com.extendedae_plus.items.materials.ChannelCardItem.getOwnerUUID(cardStack);
            }
            try {
                if (channel < 0) {
                    disconnect();
                    return;
                }
                if (link == null) {
                    var endpoint = new com.extendedae_plus.ae.wireless.endpoint.GenericNodeEndpointImpl(
                            () -> be, () -> be.getMainNode().getNode());
                    link = new com.extendedae_plus.ae.wireless.WirelessSlaveLink(endpoint);
                }
                var slave = (com.extendedae_plus.ae.wireless.WirelessSlaveLink) link;
                slave.setPlacerId(owner);
                slave.setFrequency(channel);
                slave.updateStatus();
            } catch (Throwable ignored) {
                // 无线系统异常不影响主功能
            }
        }

        /** 断开（无线系统侧清理）。 */
        public void disconnect() {
            if (!isLoaded() || link == null) {
                link = null;
                return;
            }
            try {
                com.extendedae_plus.util.wireless.ChannelCardLinkHelper.disconnect(
                        (com.extendedae_plus.ae.wireless.WirelessSlaveLink) link);
            } catch (Throwable ignored) {
            }
            link = null;
        }

        /** 卸载（方块移除/世界卸载时调用）。 */
        public void unload() {
            if (!isLoaded() || link == null) {
                link = null;
                return;
            }
            try {
                ((com.extendedae_plus.ae.wireless.WirelessSlaveLink) link).onUnloadOrRemove();
            } catch (Throwable ignored) {
            }
            link = null;
        }
    }
}
