package com.ae2addon.block.multiblock;

import com.ae2addon.init.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

/** Mirrors each validator's expected blocks without changing its formation logic. */
public final class MultiblockPreviewDefs {
    public static final MultiblockPreviewDef INTEGRATED_CPU = integratedCpu();
    public static final MultiblockPreviewDef INTEGRATED_CPU_NOEXPAND = integratedCpuNoExpand();
    public static final MultiblockPreviewDef QIANJI = qianji();
    public static final MultiblockPreviewDef INFINITE_DRIVE = infiniteDrive();

    private MultiblockPreviewDefs() {}

    private static MultiblockPreviewDef integratedCpu() {
        return fromCpuPattern("integrated_cpu", "gui.ae2addon.jei.cpu_structure",
                IntegratedCpuStructure.get());
    }

    private static MultiblockPreviewDef integratedCpuNoExpand() {
        return fromCpuPattern("integrated_cpu_noexpand", "gui.ae2addon.jei.cpu_structure_noexpand",
                IntegratedCpuStructure.loadVariant(
                        "/data/ae2addon/integrated_cpu_structure_noexpand.txt"));
    }

    private static MultiblockPreviewDef fromCpuPattern(String id, String titleKey,
                                                        IntegratedCpuStructure.Pattern pattern) {
        return new MultiblockPreviewDef(id, Component.translatable(titleKey),
                pattern.width(), pattern.height(), pattern.depth(), pattern.coreOffset(),
                (x, y, z) -> {
                    var accepted = pattern.expectedAt(x, y, z);
                    return accepted == null || accepted.isEmpty() ? null : accepted.get(0);
                }, new ItemStack(ModItems.INTEGRATED_CPU_ITEM.get()));
    }

    private static MultiblockPreviewDef qianji() {
        return new MultiblockPreviewDef("qianji",
                Component.translatable("gui.ae2addon.jei.qianji_structure"),
                3, 3, 3, new BlockPos(1, 1, 0),
                (x, y, z) -> y == 1 && x == 1 && z == 1
                        ? Blocks.DRAGON_EGG : Blocks.NETHERITE_BLOCK,
                new ItemStack(ModItems.QIAN_JI_ITEM.get()));
    }

    private static MultiblockPreviewDef infiniteDrive() {
        Block drive = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("ae2:drive"));
        if (drive == null) {
            throw new IllegalStateException("Missing required block ae2:drive for infinite drive preview");
        }
        return new MultiblockPreviewDef("infinite_drive",
                Component.translatable("gui.ae2addon.jei.drive_structure"),
                5, 5, 5, new BlockPos(2, 0, 4),
                (x, y, z) -> {
                    if (y == 0 || y == 4 || z == 0 || x == 0 || x == 4) {
                        return Blocks.GRAY_CONCRETE;
                    }
                    if (y == 1 || y == 3) {
                        return x == 2 ? Blocks.LIGHT_GRAY_CONCRETE : null;
                    }
                    if (y == 2) {
                        return x == 2 && z == 2 ? drive : Blocks.LIGHT_GRAY_CONCRETE;
                    }
                    return null;
                }, new ItemStack(ModItems.INFINITE_DRIVE_ITEM.get()));
    }
}
