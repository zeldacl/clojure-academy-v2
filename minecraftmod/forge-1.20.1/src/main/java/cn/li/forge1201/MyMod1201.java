package cn.li.forge1201;

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
            require.invoke(Clojure.read("cn.li.forge1201.mod"));

            IFn initFn = Clojure.var("cn.li.forge1201.mod", "mod-init");
            initFn.invoke();
        } catch (Throwable t) {
            System.err.println("Failed to load Clojure mod implementation:");
            t.printStackTrace();
        }
    }
}

// public class ClojureLoader {
//     private static boolean initialized = false;

//     public static synchronized void bootstrap(String namespace, String function, Object... args) {
//         try {
//             // 1. 设置当前线程的类加载器，确保 Clojure 能看到 Mod 的类
//             Thread.currentThread().setContextClassLoader(ClojureLoader.class.getClassLoader());

//             if (!initialized) {
//                 // 2. 加载 Clojure 核心运行时
//                 Symbol.intern("clojure.core"); 
//                 initialized = true;
//             }

//             // 3. 加载指定的命名空间脚本 (对应 resources/com/example/mod/core.clj)
//             RT.loadResourceScript(namespace.replace('.', '/') + ".clj");

//             // 4. 获取并调用入口函数
//             Var entryPoint = RT.var(namespace, function);
//             if (args.length > 0) {
//                 entryPoint.applyTo(clojure.lang.LazilyPersistentVector.create(java.util.Arrays.asList(args)));
//             } else {
//                 entryPoint.invoke();
//             }
//         } catch (Exception e) {
//             throw new RuntimeException("Failed to bootstrap Clojure Mod!", e);
//         }
//     }
// }
