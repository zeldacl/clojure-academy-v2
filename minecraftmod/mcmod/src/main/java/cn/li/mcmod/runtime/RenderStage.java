package cn.li.mcmod.runtime;

/**
 * Single neutral render stage vocabulary shared by the Presentation frame
 * pipeline and VFX Core. Order is the default frame-graph adjacency: each
 * stage feeds the next unless a caller supplies an explicit graph.
 */
public enum RenderStage {
    WORLD_AFTER_SKY,
    WORLD_BEFORE_TRANSLUCENT,
    WORLD_AFTER_TRANSLUCENT,
    WORLD_ALWAYS_ON_TOP,
    WORLD_GLOW,
    FIRST_PERSON,
    HUD_UNDERLAY,
    HUD,
    HUD_OVERLAY,
    SCREEN,
    POST_PROCESS
}
