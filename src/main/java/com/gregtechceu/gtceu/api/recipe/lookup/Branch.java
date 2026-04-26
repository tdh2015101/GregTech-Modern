package com.gregtechceu.gtceu.api.recipe.lookup;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.lookup.ingredient.AbstractMapIngredient;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

@ApiStatus.Internal
final class Branch {

    // Keys on this have *(should)* have unique hashcodes.
    private Map<AbstractMapIngredient, RecipeNode> nodes;
    // Keys on this have collisions, and must be differentiated by equality.
    private Map<AbstractMapIngredient, RecipeNode> specialNodes;

    public boolean isEmptyBranch() {
        return (nodes == null || nodes.isEmpty()) && (specialNodes == null || specialNodes.isEmpty());
    }

    @NotNull
    public Map<AbstractMapIngredient, RecipeNode> getNodes() {
        if (nodes == null) {
            nodes = new Object2ObjectOpenHashMap<>(2);
        }
        return nodes;
    }

    @Nullable
    public Map<AbstractMapIngredient, RecipeNode> getNodesIfPresent() {
        return nodes;
    }

    @NotNull
    public Map<AbstractMapIngredient, RecipeNode> getSpecialNodes() {
        if (specialNodes == null) {
            specialNodes = new Object2ObjectOpenHashMap<>(2);
        }
        return specialNodes;
    }

    @Nullable
    public Map<AbstractMapIngredient, RecipeNode> getSpecialNodesIfPresent() {
        return specialNodes;
    }

    /**
     * Removes all nodes in the branch
     */
    public void clear() {
        this.specialNodes = null;
        this.nodes = null;
    }
}

@ApiStatus.Internal
final class RecipeNode {

    final @Nullable GTRecipe recipe;
    final @Nullable Branch branch;

    private RecipeNode(@Nullable GTRecipe recipe, @Nullable Branch branch) {
        this.recipe = recipe;
        this.branch = branch;
    }

    static @NotNull RecipeNode recipe(@NotNull GTRecipe recipe) {
        return new RecipeNode(recipe, null);
    }

    static @NotNull RecipeNode branch(@NotNull Branch branch) {
        return new RecipeNode(null, branch);
    }
}
