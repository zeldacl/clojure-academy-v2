package cn.li.mc1201.runtime;

/**
 * Loader-agnostic adapter seam for resolving world/level references.
 */
public abstract class WorldAccessAdapter {
    public abstract Object getLevel(Object server, String worldId);
}
