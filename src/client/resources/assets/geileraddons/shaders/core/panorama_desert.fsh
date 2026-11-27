#version 330

// Custom Main Menu desert at golden hour: a raymarched dune heightfield where several dune types blend
// by region, a low warm sun with long soft shadows, wind ripples near the camera, aerial haze and a
// distant mountain range. Per-frame inputs come from PanoramaMath.writeParams.
// terrain() must stay identical to PanoramaMath.terrainHeight, which places the camera above the sand.

uniform sampler2D NoiseSampler;

layout(std140) uniform PanoramaParams {
    vec4 Params;      // x loop time (s), y aspect, z pixel size in view units, w max march steps
    vec4 Camera;      // xy ground position (x, z), zw view direction (x, z)
    vec4 CameraExtra; // x bank, y focal length, z camera height
    vec4 LightDir;    // xyz towards the sun
    vec4 Accent;      // theme accent (sRGB)
    vec4 Background;  // theme panel background (sRGB)
    vec4 TextColor;   // theme text (sRGB)
    vec4 SandDark;    // trough sand (sRGB)
    vec4 SandLight;   // sunlit sand (sRGB)
    vec4 SkyColor;    // zenith sky (sRGB)
    vec4 SunsetColor; // horizon glow toward the sun (sRGB)
    vec4 Style0;      // x ridge, y wave, z star, w swell amplitude
    vec4 Style1;      // x ridge bias, y star bias, z dune height
};

in vec2 texCoord;
out vec4 fragColor;

const float FAR = 1400.0;
const float MAX_HEIGHT = 120.0;
const vec2 WIND = vec2(0.2425, 0.9701);
const vec2 CROSS = vec2(0.9435, -0.3313);
const vec3 SUN = vec3(1.9, 1.15, 0.62);

vec3 toLinear(vec3 c) { return pow(c, vec3(2.2)); }

// Four independent smooth noises at once, one per texture channel. Interpolated here in full float
// precision: hardware bilinear weights are only 8-bit, and the terrain normals turn those tiny height
// steps into visible stripes.
vec4 noise4(vec2 x) {
    vec2 p = floor(x);
    vec2 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    ivec2 i = ivec2(mod(p, 256.0));
    ivec2 j = (i + 1) & 255;
    vec4 a = texelFetch(NoiseSampler, i, 0);
    vec4 b = texelFetch(NoiseSampler, ivec2(j.x, i.y), 0);
    vec4 c = texelFetch(NoiseSampler, ivec2(i.x, j.y), 0);
    vec4 d = texelFetch(NoiseSampler, j, 0);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float noise(vec2 x) {
    return noise4(x).r;
}

// One family of dunes: crests across [dir] with a gentle windward slope and a steep lee face.
float duneProfile(vec2 p, vec2 dir, float freq, float warp) {
    float x = fract(dot(p, dir) * freq + warp);
    float profile = x < 0.75 ? x / 0.75 : (1.0 - x) / 0.25;
    return profile * profile * (1.5 - 0.5 * profile);
}

float smax(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (a - b) / k, 0.0, 1.0);
    return mix(b, a, h) + k * h * (1.0 - h);
}

// Region noise blends long linear ridges, crescent-broken transverse dunes and star-dune ridge networks,
// with flat sand sheets between fields, all riding broad swells.
float terrain(vec2 p) {
    vec4 n = noise4(p * 0.0016 + vec2(3.1, 7.4));
    vec4 m = noise4(p * 0.007 + vec2(9.2, 1.7));
    float ridges = duneProfile(p, CROSS, 0.0085, m.r * 1.3) * (14.0 + 18.0 * m.b) * Style0.x;
    float waves = duneProfile(p, WIND, 0.021, m.g * 2.0 + n.a * 1.2) * (5.0 + 9.0 * m.a) * Style0.y;
    float star = pow(1.0 - abs(m.r * 2.0 - 1.0), 6.0) * 36.0 * Style0.z;
    float linearWeight = smoothstep(0.38, 0.62, n.r + Style1.x);
    float starWeight = smoothstep(0.52, 0.72, n.g + Style1.y);
    float dunes = mix(waves, smax(waves * 0.5, ridges, 5.0), linearWeight);
    dunes = mix(dunes, smax(dunes * 0.6, star, 6.0), starWeight);
    dunes *= 1.0 - 0.7 * smoothstep(0.6, 0.75, n.b);
    float swell = ((n.a - 0.5) * 40.0 + (m.a - 0.5) * 8.0) * Style0.w;
    return swell + dunes * Style1.z;
}

vec3 normalAt(vec2 p, float e) {
    float hx = terrain(p + vec2(e, 0.0)) - terrain(p - vec2(e, 0.0));
    float hz = terrain(p + vec2(0.0, e)) - terrain(p - vec2(0.0, e));
    return normalize(vec3(-hx, 2.0 * e, -hz));
}

// Small relaxed steps that shrink with distance less than before, so thin far crests are not stepped
// over (the dashed crest lines). Running out of steps near the ground counts as ground, never as sky.
// A ray that misses reports in [cover] how close it passed the sand (0 at the hit threshold, 1 a pixel and a half
// away), and where ([tNear]), so a crest silhouette blends over about a pixel instead of stepping.
float march(vec3 ro, vec3 rd, int steps, float px, out bool hit, out float cover, out float tNear) {
    float t = 1.0;
    float tPrev = 1.0;
    float dPrev = 1.0;
    hit = false;
    cover = 1.0;
    tNear = FAR;
    for (int i = 0; i < 256; i++) {
        if (i >= steps) {
            hit = rd.y < 0.03;
            return t;
        }
        vec3 p = ro + rd * t;
        float d = p.y - terrain(p.xz);
        if (d < 0.0015 * t) {
            // The surface lies between the last point above it and this one: secant guess, then bisect.
            float lo = tPrev;
            float hi = t;
            float tm = d < 0.0 ? mix(lo, hi, dPrev / max(dPrev - d, 1e-4)) : t;
            for (int j = 0; j < 5; j++) {
                vec3 m = ro + rd * tm;
                if (m.y - terrain(m.xz) < 0.0) hi = tm; else lo = tm;
                tm = 0.5 * (lo + hi);
            }
            hit = true;
            return tm;
        }
        if (rd.y > 0.0 && p.y > MAX_HEIGHT) break;
        // 0 at the hit threshold, 1 a pixel and a half beyond it.
        float c = (d - 0.0015 * t) / (1.5 * t * px);
        if (c < cover) {
            cover = min(cover, c);
            tNear = t;
        }
        tPrev = t;
        dPrev = d;
        t += max(0.4 * d, 0.004 * t);
        if (t > FAR) break;
    }
    return FAR;
}

// Fixed start and steps (no per-pixel jitter), so shadow edges hold still as the camera moves.
float softShadow(vec3 ro, vec3 rd) {
    float res = 1.0;
    float t = 0.8;
    for (int i = 0; i < 28; i++) {
        vec3 p = ro + rd * t;
        if (p.y > MAX_HEIGHT) break;
        float d = p.y - terrain(p.xz);
        res = min(res, 8.0 * d / t);
        if (res < 0.01) break;
        t += clamp(d, 1.5, 40.0);
    }
    return smoothstep(0.0, 1.0, res);
}

// Horizon: warm toward the sun, dusty lilac away from it; the theme accent only tints it lightly.
vec3 horizonColor(vec3 rd, vec3 L, vec3 accent) {
    float toward = pow(0.5 + 0.5 * dot(normalize(rd.xz + vec2(1e-5)), normalize(L.xz)), 3.0);
    vec3 col = mix(vec3(0.56, 0.56, 0.70), toLinear(SunsetColor.rgb) * 1.1, toward);
    return mix(col, accent, 0.08);
}

vec3 hazeColor(vec3 rd, vec3 L, vec3 accent) {
    float s = max(dot(rd, L), 0.0);
    return horizonColor(rd, L, accent) + SUN * pow(s, 6.0) * 0.22;
}

vec3 skyColor(vec3 rd, vec3 L, vec3 accent, float px) {
    float y = max(rd.y, 0.0);
    vec3 col = mix(horizonColor(rd, L, accent), toLinear(SkyColor.rgb), pow(y, 0.55));
    float s = max(dot(rd, L), 0.0);
    col += SUN * (pow(s, 6.0) * 0.18 + pow(s, 64.0) * 0.35);
    col += SUN * smoothstep(0.99950, 0.99975, s) * 4.0;
    col = mix(col, hazeColor(rd, L, accent), exp(-y * 40.0) * 0.7);

    // Two hazy mountain ranges on the horizon, drawn here instead of marched: they are always far away.
    float az = atan(rd.z, rd.x);
    vec2 c = vec2(cos(az), sin(az));
    float farRidge = 0.012 + 0.05 * (noise(c * 2.5 + 11.0) * 0.6 + noise(c * 6.0 + 7.0) * 0.3 + noise(c * 13.0 + 3.0) * 0.1);
    float nearRidge = 0.004 + 0.03 * (noise(c * 4.0 + 41.0) * 0.6 + noise(c * 9.0 + 23.0) * 0.3 + noise(c * 19.0 + 5.0) * 0.1);
    vec3 haze = hazeColor(rd, L, accent);
    col = mix(col, mix(haze, vec3(0.44, 0.40, 0.54), 0.30), smoothstep(farRidge + px, farRidge - px, rd.y));
    col = mix(col, mix(haze, vec3(0.32, 0.27, 0.38), 0.45), smoothstep(nearRidge + px, nearRidge - px, rd.y));
    return col;
}

vec3 shadeGround(vec3 ro, vec3 rd, float t, vec3 L, vec3 accent, float px) {
    vec3 p = ro + rd * t;
    // World size of one pixel here: normals and detail are filtered to it, so nothing shimmers.
    float foot = t * px;
    vec3 n = normalAt(p.xz, max(0.2, foot));
    // At a grazing view one pixel covers far more sand along the ray than across it; detail fades by
    // that longer footprint, or the near dunes shimmer as the camera glides.
    float graze = max(abs(dot(n, rd)), 0.08);
    float footA = foot / graze;

    // Wind ripples on gentle windward slopes, faded out before they get smaller than a pixel.
    float ripple = (1.0 - smoothstep(0.08, 0.3, footA)) * smoothstep(0.8, 0.95, n.y);
    if (ripple > 0.0) {
        float w = dot(p.xz, WIND) * 4.4 + noise(p.xz * 0.4) * 5.0;
        n = normalize(n - vec3(WIND.x, 0.0, WIND.y) * cos(w) * 0.12 * ripple);
    }

    // Warm sand: broad colour drift, lighter crests, darker redder troughs, fine grain up close.
    vec4 broad = noise4(p.xz * 0.0016 + vec2(3.1, 7.4));
    float swell = (broad.a - 0.5) * 40.0 * Style0.w;
    float crest = smoothstep(swell - 2.0, swell + 26.0 * Style1.z, p.y);
    float drift = noise(p.xz * 0.004 + 5.0);
    vec3 albedo = mix(toLinear(SandDark.rgb), toLinear(SandLight.rgb), drift);
    albedo *= mix(vec3(0.82, 0.72, 0.66), vec3(1.06, 1.02, 0.98), crest);
    float grain = noise(p.xz * 0.05 + 2.0);
    albedo *= 1.0 + (grain - 0.5) * 0.18 * (1.0 - smoothstep(0.5, 2.0, footA));

    float sha = softShadow(p + vec3(0.0, 0.25, 0.0), L);
    float dif = clamp(dot(n, L), 0.0, 1.0) * sha;
    vec3 skyLight = vec3(0.32, 0.40, 0.62) * (0.55 + 0.45 * n.y);
    vec3 bounce = vec3(0.60, 0.36, 0.20) * clamp(dot(n, normalize(vec3(-L.x, 0.0, -L.z))), 0.0, 1.0) * 0.35;
    vec3 col = albedo * (SUN * dif + skyLight * 0.45 + bounce);
    col += SUN * pow(clamp(dot(reflect(rd, n), L), 0.0, 1.0), 20.0) * 0.08 * sha * (1.0 - 0.7 * smoothstep(0.3, 1.5, footA));

    // Aerial haze over distance plus thicker air low in the troughs (analytic integral of exp(-b*y)).
    float b = 0.045;
    float density = 0.004 * exp(-ro.y * b);
    float optical = abs(rd.y) < 1e-3
        ? density * t
        : density * (1.0 - exp(-t * rd.y * b)) / (rd.y * b);
    float fogAmt = 1.0 - exp(-(optical + t * 0.0011));
    fogAmt = max(fogAmt, smoothstep(FAR * 0.55, FAR, t));
    return mix(col, hazeColor(rd, L, accent), clamp(fogAmt, 0.0, 1.0));
}

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

float hash(vec2 p) {
    return texelFetch(NoiseSampler, ivec2(mod(floor(p), 256.0)), 0).g;
}

void main() {
    float aspect = Params.y;
    int steps = int(Params.w);
    float focal = CameraExtra.y;
    float px = Params.z / focal;
    vec3 accent = toLinear(Accent.rgb);
    vec3 L = normalize(LightDir.xyz);

    vec3 ro = vec3(Camera.x, CameraExtra.z, Camera.y);
    vec2 dir = normalize(Camera.zw);
    vec3 fwd = normalize(vec3(dir.x, -0.07, dir.y));
    vec3 right = normalize(cross(fwd, vec3(0.0, 1.0, 0.0)));
    vec3 up = cross(right, fwd);
    float bank = CameraExtra.x;
    vec3 r2 = right * cos(bank) + up * sin(bank);
    vec3 u2 = up * cos(bank) - right * sin(bank);

    vec2 uv = texCoord * 2.0 - 1.0;
    uv.x *= aspect;
    vec3 rd = normalize(fwd * focal + uv.x * r2 + uv.y * u2);

    bool hit;
    float cover;
    float tNear;
    float t = march(ro, rd, steps, px, hit, cover, tNear);
    vec3 col;
    if (hit) {
        col = shadeGround(ro, rd, t, L, accent, px);
    } else {
        col = rd.y < 0.0 ? hazeColor(rd, L, accent) : skyColor(rd, L, accent, px);
        // Just past a crest: part of this pixel is still sand.
        if (cover < 1.0 && tNear < FAR) {
            col = mix(shadeGround(ro, rd, tNear, L, accent, px), col, smoothstep(0.0, 1.0, max(cover, 0.0)));
        }
    }

    col = aces(col * 0.9);
    col = pow(col, vec3(1.0 / 2.2));

    // Soft vignette and a tiny dither against banding in the sky gradient.
    vec2 q = texCoord;
    col *= 0.6 + 0.4 * pow(16.0 * q.x * q.y * (1.0 - q.x) * (1.0 - q.y), 0.15);
    col += (hash(gl_FragCoord.xy) - 0.5) / 255.0;

    fragColor = vec4(col, 1.0);
}
