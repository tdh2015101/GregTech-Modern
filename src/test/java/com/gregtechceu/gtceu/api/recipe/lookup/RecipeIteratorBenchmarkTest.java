package com.gregtechceu.gtceu.api.recipe.lookup;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.lookup.ingredient.AbstractMapIngredient;
import com.gregtechceu.gtceu.api.recipe.lookup.ingredient.MapIngredientTypeManager;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

@PrefixGameTestTemplate(false)
@GameTestHolder(GTCEu.MOD_ID)
public class RecipeIteratorBenchmarkTest {

    private static final boolean DO_RUN_RECIPE_ITERATOR_BENCHMARK = false;
    private static final int WARMUP_ROUNDS = 5;
    private static final int BENCH_ROUNDS = 20;
    private static final Predicate<GTRecipe> ALWAYS_TRUE = ignored -> true;

    @GameTest(template = "empty", batch = "StressTests")
    public static void compareIteratorImplementations(GameTestHelper helper) {
        if (!DO_RUN_RECIPE_ITERATOR_BENCHMARK) {
            helper.succeed();
            return;
        }

        var db = GTRecipeTypes.ASSEMBLER_RECIPES.db();
        var ingredientSpace = buildLargeIngredientSpace();
        var root = rootBranchOf(db);

        runBenchmark("optimized_warmup", WARMUP_ROUNDS,
                () -> new RecipeDB.RecipeIterator(db, ingredientSpace, ALWAYS_TRUE));
        runBenchmark("legacy_warmup", WARMUP_ROUNDS,
                () -> new LegacyRecipeIterator(root, ingredientSpace, ALWAYS_TRUE));

        var optimized = runBenchmark("optimized", BENCH_ROUNDS,
                () -> new RecipeDB.RecipeIterator(db, ingredientSpace, ALWAYS_TRUE));
        var legacy = runBenchmark("legacy", BENCH_ROUNDS,
                () -> new LegacyRecipeIterator(root, ingredientSpace, ALWAYS_TRUE));

        GTCEu.LOGGER.info("[RecipeIteratorBenchmark] optimized: rounds={}, recipes/round={}, wall={} ms, cpu={} ms",
                optimized.rounds,
                optimized.recipesPerRound,
                toMillis(optimized.wallNanos),
                toMillis(optimized.cpuNanos));
        GTCEu.LOGGER.info("[RecipeIteratorBenchmark] legacy: rounds={}, recipes/round={}, wall={} ms, cpu={} ms",
                legacy.rounds,
                legacy.recipesPerRound,
                toMillis(legacy.wallNanos),
                toMillis(legacy.cpuNanos));

        if (legacy.wallNanos > 0L && legacy.cpuNanos > 0L) {
            GTCEu.LOGGER.info("[RecipeIteratorBenchmark] wall speedup: {}x, cpu speedup: {}x",
                    round2((double) legacy.wallNanos / optimized.wallNanos),
                    round2((double) legacy.cpuNanos / optimized.cpuNanos));
        }

        helper.assertTrue(optimized.recipesPerRound == legacy.recipesPerRound,
                "Optimized and legacy iterators should produce the same number of recipes per round");
        helper.succeed();
    }

    private static List<List<AbstractMapIngredient>> buildLargeIngredientSpace() {
        List<List<AbstractMapIngredient>> list = new ArrayList<>();
        for (var item : BuiltInRegistries.ITEM) {
            list.add(MapIngredientTypeManager.getFrom(Ingredient.of(item), ItemRecipeCapability.CAP));
        }
        for (var block : BuiltInRegistries.BLOCK) {
            list.add(MapIngredientTypeManager.getFrom(Ingredient.of(block), ItemRecipeCapability.CAP));
        }
        return list;
    }

    private static BenchResult runBenchmark(String label, int rounds, Supplier<Iterator<GTRecipe>> supplier) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        boolean cpuEnabled = bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled();

        long startWall = System.nanoTime();
        long startCpu = cpuEnabled ? bean.getCurrentThreadCpuTime() : 0L;

        long recipesPerRound = -1L;
        for (int i = 0; i < rounds; i++) {
            Iterator<GTRecipe> iterator = supplier.get();
            long roundRecipes = 0L;
            while (iterator.hasNext()) {
                iterator.next();
                roundRecipes++;
            }
            if (recipesPerRound == -1L) {
                recipesPerRound = roundRecipes;
            } else if (recipesPerRound != roundRecipes) {
                throw new IllegalStateException(label + " produced unstable recipe counts between rounds");
            }
        }

        long endWall = System.nanoTime();
        long endCpu = cpuEnabled ? bean.getCurrentThreadCpuTime() : 0L;
        return new BenchResult(rounds, recipesPerRound, endWall - startWall, cpuEnabled ? (endCpu - startCpu) : -1L);
    }

    private static Branch rootBranchOf(RecipeDB db) {
        try {
            Field root = RecipeDB.class.getDeclaredField("rootBranch");
            root.setAccessible(true);
            return (Branch) root.get(db);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to access RecipeDB root branch for benchmark", e);
        }
    }

    private static double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record BenchResult(int rounds, long recipesPerRound, long wallNanos, long cpuNanos) {}

    private static class LegacySearchFrame {

        int index;
        int ingredientIndex;
        Branch branch;

        LegacySearchFrame(int index, Branch branch) {
            this.index = index;
            this.ingredientIndex = 0;
            this.branch = branch;
        }
    }

    private static class LegacyRecipeIterator implements Iterator<GTRecipe> {

        private final Branch root;
        private final List<List<AbstractMapIngredient>> ingredients;
        private final Predicate<GTRecipe> predicate;
        private final Deque<LegacySearchFrame> stack = new ArrayDeque<>();

        private GTRecipe nextCached;
        private boolean hasCached;

        LegacyRecipeIterator(Branch root, List<List<AbstractMapIngredient>> ingredients, Predicate<GTRecipe> predicate) {
            this.root = root;
            this.ingredients = ingredients;
            this.predicate = predicate;
            reset();
        }

        private static Map<AbstractMapIngredient, RecipeNode> nodesForIngredient(AbstractMapIngredient ingredient, Branch branch) {
            if (ingredient.isSpecialIngredient()) {
                return branch.getSpecialNodes();
            }
            return branch.getNodes();
        }

        private GTRecipe getNext() {
            while (!stack.isEmpty()) {
                LegacySearchFrame frame = stack.peek();

                if (frame.ingredientIndex >= ingredients.get(frame.index).size()) {
                    stack.pop();
                    continue;
                }

                List<AbstractMapIngredient> ingredientList = ingredients.get(frame.index);
                AbstractMapIngredient ingredient = ingredientList.get(frame.ingredientIndex);
                frame.ingredientIndex++;
                var nodes = nodesForIngredient(ingredient, frame.branch);
                var result = nodes.get(ingredient);
                if (result == null) {
                    continue;
                }

                if (result.recipe != null && predicate.test(result.recipe)) {
                    return result.recipe;
                }

                if (result.branch != null) {
                    for (int j = ingredients.size() - 1; j >= 0; j--) {
                        stack.push(new LegacySearchFrame(j, result.branch));
                    }
                }
            }

            return null;
        }

        @Override
        public boolean hasNext() {
            if (!hasCached) {
                nextCached = getNext();
                hasCached = true;
            }
            return nextCached != null;
        }

        @Override
        public GTRecipe next() {
            if (!hasCached) {
                nextCached = getNext();
            }
            hasCached = false;
            if (nextCached == null) {
                throw new java.util.NoSuchElementException();
            }
            return nextCached;
        }

        void reset() {
            stack.clear();
            for (int i = ingredients.size() - 1; i >= 0; i--) {
                stack.push(new LegacySearchFrame(i, root));
            }
            nextCached = null;
            hasCached = false;
        }
    }
}
