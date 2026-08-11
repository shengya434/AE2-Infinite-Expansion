package com.ae2addon.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 运行时缩放配方视图：同一个配方重复 multiplier 次的完整输入/输出。
 * 编码的配方物品（pattern definition）保持不变。
 * <p>
 * 思路来自 OmniSequence-Transfinite 的 MolecularScaledPattern。
 * 批量推送核心：一次 pushPattern 携带 N× 输入，接收方（如无限消费型机器）
 * 接受后，一次调用完成 N 份合成，而不是 N 次 1× 推送。
 */
public final class ScaledPattern implements IPatternDetails {
    private final IPatternDetails base;
    private final long multiplier;
    private final IInput[] inputs;
    private final GenericStack[] outputs;
    private final int hashCode;

    public ScaledPattern(IPatternDetails base, long multiplier) {
        Objects.requireNonNull(base, "base");
        if (multiplier <= 0) {
            throw new IllegalArgumentException("Pattern multiplier must be positive: " + multiplier);
        }

        if (base instanceof ScaledPattern scaled) {
            this.base = scaled.base;
            this.multiplier = Math.multiplyExact(scaled.multiplier, multiplier);
        } else {
            this.base = base;
            this.multiplier = multiplier;
        }

        this.inputs = scaleInputs(this.base.getInputs(), this.multiplier);
        this.outputs = scaleOutputs(this.base.getOutputs(), this.multiplier);
        this.hashCode = 31 * this.base.hashCode() + Long.hashCode(this.multiplier);
    }

    /** 原始（未缩放）配方。 */
    public IPatternDetails base() {
        return base;
    }

    public long multiplier() {
        return multiplier;
    }

    @Override
    public AEItemKey getDefinition() {
        return base.getDefinition();
    }

    @Override
    public IInput[] getInputs() {
        return inputs;
    }

    @Override
    public GenericStack[] getOutputs() {
        return outputs;
    }

    @Override
    public boolean supportsPushInputsToExternalInventory() {
        return base.supportsPushInputsToExternalInventory();
    }

    /**
     * 不能委托给 AEProcessingPattern：它的实现使用编码的稀疏配方数量（未缩放）。
     * 直接转发实际提取的计数，确保每个被接受的输入都归 provider 所有并到达目标。
     */
    @Override
    public void pushInputsToExternalInventory(KeyCounter[] inputHolder, PatternInputSink inputSink) {
        Objects.requireNonNull(inputHolder, "inputHolder");
        Objects.requireNonNull(inputSink, "inputSink");

        for (var counter : inputHolder) {
            Objects.requireNonNull(counter, "input holder entry");
            for (var entry : counter) {
                if (entry.getKey() == null || entry.getLongValue() <= 0) {
                    throw new IllegalArgumentException(
                            "Pattern input amount must be positive: " + entry.getLongValue());
                }
                inputSink.pushInput(entry.getKey(), entry.getLongValue());
            }
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ScaledPattern other)) {
            return false;
        }
        return multiplier == other.multiplier && base.equals(other.base);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "ScaledPattern[base=" + base + ", multiplier=" + multiplier + "]";
    }

    private static IInput[] scaleInputs(IInput[] baseInputs, long multiplier) {
        Objects.requireNonNull(baseInputs, "base inputs");
        var scaledInputs = new IInput[baseInputs.length];
        for (int index = 0; index < baseInputs.length; index++) {
            scaledInputs[index] = new ScaledInput(
                    Objects.requireNonNull(baseInputs[index], "base input " + index),
                    multiplier);
        }
        return scaledInputs;
    }

    private static GenericStack[] scaleOutputs(GenericStack[] baseOutputs, long multiplier) {
        Objects.requireNonNull(baseOutputs, "base outputs");
        var scaledOutputs = new GenericStack[baseOutputs.length];
        for (int index = 0; index < baseOutputs.length; index++) {
            var output = Objects.requireNonNull(baseOutputs[index], "base output " + index);
            if (output.amount() <= 0) {
                throw new IllegalArgumentException(
                        "Pattern output amount must be positive: " + output.amount());
            }
            scaledOutputs[index] = new GenericStack(output.what(),
                    Math.multiplyExact(output.amount(), multiplier));
        }
        return scaledOutputs;
    }

    private static final class ScaledInput implements IInput {
        private final IInput base;
        private final GenericStack[] possibleInputs;
        private final long multiplier;

        private ScaledInput(IInput base, long patternMultiplier) {
            this.base = base;

            long baseMultiplier = base.getMultiplier();
            if (baseMultiplier <= 0) {
                throw new IllegalArgumentException(
                        "Pattern input multiplier must be positive: " + baseMultiplier);
            }
            this.multiplier = Math.multiplyExact(baseMultiplier, patternMultiplier);

            this.possibleInputs = Objects.requireNonNull(base.getPossibleInputs(),
                    "possible inputs");
            if (possibleInputs.length == 0) {
                throw new IllegalArgumentException("Pattern input has no possible inputs");
            }
            for (var possibleInput : possibleInputs) {
                Objects.requireNonNull(possibleInput, "possible input");
                if (possibleInput.amount() <= 0) {
                    throw new IllegalArgumentException(
                            "Possible input amount must be positive: " + possibleInput.amount());
                }
                // AE2 后续用 long 算术把该数量乘 IInput#getMultiplier，
                // 这里预检同一乘积，防止在那里回绕。
                Math.multiplyExact(possibleInput.amount(), this.multiplier);
            }
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return base.isValid(input, level);
        }

        @Nullable
        @Override
        public AEKey getRemainingKey(AEKey template) {
            return base.getRemainingKey(template);
        }
    }
}
