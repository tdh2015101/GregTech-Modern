package com.gregtechceu.gtceu.api.recipe.lookup;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.lookup.ingredient.AbstractMapIngredient;
import com.gregtechceu.gtceu.api.recipe.lookup.ingredient.MapIngredientTypeManager;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.common.item.armor.PowerlessJetpack;
import com.gregtechceu.gtceu.config.ConfigHolder;

import net.minecraftforge.registries.ForgeRegistries;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.function.Predicate;

/**
 * Data structure storing recipes by their input ingredients
 */
public final class RecipeDB {

    private final @NotNull Branch rootBranch = new Branch();

    /**
     * Clear the DB
     */
    @ApiStatus.Internal
    public void clear() {
        rootBranch.clear();
    }

    /**
     * Find a GT Recipe
     *
     * @param holder the holder to search
     * @return the recipe
     */
    public @Nullable GTRecipe find(@NotNull IRecipeCapabilityHolder holder) {
        return find(holder, r -> RecipeHelper.matchRecipe(holder, r).isSuccess());
    }

    /**
     * Find a GT Recipe
     *
     * @param holder    the holder to search
     * @param predicate the predicate to determine recipe validity
     * @return the recipe
     */
    public @Nullable GTRecipe find(@NotNull IRecipeCapabilityHolder holder, @NotNull Predicate<GTRecipe> predicate) {
        List<List<AbstractMapIngredient>> list = fromHolder(holder);
        if (list == null) {
            return null;
        }
        return find(list, predicate);
    }

    /**
     * Find a GT Recipe
     *
     * @param list      the ingredients to search
     * @param predicate the predicate to determine recipe validity
     * @return the recipe
     */
    @ApiStatus.Internal
    @VisibleForTesting
    public @Nullable GTRecipe find(@NotNull List<List<AbstractMapIngredient>> list,
                                   @NotNull Predicate<GTRecipe> predicate) {
        var iter = new RecipeIterator(this, list, predicate);
        return iter.hasNext() ? iter.next() : null;
    }

    /**
     * Find a GT Recipe
     *
     * @param inputs    the input capabilities and their associated contents to search with
     * @param predicate the predicate to determine recipe validity
     * @return the recipe
     */
    public @Nullable GTRecipe find(@NotNull Map<RecipeCapability<?>, List<Object>> inputs,
                                   @NotNull Predicate<GTRecipe> predicate) {
        List<List<AbstractMapIngredient>> list = new ArrayList<>();
        inputs.forEach((cap, content) -> {
            if (!cap.isRecipeSearchFilter()) {
                return;
            }
            var compressed = cap.compressIngredients(content);
            for (var ingredient : compressed) {
                list.add(MapIngredientTypeManager.getFrom(ingredient, cap));
            }
        });
        return find(list, predicate);
    }

    /**
     * Create an iterator for a search space
     *
     * @param holder    the holder to search
     * @param predicate the predicate to determine recipe validity
     * @return an iterator
     */
    public @Nullable RecipeDB.RecipeIterator iterator(@NotNull IRecipeCapabilityHolder holder,
                                                      @NotNull Predicate<GTRecipe> predicate) {
        List<List<AbstractMapIngredient>> list = fromHolder(holder);
        if (list == null) {
            return null;
        }
        return new RecipeIterator(this, list, predicate);
    }

    /**
     * Converts a Recipe Capability holder's handlers into a list of {@link AbstractMapIngredient}
     *
     * @param holder the capability holder to query handlers from
     * @return a list of all the AbstractMapIngredients in the handlers
     */
    private @Nullable List<List<AbstractMapIngredient>> fromHolder(@NotNull IRecipeCapabilityHolder holder) {
        var handlerMap = holder.getCapabilitiesFlat().getOrDefault(IO.IN, Collections.emptyMap());
        if (handlerMap.isEmpty()) {
            return null;
        }

        // the initial capacity is a "feel-good" value because it's faster to just grow the list
        // than to calculate an accurate value.
        List<List<AbstractMapIngredient>> list = new ObjectArrayList<>(handlerMap.size() * 8);
        handlerMap.forEach((cap, handlers) -> {
            if (!cap.isRecipeSearchFilter()) {
                return;
            }
            for (var handler : handlers) {
                var compressed = cap.compressIngredients(handler.getContents());
                for (var ingredient : compressed) {
                    list.add(MapIngredientTypeManager.getFrom(ingredient, cap));
                }
            }
        });
        if (list.isEmpty()) {
            return null;
        }
        return list;
    }

    /**
     * Determine the correct root nodes for an ingredient.
     *
     * @param ingredient the ingredient to check
     * @param branch     the branch containing the nodes
     * @return the nodes to search for the ingredient
     */
    private static @Nullable Map<AbstractMapIngredient, RecipeNode> nodesForIngredient(@NotNull AbstractMapIngredient ingredient,
                                                                                        @NotNull Branch branch,
                                                                                        boolean create) {
        if (ingredient.isSpecialIngredient()) {
            if (create) {
                return branch.getSpecialNodes();
            }
            return branch.getSpecialNodesIfPresent();
        }
        if (create) {
            return branch.getNodes();
        }
        return branch.getNodesIfPresent();
    }

    /**
     * Add a recipe.
     *
     * @param recipe      the recipe to add
     * @param ingredients the ingredients in optimal order, comprising the recipe
     * @return if successful
     */
    boolean add(@NotNull GTRecipe recipe, @NotNull List<@Unmodifiable List<AbstractMapIngredient>> ingredients) {
        // Add combustion fuels to the Powerless Jetpack
        if (recipe.getType() == GTRecipeTypes.COMBUSTION_GENERATOR_FUELS) {
            Content content = recipe.getInputContents(FluidRecipeCapability.CAP).get(0);
            FluidIngredient fluid = FluidRecipeCapability.CAP.of(content.content);
            PowerlessJetpack.FUELS.putIfAbsent(fluid, recipe.duration);
        }
        if (addRecursive(recipe, ingredients, rootBranch, 0)) {
            recipe.recipeCategory.addRecipe(recipe);
            return true;
        }
        return false;
    }

    /**
     * Recursively adds a recipe.
     *
     * @param recipe      the recipe to add
     * @param ingredients the ingredients to find the recipe with
     * @param branch      the branch to add ingredients to
     * @param index       the index of the ingredient list to check
     * @return if successful
     */
    private boolean addRecursive(@NotNull GTRecipe recipe,
                                 @NotNull List<@Unmodifiable List<AbstractMapIngredient>> ingredients,
                                 @NotNull Branch branch, int index) {
        if (index >= ingredients.size()) {
            return true;
        }
        boolean lastIngredient = index == ingredients.size() - 1;
        var current = ingredients.get(index);
        for (AbstractMapIngredient ingredient : current) {
            var nodes = nodesForIngredient(ingredient, branch, true);
            var node = nodes.compute(ingredient, (k, v) -> {
                if (lastIngredient) {
                    // last ingredient
                    if (v == null) {
                        // no existing leaf, add the recipe
                        return RecipeNode.recipe(recipe);
                    }
                    if (v.recipe == null || !v.recipe.equals(recipe)) {
                        // empty recipe or different recipe exists already, conflict
                        if (ConfigHolder.INSTANCE.dev.debug || GTCEu.isDev()) {
                            GTCEu.LOGGER.warn(
                                    "Recipe duplicate or conflict found in GTRecipeType {} and was not added. See next lines for details",
                                    ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType()));
                            if (v.recipe != null) {
                                GTCEu.LOGGER.warn("Attempted to add GTRecipe: {}, which conflicts with {}",
                                        recipe.getId(), v.recipe.getId());
                            } else {
                                GTCEu.LOGGER.warn("Attempted to add GTRecipe: {}, without exact duplicate/conflict",
                                        recipe.getId());
                            }
                        }
                    }
                    // maintain existing recipe, even on conflicts
                    // if there was no conflict but a recipe was still present, it was added on an earlier recurse,
                    // and this will carry the result further back in the call stack
                    return v;
                }
                // if there is an existing ingredient, use it, otherwise create a new branch for the ingredient
                return Objects.requireNonNullElseGet(v, () -> RecipeNode.branch(new Branch()));
            });
            if (node.recipe != null) {
                if (node.recipe == recipe) {
                    // recipe was successfully added, continue to add the other paths
                    continue;
                }
                // there was already a recipe here, fail on the conflict
                return false;
            }
            boolean added = node.branch != null && addRecursive(recipe, ingredients, node.branch, index + 1);
            if (!added) {
                if (lastIngredient) {
                    // remove the recipe
                    nodes.remove(ingredient);
                } else {
                    var child = nodes.get(ingredient);
                    if (child != null && child.branch != null) {
                        var childBranch = child.branch;
                        if (childBranch.isEmptyBranch()) {
                            // remove the branch if it was the only thing in it
                            nodes.remove(ingredient);
                        }
                    }
                }
                return false;
            }
        }
        return true;
    }

    public static class RecipeIterator implements Iterator<GTRecipe> {

        private final @NotNull RecipeDB db;
        private final @NotNull List<AbstractMapIngredient>[] ingredientSlots;
        private final int[] slotSizes;
        private final @NotNull Predicate<GTRecipe> predicate;

        private Branch[] branchStack;
        private int[] slotIndexStack;
        private int[] ingredientIndexStack;
        private int depth;

        private @Nullable GTRecipe nextCached = null;
        private boolean hasCached = false;

        @VisibleForTesting
        public RecipeIterator(@NotNull RecipeDB db,
                              @NotNull List<List<AbstractMapIngredient>> ingredients,
                              @NotNull Predicate<GTRecipe> predicate) {
            this.db = db;
            this.predicate = predicate;

            int slotCount = ingredients.size();
            //noinspection unchecked
            this.ingredientSlots = (List<AbstractMapIngredient>[]) new List<?>[slotCount];
            this.slotSizes = new int[slotCount];
            for (int i = 0; i < slotCount; i++) {
                var slot = ingredients.get(i);
                ingredientSlots[i] = slot;
                slotSizes[i] = slot.size();
            }

            this.branchStack = new Branch[8];
            this.slotIndexStack = new int[8];
            this.ingredientIndexStack = new int[8];
            reset();
        }

        private void ensureCapacity(int requiredDepth) {
            if (requiredDepth < branchStack.length) {
                return;
            }
            int newCapacity = branchStack.length;
            while (requiredDepth >= newCapacity) {
                newCapacity <<= 1;
            }
            branchStack = Arrays.copyOf(branchStack, newCapacity);
            slotIndexStack = Arrays.copyOf(slotIndexStack, newCapacity);
            ingredientIndexStack = Arrays.copyOf(ingredientIndexStack, newCapacity);
        }

        private @Nullable GTRecipe getNext() {
            int slotCount = ingredientSlots.length;

            while (depth >= 0) {
                int slotIndex = slotIndexStack[depth];
                if (slotIndex >= slotCount) {
                    depth--;
                    continue;
                }

                int ingredientIndex = ingredientIndexStack[depth];
                if (ingredientIndex >= slotSizes[slotIndex]) {
                    slotIndexStack[depth] = slotIndex + 1;
                    ingredientIndexStack[depth] = 0;
                    continue;
                }

                ingredientIndexStack[depth] = ingredientIndex + 1;
                AbstractMapIngredient ingredient = ingredientSlots[slotIndex].get(ingredientIndex);

                var nodes = nodesForIngredient(ingredient, branchStack[depth], false);
                if (nodes == null) {
                    continue;
                }
                var result = nodes.get(ingredient);
                if (result == null) {
                    continue;
                }

                var recipe = result.recipe;
                if (recipe != null && predicate.test(recipe)) {
                    return recipe;
                }

                var child = result.branch;
                if (child != null) {
                    int nextDepth = depth + 1;
                    ensureCapacity(nextDepth);
                    depth = nextDepth;
                    branchStack[depth] = child;
                    slotIndexStack[depth] = 0;
                    ingredientIndexStack[depth] = 0;
                }
            }

            return null; // no more recipes
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
            if (!hasCached) nextCached = getNext();
            hasCached = false;
            if (nextCached == null) throw new NoSuchElementException();
            return nextCached;
        }

        /**
         * Reset the iterator
         */
        public void reset() {
            depth = 0;
            branchStack[0] = db.rootBranch;
            slotIndexStack[0] = 0;
            ingredientIndexStack[0] = 0;
            nextCached = null;
            hasCached = false;
        }
    }
}
