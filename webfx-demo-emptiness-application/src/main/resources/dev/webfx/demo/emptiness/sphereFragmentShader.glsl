#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec4 uColor;      // rgb = base color, a = opacity (1 = solid, < 1 = dissolving into its parts)
uniform vec3 uLightDirection; // in view space, pointing towards the light

varying vec3 vNormal;
varying vec3 vViewPosition;

void main(void) {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(-vViewPosition);
    vec3 l = normalize(uLightDirection);

    float diffuse = max(dot(n, l), 0.0);
    float specular = pow(max(dot(n, normalize(l + v)), 0.0), 48.0);
    float rim = 1.0 - max(dot(n, v), 0.0);

    vec3 color = uColor.rgb * (0.16 + 0.84 * diffuse)
               + vec3(0.30) * specular
               + uColor.rgb * pow(rim, 3.0) * 0.55;

    // While a sphere dissolves, its center becomes transparent before its rim: the "solid" sphere reduces to a
    // thin appearance and then vanishes, revealing the parts it is made of.
    float alpha = pow(uColor.a, 1.0 + 1.6 * (1.0 - rim));

    gl_FragColor = vec4(color, alpha);
}
