package cn.li.forge1201.entity;

public final class ScriptedBlockBodySpec {
    private final String defaultBlockId;
    private final double gravity;
    private final double damage;
    private final boolean placeWhenCollide;
    private final String rendererId;
    private final String hookId;

    public ScriptedBlockBodySpec(String defaultBlockId,
                                 double gravity,
                                 double damage,
                                 boolean placeWhenCollide,
                                 String rendererId,
                                 String hookId) {
        this.defaultBlockId = defaultBlockId == null ? "minecraft:stone" : defaultBlockId;
        this.gravity = gravity;
        this.damage = damage;
        this.placeWhenCollide = placeWhenCollide;
        this.rendererId = rendererId == null ? "" : rendererId;
        this.hookId = hookId == null ? "" : hookId;
    }

    public String getDefaultBlockId() {
        return defaultBlockId;
    }

    public double getGravity() {
        return gravity;
    }

    public double getDamage() {
        return damage;
    }

    public boolean isPlaceWhenCollide() {
        return placeWhenCollide;
    }

    public String getRendererId() {
        return rendererId;
    }

    public String getHookId() {
        return hookId;
    }
}
