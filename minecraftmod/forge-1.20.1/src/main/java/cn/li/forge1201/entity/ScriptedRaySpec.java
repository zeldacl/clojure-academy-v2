package cn.li.forge1201.entity;

public final class ScriptedRaySpec {
    private final int lifeTicks;
    private final double length;
    private final double blendInMs;
    private final double blendOutMs;
    private final double innerWidth;
    private final double outerWidth;
    private final double glowWidth;
    private final int startColor;
    private final int endColor;
    private final String rendererId;
    private final String hookId;

    public ScriptedRaySpec(int lifeTicks,
                           double length,
                           double blendInMs,
                           double blendOutMs,
                           double innerWidth,
                           double outerWidth,
                           double glowWidth,
                           int startColor,
                           int endColor,
                           String rendererId,
                           String hookId) {
        this.lifeTicks = Math.max(1, lifeTicks);
        this.length = Math.max(0.0D, length);
        this.blendInMs = Math.max(0.0D, blendInMs);
        this.blendOutMs = Math.max(0.0D, blendOutMs);
        this.innerWidth = Math.max(0.001D, innerWidth);
        this.outerWidth = Math.max(0.001D, outerWidth);
        this.glowWidth = Math.max(0.001D, glowWidth);
        this.startColor = startColor;
        this.endColor = endColor;
        this.rendererId = rendererId == null ? "" : rendererId;
        this.hookId = hookId == null ? "" : hookId;
    }

    public int getLifeTicks() {
        return lifeTicks;
    }

    public double getLength() {
        return length;
    }

    public double getBlendInMs() {
        return blendInMs;
    }

    public double getBlendOutMs() {
        return blendOutMs;
    }

    public double getInnerWidth() {
        return innerWidth;
    }

    public double getOuterWidth() {
        return outerWidth;
    }

    public double getGlowWidth() {
        return glowWidth;
    }

    public int getStartColor() {
        return startColor;
    }

    public int getEndColor() {
        return endColor;
    }

    public String getRendererId() {
        return rendererId;
    }

    public String getHookId() {
        return hookId;
    }
}
