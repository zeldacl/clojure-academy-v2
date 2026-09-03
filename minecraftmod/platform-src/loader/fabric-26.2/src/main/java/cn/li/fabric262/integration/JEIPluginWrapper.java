package cn.li.fabric262.integration;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import cn.li.mcmod.ModId;
import cn.li.mcbase.clj.ClojureInterop;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.registration.*;
import net.minecraft.resources.Identifier;
public final class JEIPluginWrapper implements IModPlugin {
    private static final Identifier UID = Identifier.fromNamespaceAndPath(ModId.ID, "content_plugin");
    private final IModPlugin delegate;
    public JEIPluginWrapper() {
        try { ClojureInterop.requireNamespace("cn.li.fabric262.integration.jei-impl"); IFn f = Clojure.var("cn.li.fabric262.integration.jei-impl", "create-jei-plugin"); delegate = (IModPlugin) f.invoke(); }
        catch (Exception ex) { throw new IllegalStateException("Failed to initialize AcademyCraft JEI integration", ex); }
    }
    @Override public Identifier getPluginUid() { return UID; }
    @Override public void registerCategories(IRecipeCategoryRegistration r) { delegate.registerCategories(r); }
    @Override public void registerRecipes(IRecipeRegistration r) { delegate.registerRecipes(r); }
    @Override public void registerRecipeCatalysts(IRecipeCatalystRegistration r) { delegate.registerRecipeCatalysts(r); }
}