package com.ae2addon.network;

import com.ae2addon.init.ModItems;
import com.ae2addon.recipe.QianJiPatternData;
import com.ae2addon.recipe.QianJiRecipeModel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
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
    /** 变体序（序列装配的步骤样板：0=全链，1..N=步骤，N+1=收尾；其他配方恒为 0） */
    private final int variant;

    public QianJiPatternPacket(String recipeId) {
        this(recipeId, 0);
    }

    public QianJiPatternPacket(String recipeId, int variant) {
        this.recipeId = recipeId;
        this.variant = Math.max(0, variant);
    }

    public static void encode(QianJiPatternPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.recipeId);
        buf.writeVarInt(msg.variant);
    }

    public static QianJiPatternPacket decode(FriendlyByteBuf buf) {
        return new QianJiPatternPacket(buf.readUtf(), buf.readVarInt());
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
                // GT 运行时配方（酿造/药水…）不在 RecipeManager 里 → 走额外配方来源解析
                Recipe<?> recipe = found.isPresent()
                        ? found.get()
                        : com.ae2addon.compat.GregTechRuntimeCompat.findByStableId(msg.recipeId);
                if (recipe == null) {
                    player.displayClientMessage(Component.literal("§c找不到配方: " + msg.recipeId), false);
                    return;
                }
                QianJiPatternData data;
                var variants = QianJiRecipeModel.fromRecipeAll(recipe, level.registryAccess());
                if (variants.isEmpty()) {
                    data = QianJiRecipeModel.fromRecipe(recipe, level);
                } else {
                    int idx = Math.min(msg.variant, variants.size() - 1);
                    data = variants.get(idx).data();
                }
                if (data == null || (data.primary().isEmpty() && data.chanced().isEmpty())) {
                    player.displayClientMessage(Component.literal("§c该配方提取不出主产物，无法编码"), false);
                    return;
                }

                // 成本：与 AE2 编码样板一致（创造免费，否则消耗 1 个空白样板）
                if (!player.isCreative()) {
                    // 2026-09-20 sensei：**样板编码终端的空白样板槽里的空白样板也算**
                    //（原来只扫玩家背包 → 把空白样板放在终端槽里就编不了，得先拿回背包，反直觉）
                    // 2026-09-21 sensei：空白样板还可以**直接从网络取用** ——
                    // 是"访问并使用"（从 ME 存储直接扣掉 1 个，不落到任何槽里），
                    // 不是"抽取并使用"（不先抽进容器再消耗）。优先级：界面槽 → 网络 → 背包。
                    if (!tryConsumeFromMenu(player) && !consumeFromNetwork(player)) {
                        ItemStack inBag = findBlankPattern(player);
                        if (inBag.isEmpty()) {
                            player.displayClientMessage(
                                    Component.literal("§c需要一个空白样板（终端槽内 / ME 网络里 / 背包里）"), false);
                            return;
                        }
                        inBag.shrink(1);
                    }
                }

                ItemStack pattern = new ItemStack(ModItems.QIAN_JI_PATTERN.get());
                data.writeTo(pattern);
                // 2026-09-20 sensei 要求②：编好的样板**优先进终端自己的「编码样板槽」**
                // （跟 AE2 自己编码的体验一致），进不去再走原来的背包/掉落逻辑。
                // 该槽的过滤由 PatternSlotQianJiMixin 放宽，所以 mayPlace 会通过。
                if (!tryPutIntoEncodedSlot(player, pattern)) {
                    if (!player.getInventory().add(pattern)) {
                        player.drop(pattern, false);
                    }
                }
                player.displayClientMessage(Component.literal("§a已编码千机样板: §7" + msg.recipeId
                        + " §8(主产物 " + data.primary().size()
                        + " / 概率产出 " + data.chanced().size() + ")"), false);
            });
        }
        ctx.get().setPacketHandled(true);
    }

    /**
     * 在玩家**当前打开的容器菜单**里找空白样板并消耗 1 个（2026-09-20 sensei 实测需求）。
     * <p>
     * 优先按 AE2 的槽语义找（样板编码终端的 {@code BLANK_PATTERN} 槽），找不到再扫菜单里的所有槽 ——
     * 这样"把空白样板放进终端槽里编样板"就跟 AE2 自己的编码体验一致了。
     * <p>
     * 为什么用 {@code Slot.remove(1)} 而不是直接 {@code shrink}：终端那些槽是 AE2 自己的
     * {@code InternalInventory} 包装（{@code AppEngSlot}），必须走 Slot API 才会正确回写、
     * 触发 {@code setChanged} 与客户端同步。
     *
     * @return {@code true} = **已经在菜单槽里扣掉了 1 个**（调用方不要再动背包）；{@code false} = 菜单里没找到
     */
    private static boolean tryConsumeFromMenu(net.minecraft.world.entity.player.Player player) {
        try {
            var menu = player.containerMenu;
            if (menu == null) return false;

            net.minecraft.world.inventory.Slot target = null;
            // 我们自己的界面（千机·样板终端）：优先用它声明的空白样板槽，
            // 这样"把空白样板放进终端上槽 → 编码 → 成品落进下槽"就跟 AE2 编码终端手感一致了
            if (menu instanceof com.ae2addon.gui.QianJiEncodedSlotHolder holder) {
                for (var slot : holder.blankSlots()) {
                    if (slot != null && slot.hasItem() && isBlankPattern(slot.getItem())) {
                        target = slot;
                        break;
                    }
                }
            }
            if (target == null && menu instanceof appeng.menu.AEBaseMenu ae) {
                for (var slot : ae.getSlots(appeng.menu.SlotSemantics.BLANK_PATTERN)) {
                    if (slot != null && isBlankPattern(slot.getItem())) { target = slot; break; }
                }
            }
            if (target == null) {
                for (var slot : menu.slots) {
                    if (slot != null && slot.hasItem() && isBlankPattern(slot.getItem())) { target = slot; break; }
                }
            }
            if (target == null) return false;

            // 走 Slot API 扣 1 个（AE2 的槽是 InternalInventory 包装，必须这么扣才会回写+同步）
            target.remove(1);
            return true;
        } catch (Throwable t) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 从终端槽扣空白样板失败：{}", t.toString());
            return false;
        }
    }

    /**
     * 把编好的千机样板放进终端自己的「编码样板槽」（2026-09-20 sensei 要求②）。
     * <p>
     * 该槽是 AE2 的 {@code RestrictedInputSlot}（过滤只认 AE2 样板），
     * 由 {@code PatternSlotQianJiMixin} 放宽后才放得进去 —— 所以这里正常走
     * {@code slot.mayPlace(...)} 判断，**不做任何强制写入**（强写会把玩家自己放的东西顶掉）。
     *
     * @return true = 已放入该槽（调用方不要再塞背包）
     */
    private static boolean tryPutIntoEncodedSlot(net.minecraft.world.entity.player.Player player,
                                                ItemStack pattern) {
        try {
            // 我们自己的界面（千机·样板终端）：它只有"编码样板槽"这一个出口，
            // 且该槽只认千机样板（mayPlace 会拦下别的），所以这里照常走 mayPlace 判断
            if (player.containerMenu instanceof com.ae2addon.gui.QianJiEncodedSlotHolder holder) {
                var own = holder.encodedSlot();
                if (own != null && own.getItem().isEmpty() && own.mayPlace(pattern)) {
                    own.set(pattern.copy());
                    own.setChanged();
                    return true;
                }
            }
            if (player.containerMenu instanceof appeng.menu.AEBaseMenu ae) {
                for (var slot : ae.getSlots(appeng.menu.SlotSemantics.ENCODED_PATTERN)) {
                    if (slot != null && slot.getItem().isEmpty() && slot.mayPlace(pattern)) {
                        slot.set(pattern.copy());
                        slot.setChanged();
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 放入终端编码样板槽失败：{}", t.toString());
        }
        return false;
    }

    /**
     * 从 **ME 网络**直接取用 1 个空白样板 —— sensei 2026-09-21：「空白样板可以直接通过网络拉取的方式提供，
     * 是**访问并使用**，而不是抽取并使用」。
     * <p>
     * 实现要点：直接在 ME 存储上做 {@code extract(..., Actionable.MODULATE, ...)} ——
     * 东西从网络库存里扣掉，**不会先抽进任何槽/背包**（那才是"抽取"）。这样"网络里存着空白样板"
     * 就等于随时能编码，不必先把样板搬到手上。
     * <p>
     * 优先级：界面槽（玩家的直观操作）→ 网络（本条）→ 玩家背包。
     *
     * @return true = 已从网络扣掉 1 个空白样板
     */
    private static boolean consumeFromNetwork(net.minecraft.world.entity.player.Player player) {
        try {
            appeng.api.networking.IGrid grid = null;
            if (player.containerMenu instanceof com.ae2addon.gui.QianJiEncodedSlotHolder holder) {
                grid = holder.grid();
            } else if (player.containerMenu instanceof com.ae2addon.gui.QianJiMenu qianjiMenu) {
                grid = qianjiMenu.grid();
            }
            if (grid == null) return false;
            var storageService = grid.getStorageService();
            var storage = storageService == null ? null : storageService.getInventory();
            if (storage == null) return false;

            var blankItem = appeng.core.definitions.AEItems.BLANK_PATTERN.asItem();
            var key = appeng.api.stacks.AEItemKey.of(new ItemStack(blankItem));
            if (key == null) return false;

            long taken = storage.extract(key, 1, appeng.api.config.Actionable.MODULATE,
                    appeng.api.networking.security.IActionSource.ofPlayer(player));
            if (taken >= 1) {
                com.ae2addon.AE2Addon.LOGGER.info("[ae2addon][encode] 从 ME 网络直接取用 1 个空白样板（访问并使用）");
                return true;
            }
            return false;
        } catch (Throwable t) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 从网络取用空白样板失败：{}", t.toString());
            return false;
        }
    }

    private static boolean isBlankPattern(ItemStack stack) {        return !stack.isEmpty() && String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getKey(stack.getItem())).equals("ae2:blank_pattern");
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
