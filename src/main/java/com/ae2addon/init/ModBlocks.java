package com.ae2addon.init;

import com.ae2addon.AE2Addon;
import com.ae2addon.block.InfiniteCraftingStorageBlock;
import com.ae2addon.block.InfiniteCoProcessingBlock;
import com.ae2addon.block.InfiniteInterfaceBlock;
import com.ae2addon.block.IntegratedCPUBlock;
import com.ae2addon.block.AssemblerCoreBlock;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * AE2 Addon 方块注册
 */
public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, AE2Addon.MODID);

    // ── 已有方块 ──

    /** 无限合成存储器 — 合成 CPU 扩容，永不爆仓 */
    public static final RegistryObject<Block> INFINITE_CRAFTING_STORAGE = BLOCKS.register(
            "infinite_crafting_storage",
            InfiniteCraftingStorageBlock::new
    );

    /** 无限并行处理单元 — 合成队列无限并发 */
    public static final RegistryObject<Block> INFINITE_CO_PROCESSING = BLOCKS.register(
            "infinite_co_processing",
            InfiniteCoProcessingBlock::new
    );

    // ── 新增三方块 ──

    /** 集成型CPU（无限级）— 3×5×3 多方块，提供无限合成能力 */
    public static final RegistryObject<Block> INTEGRATED_CPU = BLOCKS.register(
            "integrated_cpu",
            IntegratedCPUBlock::new
    );

    // ── ME接口（无限级） ──

    /** ME接口（无限级）— 机器供料站：被动拉取无上限 + 接收 CPU N× 直灌 + 按机器容量喂出 */
    public static final RegistryObject<Block> INFINITE_INTERFACE = BLOCKS.register(
            "infinite_interface",
            InfiniteInterfaceBlock::new
    );

    // ── 无限级装配处理器（v0.3 M3）──

    /** 装配处理器核心 — crafting-unit 型，3×3×3 框架+核心成簇，声明虚拟结算样板白名单 */
    public static final RegistryObject<Block> ASSEMBLER_CORE = BLOCKS.register(
            "assembler_core",
            AssemblerCoreBlock::new
    );
}
