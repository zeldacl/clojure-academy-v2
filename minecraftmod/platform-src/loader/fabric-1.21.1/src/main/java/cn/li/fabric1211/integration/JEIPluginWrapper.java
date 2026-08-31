package cn.li.fabric1211.integration;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import cn.li.mcmod.ModId;
import cn.li.mcbase.clj.ClojureInterop;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.registration.*;
import net.minecraft.resources.ResourceLocation;
public final class JEIPluginWrapper implements IModPlugin {
    private static final ResourceLocation UID = ResourceLocation.parse(ModId.ID + ":content_plugin");
    private final IModPlugin delegate;
    public JEIPluginWrapper() {
        try { ClojureInterop.requireNamespace("cn.li.fabric1211.integration.jei-impl"); IFn f = Clojure.var("cn.li.fabric1211.integration.jei-impl", "create-jei-plugin"); delegate = (IModPlugin) f.invoke(); }
        catch (Exception ex) { throw new IllegalStateException("Failed to initialize AcademyCraft JEI integration", ex); }
    }
    @Override public ResourceLocation getPluginUid() { return UID; }
    @Override public void registerCategories(IRecipeCategoryRegistration r) { delegate.registerCategories(r); }
    @Override public void registerRecipes(IRecipeRegistration r) { delegate.registerRecipes(r); }
    @Override public void registerRecipeCatalysts(IRecipeCatalystRegistration r) { delegate.registerRecipeCatalysts(r); }
}