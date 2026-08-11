package com.ae2addon.init;

import com.ae2addon.AE2Addon;
import com.ae2addon.block.DebugTrashBE;
import com.ae2addon.block.InfiniteCraftingStorageBE;
import com.ae2addon.block.InfiniteCoProcessingBE;
import com.ae2addon.block.IntegratedCPUBE;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * AE2 Addon 方块实体注册
 */
public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, AE2Addon.MODID);

    // ── 已有 ──

    public static final RegistryObject<BlockEntityType<InfiniteCraftingStorageBE>> INFINITE_CRAFTING_STORAGE =
            BLOCK_ENTITIES.register("infinite_crafting_storage",
                    () -> BlockEntityType.Builder.of(
                            InfiniteCraftingStorageBE::new,
                            ModBlocks.INFINITE_CRAFTING_STORAGE.get()
                    ).build(null));

    public static final RegistryObject<BlockEntityType<InfiniteCoProcessingBE>> INFINITE_CO_PROCESSING =
            BLOCK_ENTITIES.register("infinite_co_processing",
                    () -> BlockEntityType.Builder.of(
                            InfiniteCoProcessingBE::new,
                            ModBlocks.INFINITE_CO_PROCESSING.get()
                    ).build(null));

    // ── 新增 ──

    public static final RegistryObject<BlockEntityType<IntegratedCPUBE>> INTEGRATED_CPU =
            BLOCK_ENTITIES.register("integrated_cpu",
                    () -> BlockEntityType.Builder.of(
                            IntegratedCPUBE::new,
                            ModBlocks.INTEGRATED_CPU.get()
                    ).build(null));

    public static final RegistryObject<BlockEntityType<DebugTrashBE>> DEBUG_TRASH =
            BLOCK_ENTITIES.register("debug_trash",
                    () -> BlockEntityType.Builder.of(
                            DebugTrashBE::new,
                            ModBlocks.DEBUG_TRASH.get()
                    ).build(null));
}
