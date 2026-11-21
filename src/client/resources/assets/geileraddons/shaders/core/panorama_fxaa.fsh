#version 330

// Custom Main Menu desert edge smoothing: a small FXAA-style pass (after Timothy Lottes' FXAA, written
// from the published algorithm). Finds contrast edges in the finished desert and blends along them,
// so dune silhouettes against the sky are not stair-stepped.

uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 px = 1.0 / vec2(textureSize(InSampler, 0));
    vec3 rgbM = texture(InSampler, texCoord).rgb;
    float lM = luma(rgbM);
    float lNW = luma(texture(InSampler, texCoord + vec2(-1.0, -1.0) * px).rgb);
    float lNE = luma(texture(InSampler, texCoord + vec2(1.0, -1.0) * px).rgb);
    float lSW = luma(texture(InSampler, texCoord + vec2(-1.0, 1.0) * px).rgb);
    float lSE = luma(texture(InSampler, texCoord + vec2(1.0, 1.0) * px).rgb);
    float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));
    float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
    if (lMax - lMin < max(0.03, lMax * 0.1)) {
        fragColor = vec4(rgbM, 1.0);
        return;
    }
    vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), (lNW + lSW) - (lNE + lSE));
    float reduce = max((lNW + lNE + lSW + lSE) * 0.25 * 0.125, 1.0 / 128.0);
    float scale = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
    dir = clamp(dir * scale, vec2(-8.0), vec2(8.0)) * px;
    vec3 a = 0.5 * (texture(InSampler, texCoord + dir * (1.0 / 3.0 - 0.5)).rgb
        + texture(InSampler, texCoord + dir * (2.0 / 3.0 - 0.5)).rgb);
    vec3 b = a * 0.5 + 0.25 * (texture(InSampler, texCoord - dir * 0.5).rgb
        + texture(InSampler, texCoord + dir * 0.5).rgb);
    float lB = luma(b);
    fragColor = vec4((lB < lMin || lB > lMax) ? a : b, 1.0);
}
