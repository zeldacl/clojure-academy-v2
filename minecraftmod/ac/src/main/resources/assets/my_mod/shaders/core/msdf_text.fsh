#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

uniform float u_ThicknessOffset;
uniform float u_BoldThickness;
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

    int blueBits = int(floor(vertexColor.b * 255.0 + 0.5));
    int glyphFlags = blueBits & 7;
    vec3 textRgb = vec3(vertexColor.r, vertexColor.g, float(blueBits & ~7) / 255.0);

    float thicknessOffset = u_ThicknessOffset;
    float outlineWidth = u_OutlineWidth;
    float glowRadius = u_GlowRadius;
    if (glyphFlags != 0) {
        if ((glyphFlags & 1) != 0) {
            thicknessOffset = u_BoldThickness;
        } else {
            thicknessOffset = 0.0;
        }
        outlineWidth = ((glyphFlags & 2) != 0) ? u_OutlineWidth : 0.0;
        glowRadius = ((glyphFlags & 4) != 0) ? u_GlowRadius : 0.0;
    }

    float fontBase = 0.5 - thicknessOffset;
    float fontAlpha = smoothstep(fontBase - w, fontBase + w, sdShape);

    float outlineBase = fontBase - outlineWidth;
    float outlineAlpha = smoothstep(outlineBase - w, outlineBase + w, sdEffects);

    float glowBase = outlineBase - glowRadius;
    float glowAlpha = smoothstep(glowBase, fontBase, sdEffects);

    vec4 finalColor = vec4(0.0);

    if (glowRadius > 0.0) {
        finalColor = mix(finalColor, u_GlowColor, glowAlpha * u_GlowColor.a);
    }

    if (outlineWidth > 0.0) {
        finalColor = mix(finalColor, u_OutlineColor, outlineAlpha * u_OutlineColor.a);
    }

    if (length(u_ShadowOffset) > 0.001) {
        vec4 shadowField = sampleField(texCoord0 - u_ShadowOffset);
        float shadowD = shadowField.a;
        float shadowAlpha = smoothstep(glowBase, fontBase, shadowD);
        finalColor = mix(finalColor, u_GlowColor, shadowAlpha * u_GlowColor.a * 0.5);
    }

    finalColor = mix(finalColor, vec4(textRgb, 1.0), fontAlpha);

    if (finalColor.a < 0.01) {
        discard;
    }

    finalColor *= ColorModulator;
    fragColor = linear_fog(finalColor, vertexDistance, FogStart, FogEnd, FogColor);
}
