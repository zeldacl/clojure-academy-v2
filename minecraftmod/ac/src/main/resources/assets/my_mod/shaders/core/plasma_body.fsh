#version 150

uniform int ballCount;
uniform vec4 balls[16];
uniform float alpha;
uniform vec4 ColorModulator;

in vec3 camspace;

out vec4 fragColor;

float field_density(vec3 position) {
    float ret = 0.0;
    for (int i = 0; i < ballCount; ++i) {
        float dist = max(0.1, length(position - balls[i].xyz));
        ret += alpha * balls[i].w / (dist * dist);
    }
    return clamp(ret, 0.0, 2.0);
}

vec4 ray_march(vec3 begin, vec3 dir) {
    dir *= 0.15;
    vec3 pos = begin;
    vec4 accum = vec4(0.0);
    for (int i = 0; i < 20 && accum.a < 1.0; ++i) {
        float density = field_density(pos);
        float a = 0.075 * density;
        vec3 c = mix(vec3(0.43, 0.74, 1.0), vec3(0.98, 0.51, 0.92), 1.0 - density / 2.0);
        accum.rgb = mix(accum.rgb, c, a / max(0.001, accum.a + a));
        accum.a += a;
        pos += dir;
    }

    if (accum.a < 0.2) {
        accum.a = 2.0 * accum.a - 0.2;
    }
    return accum;
}

void main() {
    vec3 cam = vec3(camspace.x, camspace.y, -camspace.z);
    vec3 dir = normalize(cam);
    vec4 rc = ray_march(cam - dir * 3.0, dir);
    rc.a = clamp(rc.a, 0.0, 1.0) * (0.5 + alpha * 0.5);
    fragColor = rc * ColorModulator;
}
