#version 150

uniform sampler2D Sampler0;

in vec2 texCoord;
in vec4 blurDir;

out vec4 fragColor;

// Separable 13-tap Gaussian (sigma about 3 texels). The tap spacing comes from the source
// texture's own size, so no extra uniform has to be plumbed through.
void main() {
    vec2 texel = 1.0 / vec2(textureSize(Sampler0, 0));
    vec2 offset = texel * blurDir.xy;

    vec4 sum = texture(Sampler0, texCoord) * 0.18;
    float total = 0.18;
    for (int i = 1; i <= 6; i++) {
        float weight = exp(-float(i * i) / 9.0);
        sum += texture(Sampler0, texCoord + offset * float(i)) * weight;
        sum += texture(Sampler0, texCoord - offset * float(i)) * weight;
        total += 2.0 * weight;
    }
    fragColor = sum / total;
}
