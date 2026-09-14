package com.ae2addon.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2addon.recipe.QianJiPatternData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;

/**
 * 由千机**自有样板**（{@link QianJiPatternData}）合成的 AE2 {@link IPatternDetails}。
 * <p>
 * 作用：让 AE2 的合成 CPU 仍能完成任务派发与账务（不用改 AE2），
 * 但**执行细节由我们自己掌握** —— 输出只报「主产物」，
 * 概率产出不进 AE2 的期望表（于是永远不会出现"缺少副产 N"的等待），
 * 由千机在 {@code instantCraft} 里按我们样板里的几率掷骰后附带。
 * <p>
 * 2026-09-15 sensei 定稿：独立样板体系，不强兼原版样板。
 */
public final class QianJiPatternDetails implements IPatternDetails {

    private final QianJiPatternData data;
    private final AEItemKey definition;
    private final IInput[] inputs;
    private final GenericStack[] outputs;

    public QianJiPatternDetails(QianJiPatternData data, ItemStack patternStack) {
        this.data = data;
        this.definition = AEItemKey.of(patternStack);

        var in = new ArrayList<IInput>();
        for (var slot : data.inputs()) {
            // 选项数量统一为 1，槽位数量交给 multiplier（与 AE2 样板解码约定一致）
            var options = new GenericStack[slot.options().size()];
            long multiplier = 1;
            for (int i = 0; i < options.length; i++) {
                var option = slot.options().get(i);
                options[i] = new GenericStack(option.what(), 1);
                if (option.amount() > multiplier) multiplier = option.amount();
            }
            if (options.length > 0) in.add(new SimpleInput(options, multiplier));
        }
        this.inputs = in.toArray(new IInput[0]);

        var out = new ArrayList<GenericStack>();
        for (var p : data.primary()) {
            out.add(p.stack());
        }
        this.outputs = out.toArray(new GenericStack[0]);
    }

    /** 我们自己的结构化数据（执行时读它掷骰） */
    public QianJiPatternData data() {
        return data;
    }

    @Override
    public AEItemKey getDefinition() {
        return definition;
    }

    @Override
    public IInput[] getInputs() {
        return inputs;
    }

    @Override
    public GenericStack[] getOutputs() {
        return outputs;
    }

    /** 一个输入槽：可能多选（标签类原料）+ 数量 */
    private static final class SimpleInput implements IInput {
        private final GenericStack[] options;
        private final long multiplier;

        SimpleInput(GenericStack[] options, long multiplier) {
            this.options = options;
            this.multiplier = multiplier;
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return options;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            for (var option : options) {
                if (option != null && option.what().equals(input)) return true;
            }
            return false;
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
