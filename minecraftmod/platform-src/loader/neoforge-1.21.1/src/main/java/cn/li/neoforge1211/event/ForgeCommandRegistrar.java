package cn.li.neoforge1211.event;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Var;
import cn.li.mc1211.clj.ClojureInterop;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

final class ForgeCommandRegistrar {
    private ForgeCommandRegistrar() {
    }

    static void registerAll(RegisterCommandsEvent event, Logger logger) {
        try {
            ClojureInterop.requireNamespace("cn.li.neoforge1211.commands");

            Var handler = (Var) Clojure.var("cn.li.neoforge1211.commands", "register-all-commands");
            if (!handler.isBound()) {
                throw new IllegalStateException("register-all-commands is unbound after require");
            }

            handler.invoke(event.getDispatcher(), event.getBuildContext());
        } catch (Throwable t) {
            logger.error("[ForgeEventBusManager] Failed to register commands", t);
        }
    }
}
