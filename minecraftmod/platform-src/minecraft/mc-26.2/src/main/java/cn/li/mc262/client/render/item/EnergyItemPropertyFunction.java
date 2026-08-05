package cn.li.mc262.client.render.item;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 26.2 stub: ItemPropertyFunction removed in favor of item model properties.
 * Call sites should migrate to RangeSelectItemModelProperty / ConditionalItemModelProperty.
 */
@OnlyIn(Dist.CLIENT)
public enum EnergyItemPropertyFunction {
    INSTANCE;

    public float call(Object stack, Object level, Object entity, int seed) {
        return 0.0f;
    }
}
