package cn.li.mcmod.runtime.effect;

/** Startup-linked host function table. Clojure owns all dispatch logic. */
public final class HostTable {
    public final Object[] queryHandlers;
    public final Object preflightHandler;
    public final Object commitHandler;

    public HostTable(Object[] queryHandlers,
                     Object preflightHandler,
                     Object commitHandler) {
        this.queryHandlers = queryHandlers;
        this.preflightHandler = preflightHandler;
        this.commitHandler = commitHandler;
    }
}
