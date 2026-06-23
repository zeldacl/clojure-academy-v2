#version 150

uniform sampler2D TexSampler0;
uniform sampler2D TexSampler1;
uniform float Progress;

in vec2 uv;

out vec4 fragColor;

void main() {
    float threshold = texture(TexSampler1, uv).r;
    fragColor = Progress > threshold ? texture(TexSampler0, uv) : vec4(0.0);
}
