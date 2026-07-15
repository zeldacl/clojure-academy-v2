package cn.li.forge1201;

import cn.li.mcmod.ModId;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Var;
import net.minecraftforge.fml.common.Mod;

/**
 * Minimal Java bridge for @Mod annotation.
 * All logic implemented in cn.li.forge1201.mod Clojure namespace.
 */
@Mod(MyMod1201.MODID)
public class MyMod1201 {
    public static final String MODID = ModId.ID;

    public MyMod1201() {
        // Load and instantiate the Clojure mod class for both normal run and datagen.
        // Datagen also needs platform registration state for official Forge model providers.
        try {
            boolean forceReload = Boolean.getBoolean("ac.clj.reload");

            if (forceReload) {
                Var warnVar = clojure.lang.RT.var("clojure.core", "*warn-on-reflection*");
                warnVar.bindRoot(true);
            }

            IFn require = Clojure.var("clojure.core", "require");
            Object modNs = Clojure.read("cn.li.forge1201.mod");
            if (forceReload) {
                require.invoke(modNs, Clojure.read(":reload"));
            } else {
                require.invoke(modNs);
            }

            Var startFn = (Var) Clojure.var("cn.li.forge1201.mod", "start-forge-mod!");
            if (!startFn.isBound()) {
                if (forceReload) {
                    // Retry with full dependency reload for occasional namespace init races.
                    require.invoke(modNs, Clojure.read(":reload-all"));
                    startFn = (Var) Clojure.var("cn.li.forge1201.mod", "start-forge-mod!");
                }
            }

            if (startFn.isBound()) {
                startFn.invoke();
            } else {
                throw new IllegalStateException("Clojure bootstrap var is unbound: cn.li.forge1201.mod/start-forge-mod!");
            }
        } catch (Throwable t) {
            System.err.println("Failed to load Clojure mod implementation:");
            t.printStackTrace();
            throw new RuntimeException("Clojure mod initialization failed — terminating Minecraft startup", t);
        }
    }
}
