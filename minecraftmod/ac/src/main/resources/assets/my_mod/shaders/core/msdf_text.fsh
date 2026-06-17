#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

uniform float u_ThicknessOffset;
uniform vec4 u_OutlineColor;
uniform float u_OutlineWidth;
uniform vec4 u_GlowColor;
uniform float u_GlowRadius;
uniform vec2 u_ShadowOffset;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 texCoord2;

out vec4 fragColor;

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

vec4 sampleField(vec2 uv) {
    return texture(Sampler0, uv);
}

void main() {
    vec4 field = sampleField(texCoord0);
    vec3 msd = field.rgb;
    float sdShape = median(msd.r, msd.g, msd.b);
    float sdEffects = field.a;

    float w = clamp(0.5 / length(vec2(dFdx(sdShape), dFdy(sdShape))), 0.0, 0.5);

    float fontBase = 0.5 - u_ThicknessOffset;
    float fontAlpha = smoothstep(fontBase - w, fontBase + w, sdShape);

    float outlineBase = fontBase - u_OutlineWidth;
    float outlineAlpha = smoothstep(outlineBase - w, outlineBase + w, sdEffects);

    float glowBase = outlineBase - u_GlowRadius;
    float glowAlpha = smoothstep(glowBase, fontBase, sdEffects);

    vec4 finalColor = vec4(0.0);

    if (u_GlowRadius > 0.0) {
        finalColor = mix(finalColor, u_GlowColor, glowAlpha * u_GlowColor.a);
    }

    if (u_OutlineWidth > 0.0) {
        finalColor = mix(finalColor, u_OutlineColor, outlineAlpha * u_OutlineColor.a);
    }

    if (length(u_ShadowOffset) > 0.001) {
        vec4 shadowField = sampleField(texCoord0 - u_ShadowOffset);
        float shadowD = shadowField.a;
        float shadowAlpha = smoothstep(glowBase, fontBase, shadowD);
        finalColor = mix(finalColor, u_GlowColor, shadowAlpha * u_GlowColor.a * 0.5);
    }

    finalColor = mix(finalColor, vertexColor, fontAlpha);

    if (finalColor.a < 0.01) {
        discard;
    }

    finalColor *= ColorModulator;
    fragColor = linear_fog(finalColor, vertexDistance, FogStart, FogEnd, FogColor);
}
