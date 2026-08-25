package cn.li.mcmod.runtime.vfx;

/** Physical channels reserved exclusively for the typed VFX protocol. */
public final class VfxChannels {
    public static final String SERVER_TO_CLIENT = "academy:vfx_s2c";
    public static final String CLIENT_TO_SERVER = "academy:vfx_c2s";
    private VfxChannels() {}
}