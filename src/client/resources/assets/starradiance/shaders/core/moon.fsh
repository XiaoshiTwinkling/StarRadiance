#version 150

uniform sampler2D Sampler0; // equirectangular lunar albedo (LROC)
uniform sampler2D Sampler1; // tangent-space normal map (from LOLA elevation)
uniform vec4 ColorModulator;
uniform vec3 SunDirLocal;   // sun direction expressed in the moon's local frame
uniform vec4 MoonParams;    // rgb = tint (reddens during a lunar eclipse), a = normal strength
uniform vec4 MoonShadow;    // xy = shadow axis offset in moon-radius units, z = umbra ratio, w = penumbra ratio

in vec2 texCoord0;
in vec3 localPos;

out vec4 fragColor;

void main() {
    vec3 albedo = texture(Sampler0, texCoord0).rgb;

    // Geometric normal and an analytic tangent from the u->longitude mapping.
    vec3 N = normalize(localPos);
    float lon = (texCoord0.x - 0.5) * 6.28318530718;
    vec3 T = normalize(vec3(cos(lon), 0.0, -sin(lon)));
    vec3 B = cross(N, T);

    vec3 nTex = texture(Sampler1, texCoord0).rgb * 2.0 - 1.0;
    nTex.xy *= MoonParams.a;
    vec3 Np = normalize(mat3(T, B, N) * nTex);

    // Per-pixel sun lighting: real phases and terminator emerge from geometry alone. The lit
    // fraction rides in alpha so the unlit side is transparent — otherwise a new moon renders as
    // an opaque black disk (and, during a solar eclipse, a black disk over the sun).
    float diff = max(dot(Np, normalize(SunDirLocal)), 0.0);
    vec3 color = albedo * MoonParams.rgb;

    // Lunar eclipse: darken the part of the disc inside the Earth's penumbra and umbra, using the
    // real shadow geometry (the sphere itself is drawn enlarged).
    if (MoonShadow.z > 0.0) {
        float distanceToAxis = length(localPos.xy - MoonShadow.xy);
        float inUmbra = 1.0 - smoothstep(MoonShadow.z - 0.03, MoonShadow.z + 0.03, distanceToAxis);
        float inPenumbra = 1.0 - smoothstep(MoonShadow.w - 0.03, MoonShadow.w + 0.03, distanceToAxis);
        color *= mix(1.0, 0.35, inPenumbra);
        color *= mix(1.0, 0.28, inUmbra);
        color = mix(color, color * vec3(1.0, 0.42, 0.30), inUmbra * 0.9);
    }
    fragColor = vec4(color, diff) * ColorModulator;
}
