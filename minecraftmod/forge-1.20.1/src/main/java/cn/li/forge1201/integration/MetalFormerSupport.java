package cn.li.forge1201.integration;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import com.blamejared.crafttweaker.api.CraftTweakerAPI;
import com.blamejared.crafttweaker.api.action.base.IAction;
import com.blamejared.crafttweaker.api.annotation.ZenRegister;
import com.blamejared.crafttweaker.api.item.IItemStack;
import org.openzen.zencode.java.ZenCodeType;

/**
 * CraftTweaker support for Metal Former recipes.
 *
 * This class provides ZenScript methods for adding/removing Metal Former recipes.
 * The actual implementation is in cn.li.forge1201.integration.crafttweaker-impl namespace.
 *
 * Metal Former has three modes:
 * - Etch: Creates etched patterns
 * - Incise: Creates incised patterns
 * - Plate: Creates plates
 *
 * Usage in CraftTweaker scripts:
 * <code>
 * import mods.academycraft.MetalFormer;
 * MetalFormer.addEtchRecipe(<minecraft:iron_ingot>, <minecraft:iron_ore>);
 * MetalFormer.addInciseRecipe(<minecraft:gold_ingot>, <minecraft:gold_ore>);
 * MetalFormer.addPlateRecipe(<minecraft:diamond>, <minecraft:diamond_ore>);
 * </code>
 */
@ZenRegister
@ZenCodeType.Name("mods.academycraft.MetalFormer")
public class MetalFormerSupport {

    private static IFn addEtchFn;
    private static IFn addInciseFn;
    private static IFn addPlateFn;
    private static IFn removeRecipeFn;

    static {
        try {
            // Load the Clojure namespace
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read("cn.li.forge1201.integration.crafttweaker-impl"));

            // Get the recipe functions
            addEtchFn = Clojure.var("cn.li.forge1201.integration.crafttweaker-impl", "add-former-etch-recipe");
            addInciseFn = Clojure.var("cn.li.forge1201.integration.crafttweaker-impl", "add-former-incise-recipe");
            addPlateFn = Clojure.var("cn.li.forge1201.integration.crafttweaker-impl", "add-former-plate-recipe");
            removeRecipeFn = Clojure.var("cn.li.forge1201.integration.crafttweaker-impl", "remove-former-recipe");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize CraftTweaker Metal Former support", e);
        }
    }

    /**
     * Add a Metal Former etch recipe.
     *
     * @param output Output item
     * @param input Input item
     */
    @ZenCodeType.Method
    public static void addEtchRecipe(IItemStack output, IItemStack input) {
        CraftTweakerAPI.apply(new AddMetalFormerRecipe(output, input, "etch", addEtchFn));
    }

    /**
     * Add a Metal Former incise recipe.
     *
     * @param output Output item
     * @param input Input item
     */
    @ZenCodeType.Method
    public static void addInciseRecipe(IItemStack output, IItemStack input) {
        CraftTweakerAPI.apply(new AddMetalFormerRecipe(output, input, "incise", addInciseFn));
    }

    /**
     * Add a Metal Former plate recipe.
     *
     * @param output Output item
     * @param input Input item
     */
    @ZenCodeType.Method
    public static void addPlateRecipe(IItemStack output, IItemStack input) {
        CraftTweakerAPI.apply(new AddMetalFormerRecipe(output, input, "plate", addPlateFn));
    }

    /**
     * Remove a Metal Former recipe by output item.
     *
     * @param output Output item to remove
     */
    @ZenCodeType.Method
    public static void removeRecipe(IItemStack output) {
        CraftTweakerAPI.apply(new RemoveMetalFormerRecipe(output, null));
    }

    /**
     * Remove a Metal Former recipe by output item and mode.
     *
     * @param output Output item to remove
     * @param mode Mode string ("etch", "incise", or "plate")
     */
    @ZenCodeType.Method
    public static void removeRecipe(IItemStack output, String mode) {
        CraftTweakerAPI.apply(new RemoveMetalFormerRecipe(output, mode));
    }

    private static class AddMetalFormerRecipe implements IAction {
        private final IItemStack output;
        private final IItemStack input;
        private final String mode;
        private final IFn addFn;

        public AddMetalFormerRecipe(IItemStack output, IItemStack input, String mode, IFn addFn) {
            this.output = output;
            this.input = input;
            this.mode = mode;
            this.addFn = addFn;
        }

        @Override
        public void apply() {
            addFn.invoke(output, input);
        }

        @Override
        public String describe() {
            return "Add Metal Former recipe (" + mode + "): " + input.getCommandString() + " -> " + output.getCommandString();
        }

        @Override
        public String systemName() {
            return "academycraft:add_metal_former_recipe_" + mode;
        }
    }

    private static class RemoveMetalFormerRecipe implements IAction {
        private final IItemStack output;
        private final String mode;

        public RemoveMetalFormerRecipe(IItemStack output, String mode) {
            this.output = output;
            this.mode = mode;
        }

        @Override
        public void apply() {
            if (mode != null) {
                removeRecipeFn.invoke(output, mode);
            } else {
                removeRecipeFn.invoke(output);
            }
        }

        @Override
        public String describe() {
            String modeStr = mode != null ? " (mode: " + mode + ")" : "";
            return "Remove Metal Former recipe for: " + output.getCommandString() + modeStr;
        }

        @Override
        public String systemName() {
            return "academycraft:remove_metal_former_recipe";
        }
    }
}
