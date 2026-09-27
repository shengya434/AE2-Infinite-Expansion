package com.ae2addon.item;

import com.ae2addon.block.InfiniteDriveBE;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkHooks;
import com.ae2addon.gui.InfiniteDriveMenu;

/** 绑定一台无限级驱动器，并通过它的面板选择要编辑的元件。 */
public class CellEditorItem extends Item {

    public CellEditorItem() {
        super(new Item.Properties().stacksTo(1).rarity(Rarity.RARE));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;

        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof InfiniteDriveBE drive) || !drive.isFormed()) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide) {
            CompoundTag tag = context.getItemInHand().getOrCreateTag();
            tag.putIntArray("edpos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
            tag.putString("eddim", level.dimension().location().toString());
            player.sendSystemMessage(Component.translatable("gui.ae2addon.cell_editor.bound",
                    pos.getX() + "," + pos.getY() + "," + pos.getZ()));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack editor = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) return InteractionResultHolder.pass(editor);
        if (level.isClientSide) return InteractionResultHolder.success(editor);

        CompoundTag tag = editor.getTag();
        if (tag == null || tag.getIntArray("edpos").length != 3 || !tag.contains("eddim", 8)) {
            player.sendSystemMessage(Component.translatable("gui.ae2addon.cell_editor.unbound"));
            return InteractionResultHolder.success(editor);
        }
        if (!level.dimension().location().toString().equals(tag.getString("eddim"))) {
            player.sendSystemMessage(Component.translatable("gui.ae2addon.cell_editor.wrong_dim"));
            return InteractionResultHolder.success(editor);
        }

        int[] coords = tag.getIntArray("edpos");
        BlockPos pos = new BlockPos(coords[0], coords[1], coords[2]);
        if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof InfiniteDriveBE drive)
                || !drive.isFormed()) {
            player.sendSystemMessage(Component.translatable("gui.ae2addon.cell_editor.missing"));
            return InteractionResultHolder.success(editor);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, new SimpleMenuProvider(
                    (id, inv, p) -> new InfiniteDriveMenu(id, inv, pos, true),
                    drive.getDisplayName()), buf -> {
                buf.writeBlockPos(pos);
                buf.writeBoolean(true);
            });
        }
        return InteractionResultHolder.success(editor);
    }
}
