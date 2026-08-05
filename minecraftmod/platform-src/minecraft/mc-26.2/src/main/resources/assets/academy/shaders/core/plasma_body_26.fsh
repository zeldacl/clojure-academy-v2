#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

in vec2 plasmaUv;
in vec4 plasmaColor;

out vec4 fragColor;

float plasma_noise(vec2 p, float phase) {
    float angular = sin(atan(p.y, p.x) * 7.0 + phase * 1.7);
    float ripples = sin(length(p) * 22.0 - phase * 2.4);
    float cells = sin(dot(p, vec2(13.7, 19.3)) + phase);
    return (angular + ripples + cells) / 3.0;
}

void main() {
    vec2 p = plasmaUv * 2.0 - 1.0;
    float radius = length(p);
    float phase = GameTime * 120.0;
    float noise = plasma_noise(p, phase);
    float shell = 1.0 - smoothstep(0.62 + noise * 0.06, 1.02 + noise * 0.08, radius);
    float core = 1.0 - smoothstep(0.0, 0.72, radius);
    float sparks = pow(max(0.0, sin((p.x - p.y) * 28.0 + phase * 3.0)), 12.0)
        * (1.0 - smoothstep(0.25, 0.95, radius));

    float opacity = clamp(shell * (0.42 + core * 0.72) + sparks * 0.22, 0.0, 1.0)
        * plasmaColor.a;
    if (opacity < 0.01) {
        discard;
    }

    vec3 cyan = vec3(0.43, 0.82, 1.0);
    vec3 magenta = vec3(0.98, 0.51, 0.92);
    vec3 procedural = mix(cyan, magenta, clamp(core + noise * 0.18, 0.0, 1.0));
    vec3 color = mix(procedural, plasmaColor.rgb, 0.35);
    color += vec3(0.32, 0.36, 0.48) * core + sparks * vec3(0.55, 0.72, 1.0);
    fragColor = vec4(color, opacity) * ColorModulator;
}
