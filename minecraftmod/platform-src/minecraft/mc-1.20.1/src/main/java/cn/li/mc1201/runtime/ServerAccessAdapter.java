package cn.li.mc1201.runtime;

/**
 * Loader-agnostic adapter seam for resolving current server instance.
 */
public abstract class ServerAccessAdapter {
    public abstract Object getCurrentServer();
}
