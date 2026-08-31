package cn.li.neoforge1211.integration;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import cn.li.neoforge1211.AcademyCraft1211;
import cn.li.mcbase.clj.ClojureInterop;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.*;
import net.minecraft.resources.ResourceLocation;

/** Typed JEI discovery seam. Optionality is provided by the loader dependency
 * graph: this class is only loaded when JEI is present. */
@JeiPlugin
public final class JEIPluginWrapper implements IModPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
            AcademyCraft1211.MODID, "content_plugin");
    private final IModPlugin delegate;

    public JEIPluginWrapper() {
        try {
            ClojureInterop.requireNamespace("cn.li.neoforge1211.integration.jei-impl");
            IFn factory = Clojure.var("cn.li.neoforge1211.integration.jei-impl", "create-jei-plugin");
            delegate = (IModPlugin) factory.invoke();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize AcademyCraft JEI integration", ex);
        }
    }

    @Override public ResourceLocation getPluginUid() { return UID; }
    @Override public void registerItemSubtypes(ISubtypeRegistration r) { delegate.registerItemSubtypes(r); }
    @Override public void registerCategories(IRecipeCategoryRegistration r) { delegate.registerCategories(r); }
    @Override public void registerRecipes(IRecipeRegistration r) { delegate.registerRecipes(r); }
    @Override public void registerRecipeCatalysts(IRecipeCatalystRegistration r) { delegate.registerRecipeCatalysts(r); }
    @Override public void registerGuiHandlers(IGuiHandlerRegistration r) { delegate.registerGuiHandlers(r); }
}
