package com.shadowqft.gtnhqueueeta.client;

import static gregtech.api.enums.GTValues.V;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.objects.overclockdescriber.OverclockDescriber;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.OverclockCalculator;

final class QueueWorkloadAnalyzer {

    private static final int MAX_SIMULATED_RECIPES = 128;
    private static final int MAX_SIMULATED_TICKS = 20 * 60 * 60 * 24;
    private static final int OVERCLOCK_FAILURE_SENTINEL = Integer.MAX_VALUE - 1;
    private static final Map<String, Integer> LAST_RECIPE_DURATIONS = new HashMap<>();

    private QueueWorkloadAnalyzer() {}

    static Optional<QueueEtaSnapshot> analyze(MTEBasicMachine machine, Optional<Double> progressRatioOverride) {
        String machineClassName = machine.getClass()
            .getName();
        if (machine.getBaseMetaTileEntity() == null) {
            return Optional.empty();
        }
        String machineKey = machineKey(machine);

        int maxProgress = machine.maxProgresstime();
        int currentProgress = machine.getProgresstime();
        int currentRemainingTicks = maxProgress - currentProgress;
        if (maxProgress <= 0 || currentProgress < 0 || currentRemainingTicks <= 0) {
            currentRemainingTicks = estimateCurrentRecipeRemainingTicks(machine, progressRatioOverride, machineKey);
            if (currentRemainingTicks <= 0) {
                return Optional.empty();
            }
        } else if (machineKey != null) {
            LAST_RECIPE_DURATIONS.put(machineKey, maxProgress);
        }

        RecipeMap<?> recipeMap = machine.getRecipeMap();
        if (recipeMap == null) {
            return Optional.of(
                new QueueEtaSnapshot(
                    currentRemainingTicks,
                    currentRemainingTicks,
                    machineClassName,
                    "QueueSim: no recipe map"));
        }

        SimulationState state = SimulationState.from(machine);
        if (state == null) {
            return Optional.of(
                new QueueEtaSnapshot(
                    currentRemainingTicks,
                    currentRemainingTicks,
                    machineClassName,
                    "QueueSim: state init failed"));
        }

        SimulationResult simulation = simulateQueuedTicks(machine, recipeMap, state);
        return Optional.of(
            new QueueEtaSnapshot(
                currentRemainingTicks + simulation.totalTicks(),
                currentRemainingTicks,
                machineClassName,
                simulation.summary()));
    }

    private static int estimateCurrentRecipeRemainingTicks(MTEBasicMachine machine,
        Optional<Double> progressRatioOverride, String machineKey) {
        if (!progressRatioOverride.isPresent()) {
            return -1;
        }

        double progressRatio = progressRatioOverride.get();
        GTRecipe currentRecipe = readLastRecipe(machine).orElseGet(() -> findCandidateRecipe(machine).orElse(null));
        int recipeDuration;
        if (currentRecipe != null) {
            recipeDuration = calculateRecipeDuration(machine, currentRecipe);
            if (recipeDuration <= 0 || recipeDuration >= OVERCLOCK_FAILURE_SENTINEL) {
                return -1;
            }
            if (machineKey != null) {
                LAST_RECIPE_DURATIONS.put(machineKey, recipeDuration);
            }
        } else {
            recipeDuration = machineKey == null ? -1 : LAST_RECIPE_DURATIONS.getOrDefault(machineKey, -1);
            if (recipeDuration <= 0) {
                return -1;
            }
        }

        int remainingTicks = (int) Math.ceil(recipeDuration * Math.max(0.0d, 1.0d - progressRatio));
        return remainingTicks > 0 ? remainingTicks : 1;
    }

    private static Optional<GTRecipe> readLastRecipe(MTEBasicMachine machine) {
        try {
            Field field = MTEBasicMachine.class.getDeclaredField("mLastRecipe");
            field.setAccessible(true);
            return Optional.ofNullable((GTRecipe) field.get(machine));
        } catch (ReflectiveOperationException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<GTRecipe> findCandidateRecipe(MTEBasicMachine machine) {
        RecipeMap<?> recipeMap = machine.getRecipeMap();
        if (recipeMap == null) {
            return Optional.empty();
        }

        SimulationState state = SimulationState.from(machine);
        if (state == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(
            recipeMap.findRecipeQuery()
                .items(state.inputs)
                .fluids(state.fillableStack)
                .specialSlot(state.specialSlot)
                .voltage(V[machine.mTier])
                .find());
    }

    private static SimulationResult simulateQueuedTicks(MTEBasicMachine machine, RecipeMap<?> recipeMap,
        SimulationState state) {
        GTRecipe cachedRecipe = null;
        int totalTicks = 0;
        String initialStateSummary = describeState(state);

        for (int simulatedRecipes = 0; simulatedRecipes < MAX_SIMULATED_RECIPES; simulatedRecipes++) {
            GTRecipe recipe = recipeMap.findRecipeQuery()
                .items(state.inputs)
                .fluids(state.fillableStack)
                .specialSlot(state.specialSlot)
                .voltage(V[machine.mTier])
                .cachedRecipe(cachedRecipe)
                .find();

            if (recipe == null) {
                return new SimulationResult(
                    totalTicks,
                    summary(simulatedRecipes, totalTicks, "no next recipe", initialStateSummary, describeState(state)));
            }
            if (!isDeterministic(recipe)) {
                return new SimulationResult(
                    totalTicks,
                    summary(
                        simulatedRecipes,
                        totalTicks,
                        "non-deterministic",
                        initialStateSummary,
                        describeState(state)));
            }
            if (!canOutput(state, recipe)) {
                return new SimulationResult(
                    totalTicks,
                    summary(simulatedRecipes, totalTicks, "output blocked", initialStateSummary, describeState(state)));
            }

            int recipeDuration = calculateRecipeDuration(machine, recipe);
            if (recipeDuration <= 0 || recipeDuration >= OVERCLOCK_FAILURE_SENTINEL) {
                return new SimulationResult(
                    totalTicks,
                    summary(simulatedRecipes, totalTicks, "bad duration", initialStateSummary, describeState(state)));
            }

            ItemStack[] previousInputs = copyItems(state.inputs);
            FluidStack previousFluid = copyFluid(state.fillableStack);
            FluidStack[] recipeFluids = toRecipeFluids(state.fillableStack);
            if (!recipe.isRecipeInputEqual(true, recipeFluids, state.inputs)) {
                return new SimulationResult(
                    totalTicks,
                    summary(
                        simulatedRecipes,
                        totalTicks,
                        "input consume failed",
                        initialStateSummary,
                        describeState(state)));
            }

            state.fillableStack = normalizeFluid(recipeFluids[0]);
            if (!consumedAnything(previousInputs, previousFluid, state.inputs, state.fillableStack)) {
                return new SimulationResult(
                    totalTicks,
                    summary(simulatedRecipes, totalTicks, "no input delta", initialStateSummary, describeState(state)));
            }

            cleanupItems(state.inputs);
            state.restorePersistentInputs();
            applyRecipeOutputs(state, recipe);
            cachedRecipe = recipe.mCanBeBuffered ? recipe : null;
            totalTicks = safeAdd(totalTicks, recipeDuration);
            if (totalTicks >= MAX_SIMULATED_TICKS) {
                return new SimulationResult(
                    MAX_SIMULATED_TICKS,
                    summary(
                        simulatedRecipes + 1,
                        MAX_SIMULATED_TICKS,
                        "tick cap",
                        initialStateSummary,
                        describeState(state)));
            }
        }

        return new SimulationResult(
            totalTicks,
            summary(MAX_SIMULATED_RECIPES, totalTicks, "recipe cap", initialStateSummary, describeState(state)));
    }

    private static String summary(int simulatedRecipes, int totalTicks, String reason, String initialState,
        String finalState) {
        return "QueueSim: " + simulatedRecipes
            + " runs, "
            + QueueEtaFormatter.formatTicks(totalTicks)
            + ", stop="
            + reason
            + ", in="
            + initialState
            + ", out="
            + finalState;
    }

    private static String describeState(SimulationState state) {
        return "items=" + summarizeItems(state.inputs)
            + ", special="
            + summarizeItem(state.specialSlot)
            + ", fill="
            + summarizeFluid(state.fillableStack);
    }

    private static String summarizeItems(ItemStack[] stacks) {
        StringBuilder builder = new StringBuilder("[");
        int shown = 0;
        for (ItemStack stack : stacks) {
            if (stack == null) {
                continue;
            }
            if (shown > 0) {
                builder.append("; ");
            }
            builder.append(summarizeItem(stack));
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        if (shown == 0) {
            builder.append("empty");
        }
        builder.append(']');
        return builder.toString();
    }

    private static String summarizeItem(ItemStack stack) {
        if (stack == null) {
            return "empty";
        }
        return stack.getUnlocalizedName() + "x" + stack.stackSize + ":" + stack.getItemDamage();
    }

    private static String summarizeFluid(FluidStack stack) {
        if (stack == null) {
            return "empty";
        }
        return stack.getFluid()
            .getName() + "@"
            + stack.amount;
    }

    private static int calculateRecipeDuration(MTEBasicMachine machine, GTRecipe recipe) {
        try {
            OverclockDescriber overclockDescriber = readOverclockDescriber(machine);
            OverclockCalculator calculator = overclockDescriber.createCalculator(
                new OverclockCalculator().setRecipeEUt(recipe.mEUt)
                    .setDuration(recipe.mDuration),
                recipe);
            calculator.calculate();
            return calculator.getDuration();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1;
        }
    }

    private static OverclockDescriber readOverclockDescriber(MTEBasicMachine machine)
        throws ReflectiveOperationException {
        Field field = MTEBasicMachine.class.getDeclaredField("overclockDescriber");
        field.setAccessible(true);
        return (OverclockDescriber) field.get(machine);
    }

    private static boolean isDeterministic(GTRecipe recipe) {
        if (recipe == null) {
            return false;
        }

        for (int i = 0; i < recipe.mOutputs.length; i++) {
            if (recipe.getOutputChance(i) != 10000) {
                return false;
            }
        }

        for (int i = 0; i < recipe.mFluidOutputs.length; i++) {
            if (recipe.getFluidOutputChance(i) != 10000) {
                return false;
            }
        }

        return recipe.mSpecialValue != -200 && recipe.mSpecialValue != -300;
    }

    private static boolean canOutput(SimulationState state, GTRecipe recipe) {
        if (recipe.mNeedsEmptyOutput) {
            return isOutputEmpty(state.outputSlots) && state.drainableStack == null;
        }
        return canOutputItems(state.outputSlots, recipe.mOutputs)
            && canOutputFluid(state.drainableStack, recipe.getFluidOutput(0), state.tankCapacity);
    }

    private static boolean canOutputItems(ItemStack[] outputSlots, ItemStack[] recipeOutputs) {
        if (recipeOutputs == null) {
            return true;
        }

        if (outputSlots.length == 0) {
            for (ItemStack recipeOutput : recipeOutputs) {
                if (recipeOutput != null) {
                    return false;
                }
            }
            return true;
        }

        for (int i = 0; i < outputSlots.length && i < recipeOutputs.length; i++) {
            ItemStack existing = outputSlots[i];
            ItemStack output = recipeOutputs[i];
            if (existing == null || output == null) {
                continue;
            }
            if (!GTUtility.areStacksEqual(existing, output, false)
                || existing.stackSize + output.stackSize > existing.getMaxStackSize()) {
                return false;
            }
        }

        return true;
    }

    private static boolean canOutputFluid(FluidStack drainableStack, FluidStack output, int tankCapacity) {
        if (output == null) {
            return true;
        }
        if (drainableStack != null && !drainableStack.isFluidEqual(output)) {
            return false;
        }
        return (drainableStack != null ? drainableStack.amount : 0) + output.amount <= tankCapacity;
    }

    private static void applyRecipeOutputs(SimulationState state, GTRecipe recipe) {
        insertOutputs(state.outputSlots, recipe.mOutputs);

        FluidStack outputFluid = recipe.getFluidOutput(0);
        if (outputFluid == null) {
            return;
        }

        if (state.drainableStack == null) {
            state.drainableStack = outputFluid.copy();
        } else if (state.drainableStack.isFluidEqual(outputFluid)) {
            state.drainableStack.amount += outputFluid.amount;
        }
    }

    private static void insertOutputs(ItemStack[] outputSlots, ItemStack[] pendingOutputs) {
        if (pendingOutputs == null || outputSlots.length == 0) {
            return;
        }

        for (int i = 0; i < pendingOutputs.length; i++) {
            ItemStack output = copyItem(pendingOutputs[i]);
            if (output == null) {
                continue;
            }
            if (output.stackSize > output.getMaxStackSize()) {
                output.stackSize = output.getMaxStackSize();
            }
            for (int j = 0; j < outputSlots.length; j++) {
                int slotIndex = (j + i) % outputSlots.length;
                if (tryInsert(outputSlots, slotIndex, output)) {
                    break;
                }
            }
        }
    }

    private static boolean tryInsert(ItemStack[] outputSlots, int slotIndex, ItemStack output) {
        ItemStack existing = outputSlots[slotIndex];
        if (existing == null) {
            outputSlots[slotIndex] = output.copy();
            return true;
        }
        if (!GTUtility.areStacksEqual(existing, output, false)) {
            return false;
        }
        int maxStackSize = existing.getMaxStackSize();
        if (existing.stackSize + output.stackSize > maxStackSize) {
            return false;
        }
        existing.stackSize += output.stackSize;
        return true;
    }

    private static boolean consumedAnything(ItemStack[] previousInputs, FluidStack previousFluid,
        ItemStack[] nextInputs, FluidStack nextFluid) {
        if (fluidAmount(previousFluid) != fluidAmount(nextFluid)) {
            return true;
        }

        if (previousInputs.length != nextInputs.length) {
            return true;
        }

        for (int i = 0; i < previousInputs.length; i++) {
            ItemStack before = previousInputs[i];
            ItemStack after = nextInputs[i];
            if (!sameStack(before, after)) {
                return true;
            }
        }

        return false;
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.stackSize == right.stackSize && GTUtility.areStacksEqual(left, right, true);
    }

    private static int fluidAmount(FluidStack fluidStack) {
        return fluidStack == null ? 0 : fluidStack.amount;
    }

    private static boolean isOutputEmpty(ItemStack[] outputSlots) {
        for (ItemStack outputSlot : outputSlots) {
            if (outputSlot != null) {
                return false;
            }
        }
        return true;
    }

    private static FluidStack[] toRecipeFluids(FluidStack fluidStack) {
        return new FluidStack[] { copyFluid(fluidStack) };
    }

    private static FluidStack normalizeFluid(FluidStack fluidStack) {
        if (fluidStack == null || fluidStack.amount <= 0) {
            return null;
        }
        return fluidStack;
    }

    private static ItemStack[] copyItems(ItemStack[] items) {
        ItemStack[] copies = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copies[i] = copyItem(items[i]);
        }
        return copies;
    }

    private static void cleanupItems(ItemStack[] items) {
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && items[i].stackSize <= 0) {
                items[i] = null;
            }
        }
    }

    private static ItemStack copyItem(ItemStack itemStack) {
        return itemStack == null ? null : itemStack.copy();
    }

    private static FluidStack copyFluid(FluidStack fluidStack) {
        return fluidStack == null ? null : fluidStack.copy();
    }

    private static ItemStack copyRecipeInput(ItemStack itemStack) {
        ItemStack copy = copyItem(itemStack);
        if (copy != null && copy.stackSize <= 0) {
            copy.stackSize = 1;
        }
        return copy;
    }

    private static int safeAdd(int left, int right) {
        long sum = (long) left + right;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static String machineKey(MTEBasicMachine machine) {
        if (machine.getBaseMetaTileEntity() == null || machine.getBaseMetaTileEntity()
            .getWorld() == null) {
            return null;
        }

        return machine.getBaseMetaTileEntity()
            .getWorld().provider.dimensionId + ":"
            + machine.getBaseMetaTileEntity()
                .getXCoord()
            + ":"
            + machine.getBaseMetaTileEntity()
                .getYCoord()
            + ":"
            + machine.getBaseMetaTileEntity()
                .getZCoord();
    }

    private static final class SimulationState {

        private final ItemStack[] inputs;
        private final int persistentInputIndex;
        private final ItemStack persistentInput;
        private final ItemStack specialSlot;
        private final ItemStack[] outputSlots;
        private final int tankCapacity;
        private FluidStack fillableStack;
        private FluidStack drainableStack;

        private SimulationState(ItemStack[] inputs, int persistentInputIndex, ItemStack persistentInput,
            ItemStack specialSlot, FluidStack fillableStack, ItemStack[] outputSlots, FluidStack drainableStack,
            int tankCapacity) {
            this.inputs = inputs;
            this.persistentInputIndex = persistentInputIndex;
            this.persistentInput = persistentInput;
            this.specialSlot = specialSlot;
            this.fillableStack = fillableStack;
            this.outputSlots = outputSlots;
            this.drainableStack = drainableStack;
            this.tankCapacity = tankCapacity;
        }

        private static SimulationState from(MTEBasicMachine machine) {
            ItemStack[] inputs = new ItemStack[machine.mInputSlotCount + (machine.allowSelectCircuit() ? 1 : 0)];
            for (int i = 0; i < machine.mInputSlotCount; i++) {
                inputs[i] = copyRecipeInput(machine.getStackInSlot(machine.getInputSlot() + i));
            }
            int persistentInputIndex = -1;
            ItemStack persistentInput = null;
            if (machine.allowSelectCircuit()) {
                persistentInputIndex = machine.mInputSlotCount;
                persistentInput = copyRecipeInput(machine.getStackInSlot(machine.getCircuitSlot()));
                inputs[persistentInputIndex] = copyItem(persistentInput);
            }

            ItemStack[] outputSlots = new ItemStack[machine.mOutputItems.length];
            for (int i = 0; i < outputSlots.length; i++) {
                outputSlots[i] = copyItem(machine.getStackInSlot(machine.getOutputSlot() + i));
            }

            SimulationState state = new SimulationState(
                inputs,
                persistentInputIndex,
                persistentInput,
                copyRecipeInput(machine.getStackInSlot(machine.getSpecialSlotIndex())),
                copyFluid(machine.getFillableStack()),
                outputSlots,
                copyFluid(machine.getDrainableStack()),
                machine.getCapacity());
            insertOutputs(state.outputSlots, machine.mOutputItems);
            if (machine.mOutputFluid != null) {
                if (state.drainableStack == null) {
                    state.drainableStack = machine.mOutputFluid.copy();
                } else if (state.drainableStack.isFluidEqual(machine.mOutputFluid)) {
                    state.drainableStack.amount += machine.mOutputFluid.amount;
                } else {
                    return null;
                }
            }
            return state;
        }

        private void restorePersistentInputs() {
            if (persistentInputIndex >= 0 && persistentInput != null) {
                inputs[persistentInputIndex] = copyItem(persistentInput);
            }
        }
    }

    private static final class SimulationResult {

        private final int totalTicks;
        private final String summary;

        private SimulationResult(int totalTicks, String summary) {
            this.totalTicks = totalTicks;
            this.summary = summary;
        }

        private int totalTicks() {
            return totalTicks;
        }

        private String summary() {
            return summary;
        }
    }
}
