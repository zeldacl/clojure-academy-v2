#version 150

uniform sampler2D TexSampler0;
uniform float AlphaThreshold;

in vec2 uv;

out vec4 fragColor;

void main() {
    vec4 color = texture(TexSampler0, uv);
    if (color.a < AlphaThreshold) discard;
    fragColor = color;
}
