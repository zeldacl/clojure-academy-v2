package cn.li.forge1201.integration;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import com.blamejared.crafttweaker.api.CraftTweakerAPI;
import com.blamejared.crafttweaker.api.action.base.IAction;
import com.blamejared.crafttweaker.api.annotation.ZenRegister;
import com.blamejared.crafttweaker.api.item.IItemStack;
import org.openzen.zencode.java.ZenCodeType;

/**
 * CraftTweaker support for Imag Fusor recipes.
 *
 * This class provides ZenScript methods for adding/removing Imag Fusor recipes.
 * The actual implementation is in cn.li.forge1201.integration.crafttweaker-impl namespace.
 *
 * Usage in CraftTweaker scripts:
 * <code>
 * import mods.academycraft.ImagFusor;
 * ImagFusor.addRecipe(<minecraft:diamond>, <minecraft:coal>, 10000);
 * </code>
 */
@ZenRegister
@ZenCodeType.Name("mods.academycraft.ImagFusor")
public class ImagFusorSupport {

    private static IFn addRecipeFn;
    private static IFn removeRecipeFn;

    static {
        try {
            // Load the Clojure namespace
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read("cn.li.forge1201.integration.crafttweaker-impl"));

            // Get the recipe functions
            addRecipeFn = Clojure.var("cn.li.forge1201.integration.crafttweaker-impl", "add-fusor-recipe");
            removeRecipeFn = Clojure.var("cn.li.forge1201.integration.crafttweaker-impl", "remove-fusor-recipe");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize CraftTweaker Imag Fusor support", e);
        }
    }

    /**
     * Add an Imag Fusor recipe.
     *
     * @param output Output item
     * @param input Input item
     * @param energy Energy cost in IF (Imaginary Energy)
     */
    @ZenCodeType.Method
    public static void addRecipe(IItemStack output, IItemStack input, int energy) {
        CraftTweakerAPI.apply(new AddImagFusorRecipe(output, input, energy));
    }

    /**
     * Remove an Imag Fusor recipe by output item.
     *
     * @param output Output item to remove
     */
    @ZenCodeType.Method
    public static void removeRecipe(IItemStack output) {
        CraftTweakerAPI.apply(new RemoveImagFusorRecipe(output));
    }

    private static class AddImagFusorRecipe implements IAction {
        private final IItemStack output;
        private final IItemStack input;
        private final int energy;

        public AddImagFusorRecipe(IItemStack output, IItemStack input, int energy) {
            this.output = output;
            this.input = input;
            this.energy = energy;
        }

        @Override
        public void apply() {
            addRecipeFn.invoke(output, input, energy);
        }

        @Override
        public String describe() {
            return "Add Imag Fusor recipe: " + input.getCommandString() + " -> " + output.getCommandString();
        }

        @Override
        public String systemName() {
            return "academycraft:add_imag_fusor_recipe";
        }
    }

    private static class RemoveImagFusorRecipe implements IAction {
        private final IItemStack output;

        public RemoveImagFusorRecipe(IItemStack output) {
            this.output = output;
        }

        @Override
        public void apply() {
            removeRecipeFn.invoke(output);
        }

        @Override
        public String describe() {
            return "Remove Imag Fusor recipe for: " + output.getCommandString();
        }

        @Override
        public String systemName() {
            return "academycraft:remove_imag_fusor_recipe";
        }
    }
}
