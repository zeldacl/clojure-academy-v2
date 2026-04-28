package cn.li.forge1201.entity;

public final class ScriptedEffectSpec {
    private final int lifeTicks;
    private final boolean followOwner;
    private final String rendererId;
    private final String effectHook;

    public ScriptedEffectSpec(int lifeTicks, boolean followOwner, String effectHook) {
        this(lifeTicks, followOwner, "effect-billboard", effectHook);
    }

    public ScriptedEffectSpec(int lifeTicks, boolean followOwner, String rendererId, String effectHook) {
        this.lifeTicks = Math.max(1, lifeTicks);
        this.followOwner = followOwner;
        this.rendererId = rendererId == null ? "" : rendererId;
        this.effectHook = effectHook == null ? "" : effectHook;
    }

    public int getLifeTicks() {
        return lifeTicks;
    }

    public boolean isFollowOwner() {
        return followOwner;
    }

    public String getRendererId() {
        return rendererId;
    }

    public String getEffectHook() {
        return effectHook;
    }
}
