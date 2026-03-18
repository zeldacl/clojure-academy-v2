package my_mod;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Var;
import net.minecraftforge.fml.common.Mod;

/**
 * Minimal Java bridge for @Mod annotation.
 * All logic implemented in my-mod.forge1201.mod Clojure namespace.
 */
@Mod(MyMod1201.MODID)
public class MyMod1201 {
    public static final String MODID = "my_mod";

    private static boolean isDataGenRun() {
        String cmd = System.getProperty("sun.java.command", "");
        return cmd.contains("forgedata") || cmd.contains("runData");
    }

    public MyMod1201() {
        // Load and instantiate the Clojure mod class for both normal run and datagen.
        // Datagen also needs platform registration state for official Forge model providers.
        try {
            Var warnVar = clojure.lang.RT.var("clojure.core", "*warn-on-reflection*");
            warnVar.bindRoot(true); 
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read("my-mod.forge1201.mod"));

            IFn initFn = Clojure.var("my-mod.forge1201.mod", "mod-init");
            initFn.invoke();
        } catch (Throwable t) {
            System.err.println("Failed to load Clojure mod implementation:");
            t.printStackTrace();
        }
    }
}
