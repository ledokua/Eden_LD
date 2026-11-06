package net.ledok.eden_ld.world;

/** Recipe-unlock advancement ids ({@code <namespace>:recipes/…}), which {@code saving.json} can leave unloaded. */
public final class RecipeAdvancements {

    private RecipeAdvancements() {
    }

    /** An advancement id as text (a player file key): {@code namespace:recipes/…}. */
    public static boolean isRecipeAdvancement(String id) {
        int colon = id.indexOf(':');
        return id.startsWith("recipes/", colon + 1);
    }
}
