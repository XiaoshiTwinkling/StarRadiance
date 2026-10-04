#version 150

uniform vec4 ColorModulator;
uniform vec4 SunParams; // x = brightness (incl. eclipse/rain), y = glow strength, z = disk radius (0..1 of half-quad), w = warmth
uniform vec4 SunShadow; // xy = Moon centre offset in disk-radius units, z = Moon/Sun radius ratio, w = 1 when a solar eclipse is in progress

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    float d = length(texCoord0 - 0.5) * 2.0; // 0 at centre, 1.0 at the edge midpoint, 1.41 at the corner
    float R = SunParams.z;

    // The sun is generated procedurally: vanilla's sun texture has a square bright core, which is
    // exactly what made the sun look like a block when it was sampled through the disk mask.
    float mask;
    if (d < R) {
        float r = d / R;
        float mu = sqrt(max(0.0, 1.0 - r * r));
        mask = 1.0 - 0.5 * (1.0 - mu); // photosphere with limb darkening
    } else {
        mask = SunParams.y * exp(-(d - R) * 10.0); // soft corona
    }
    if (d > 1.0) {
        discard;
    }
    mask *= SunParams.x;

    // Solar eclipse: the Moon's silhouette is carved out of the photosphere and corona using the
    // real relative geometry (the discs themselves are drawn enlarged).
    if (SunShadow.w > 0.5) {
        vec2 disc = (texCoord0 - 0.5) * 2.0 / R;
        float distanceToMoon = length(disc - SunShadow.xy);
        float covered = 1.0 - smoothstep(SunShadow.z - 0.03, SunShadow.z + 0.03, distanceToMoon);
        mask *= 1.0 - covered;
    }

    vec3 core = vec3(1.0, 0.97, 0.90);
    vec3 edge = vec3(1.0, 0.66, 0.32);
    float heat = clamp((d - R * 0.5) / (1.0 - R * 0.5), 0.0, 1.0) * SunParams.w;
    vec3 color = mix(core, edge, heat) * mask;
    fragColor = vec4(color, 1.0) * ColorModulator;
}
