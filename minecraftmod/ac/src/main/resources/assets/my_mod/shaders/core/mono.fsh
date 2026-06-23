#version 150

uniform sampler2D TexSampler0;

in vec2 uv;

out vec4 fragColor;

void main() {
    vec4 color = texture(TexSampler0, uv);
    float gray = (color.r + color.g + color.b) / 3.0;
    fragColor = vec4(gray, gray, gray, color.a);
}
