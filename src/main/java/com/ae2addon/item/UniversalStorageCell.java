package com.ae2addon.item;

import appeng.api.config.FuzzyMode;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.util.ConfigInventory;
import com.ae2addon.gui.ModeSelectMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.math.BigInteger;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 万能无限存储元件
 * <p>
 * Mode 1 — 无限制存储   Mode 2 — 自定义无限   Mode 3 — 全类型无限
 */
public class UniversalStorageCell extends Item implements ICellWorkbenchItem {

    public static final int MODE_STANDARD = 1;
    public static final int MODE_CUSTOM = 2;
    public static final int MODE_UNIVERSAL = 3;

    public UniversalStorageCell() {
        super(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
    }

    // ── 右键：模式2→配置界面，其他→模式选择 ──

    /**
     * Mode 2 配置菜单的 MenuProvider（静态内部类，避免匿名类 $N 加载问题）。
     * 注意：openScreen 的 buf 写入也必须用静态 Consumer 类（不能 lambda）。
     */
    private static class Mode2MenuProvider implements MenuProvider {
        private final ItemStack stack;

        Mode2MenuProvider(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public Component getDisplayName() {
            return Component.translatable("gui.ae2addon.mode2_config");
        }

        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
            return new com.ae2addon.gui.Mode2ConfigMenu(id, inv, stack);
        }
    }

    /** Mode 1/3 模式选择菜单的 MenuProvider（静态内部类） */
    private static class ModeSelectMenuProvider implements MenuProvider {
        private final ItemStack stack;

        ModeSelectMenuProvider(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public Component getDisplayName() {
            return Component.translatable("gui.ae2addon.mode_select");
        }

        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
            return new ModeSelectMenu(id, inv, stack);
        }
    }

    /** openScreen 的 buf 写入器（静态内部类，避免 lambda 合成类问题） */
    private static class LightStackWriter implements java.util.function.Consumer<net.minecraft.network.FriendlyByteBuf> {
        private final ItemStack stack;
        private final boolean dropModeData; // true=模式1/3（额外移除 a）

        LightStackWriter(ItemStack stack, boolean dropModeData) {
            this.stack = stack;
            this.dropModeData = dropModeData;
        }

        @Override
        public void accept(net.minecraft.network.FriendlyByteBuf buf) {
            // 只传阈值，裁掉 s2/ul/wl 等重 NBT 数据以防止打开菜单时就炸包
            ItemStack copy = stack.copy();
            CompoundTag tag = copy.getOrCreateTag();
            tag.remove("s1");
            tag.remove("s2");
            tag.remove("sa");
            tag.remove("u");
            tag.remove("w");
            if (dropModeData) {
                tag.remove("a");
            }
            buf.writeItem(copy);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);

        if (player instanceof ServerPlayer serverPlayer) {
            int mode = stack.getOrCreateTag().getInt("umode");
            if (mode < 1 || mode > 3) mode = 1;

            if (mode == MODE_CUSTOM) {
                NetworkHooks.openScreen(serverPlayer,
                        new Mode2MenuProvider(stack),
                        new LightStackWriter(stack, false));
            } else {
                // Mode 1 / Mode 3：只传光副本，裁掉存储NBT防炸包
                NetworkHooks.openScreen(serverPlayer,
                        new ModeSelectMenuProvider(stack),
                        new LightStackWriter(stack, true));
            }
        }
        return InteractionResultHolder.success(stack);
    }

    // ── ICellWorkbenchItem ──

    @Override public boolean isEditable(ItemStack cellItem) { return true; }
    @Override public ConfigInventory getConfigInventory(ItemStack is) {
        return ConfigInventory.configTypes(63, () -> {});
    }
    @Override public IUpgradeInventory getUpgrades(ItemStack cellItem) {
        return UpgradeInventories.forItem(cellItem, 1, (s, u) -> {});
    }
    @Override public FuzzyMode getFuzzyMode(ItemStack is) { return FuzzyMode.IGNORE_ALL; }
    @Override public void setFuzzyMode(ItemStack is, FuzzyMode fzMode) {}

    // ── 工具提示 ──

    @Override
    public Component getName(ItemStack stack) {
        int m = stack.getOrCreateTag().getInt("umode");
        String[] n = {"", "§a无限制", "§e自定义", "§d全类型"};
        if (m < 1 || m > 3) m = 1;
        return Component.literal("§5万能无限 [" + n[m] + "]");
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getOrCreateTag();
        int m = tag.getInt("umode");
        if (m < 1 || m > 3) m = 1;

        String[][] info = {
                {},
                {"§a无限制存储", "§7无限容量·全AE类型·正常存取"},
                {"§e自定义无限", "§7白名单无限 + 通用阈值"},
                {"§d全类型无限", "§7仅物品/流体·存入即无限"}
        };
        tooltip.add(Component.literal("模式: " + info[m][0]));
        tooltip.add(Component.literal(info[m][1]));

        // Mode 3 直接显示 ∞
        if (m == 3) {
            tooltip.add(Component.literal("§7字节: §b∞"));
            tooltip.add(Component.literal("§7类型: §b∞"));
            tooltip.add(Component.literal("§7限制: §e仅物品和流体类型"));
            tooltip.add(Component.literal("§7右键切换模式"));
            return;
        }

        // Mode 1 / 2：从统计摘要标签读（轻量，不遍历几千条 NBT）
        BigInteger totalBytes;
        if (tag.contains("_b2", 7)) {
            totalBytes = new BigInteger(tag.getByteArray("_b2"));
        } else {
            totalBytes = BigInteger.valueOf(tag.getLong("_b"));
        }
        int typeCount = tag.getInt("_t");

        tooltip.add(Component.literal("§7字节: §b" + formatBytes(totalBytes)));
        tooltip.add(Component.literal("§7类型: §b" + typeCount));
        tooltip.add(Component.literal("§7右键切换模式"));
    }

    /** BigInteger 版字节格式化：支持 B/K/M/G/T/P/E/Z/Y/R/Q 单位 */
    private String formatBytes(BigInteger bytes) {
        if (bytes.signum() < 0) return "0B";
        String[] units = {"B", "K", "M", "G", "T", "P", "E", "Z", "Y", "R", "Q"};
        BigInteger base = BigInteger.valueOf(1000);
        BigInteger v = bytes;
        int u = 0;
        while (u < units.length - 1 && v.compareTo(base) >= 0) {
            v = v.divide(base);
            u++;
        }
        // 保留一位小数的近似（显示友好）
        if (u > 0) {
            // 用 BigDecimal 算一位小数
            java.math.BigDecimal bd = new java.math.BigDecimal(bytes);
            java.math.BigDecimal div = java.math.BigDecimal.valueOf(1000).pow(u);
            bd = bd.divide(div, 1, java.math.RoundingMode.DOWN);
            return bd.toPlainString() + units[u];
        }
        return v + units[u];
    }


}
