package dev.webfx.demo.emptiness;

import dev.webfx.kit.webgl.*;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.resource.Resource;
import javafx.scene.canvas.Canvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The WebGL scene: a sphere made of spheres made of spheres... and a camera diving into it forever.
 *
 * <h3>Infinite zoom</h3>
 * All coordinates are expressed relative to the current "root" sphere (radius 1 at the origin). The camera dives along a
 * {@link ZoomPath} into one of the root parts (the target), parameterised by s in [0, 1]. When s reaches 1, the target
 * looks exactly like the root looked at s = 0, so the coordinates are rebased: the target becomes the new root, the old
 * root becomes an ancestor, and s goes back to 0. Nothing ever gets too big or too small, so the zoom never runs out of
 * precision. The sphere positions are computed relative to the camera in double precision and only then sent to WebGL.
 *
 * <h3>Level of detail = emptiness</h3>
 * Every sphere is rendered depending on q = (camera distance) / (sphere radius):
 * <ul>
 *     <li>q &gt; qOut: drawn as a simple solid sphere (its parts are not even computed);</li>
 *     <li>q &lt; qIn: not drawn at all, only its parts are drawn (recursively applying the same rule);</li>
 *     <li>in between: its parts are drawn, and the sphere itself is drawn over them with a decreasing opacity.</li>
 * </ul>
 * The parts fit inside their sphere, so they are hidden as long as the sphere is opaque, and are progressively revealed
 * as it dissolves. Because the same rule applies at every level, no sphere is ever found that is more than an appearance.
 *
 * <h3>Ancestors</h3>
 * The camera is inside the ancestors of the root, which are therefore always decomposed into their parts. A few
 * ancestors are kept to render the surroundings; spheres become transparent as they get very big relative to the
 * current scale (the "level fog"), so that the oldest ancestor can be dropped without anything visibly disappearing.
 *
 * @author Bruno Salmon
 */
final class EmptinessScene {

    // Background color (very dark blue)
    private static final double BG_R = 0.016, BG_G = 0.020, BG_B = 0.045;
    // Camera distance (in root radii) at the beginning of each zoom cycle
    private static final double D0 = 3.2;
    private static final double FIELD_OF_VIEW = Math.toRadians(50);
    // How far (in zoom cycles) the camera starts from the first sphere
    private static final double INTRO_LEVELS = 1.0;
    // Half-width (in zoom cycles) of the blending between two consecutive paths, to avoid a jolt when the target changes
    private static final double PATH_BLEND = 0.3;
    // Number of different layouts (the layout of a sphere is chosen from its seed)
    private static final int LAYOUT_COUNT = 6;
    private static final int MAX_ANCESTORS = 3;
    // Level fog: spheres bigger than the current root by these numbers of levels fade out
    private static final double FOG_START = 1.4, FOG_END = 3.0;
    // Width of the dissolving zone around the reveal distance (qIn = reveal / factor, qOut = reveal * factor)
    private static final double REVEAL_ZONE_FACTOR = 1.35;
    private static final double MIN_PIXEL_RADIUS = 0.4;
    private static final int MAX_DEPTH = 24;
    private static final int MAX_DRAWS = 12000;
    private static final double HUE_SPREAD = 0.14;

    private final Canvas canvas;
    private final WebGLRenderingContext gl;
    private WebGLProgram program;
    private int aPosition;
    private WebGLUniformLocation uProjection, uSphere, uColor, uLightDirection;
    private SphereMesh[] meshes; // low, medium & high tessellation

    // Settings
    private int partsCount = 80;
    private double revealDistance = 6, qIn, qOut;
    private double speed = 0.07; // zoom cycles per second
    private boolean paused;

    // Zoom state
    private final Random random = new Random();
    private SphereLayout[] layouts;
    private double s;
    private int rootSeed;
    private double rootHue, rootSaturation, rootBrightness;
    private ZoomPath previousPath, currentPath, nextPath;
    private final List<Frame> ancestors = new ArrayList<>(); // the oldest (biggest) first
    private long lastFrameNanos;
    private int levelsCount;

    // Per-frame camera & traversal state (all in root coordinates)
    private final double[] cameraPosition = new double[3], cameraQuat = new double[4];
    private final double[] tmpPosition = new double[3], tmpQuat = new double[4];
    private final double[] view = new double[9]; // world -> view rotation
    private double camX, camY, camZ, sigma, lnInverseR, focalPixels, cosV, sinV, cosH, sinH;
    private final double[][] matrices = new double[MAX_DEPTH + 2][9];
    private final double[] rgb = new double[3];
    private final double[] projection = new double[16];

    // Draw lists: 8 doubles per draw (view x, y, z, radius, r, g, b, alpha)
    private final double[][] opaqueDraws = new double[3][];
    private final int[] opaqueCounts = new int[3];
    private double[] transparentDraws = new double[8 * 256];
    private int[] transparentMeshes = new int[256];
    private double[] transparentDistances = new double[256];
    private int[] transparentOrder = new int[256];
    private int transparentCount, drawCount;

    private static final class Frame {
        double x, y, z, size;
        final double[] m = new double[9];
        int seed;
        double hue, saturation, brightness;
        int childIndex; // index of the part leading to the next ancestor (or to the root)
    }

    EmptinessScene(Canvas canvas) {
        this.canvas = canvas;
        gl = WebGL.getWebGLContext(canvas);
        if (gl == null) {
            Console.log("WebGL is not available");
            return;
        }
        program = initShaderProgram(
                Resource.getText(Resource.toUrl("sphereVertexShader.glsl", getClass())),
                Resource.getText(Resource.toUrl("sphereFragmentShader.glsl", getClass())));
        aPosition = gl.getAttribLocation(program, "aPosition");
        uProjection = gl.getUniformLocation(program, "uProjection");
        uSphere = gl.getUniformLocation(program, "uSphere");
        uColor = gl.getUniformLocation(program, "uColor");
        uLightDirection = gl.getUniformLocation(program, "uLightDirection");
        meshes = new SphereMesh[] {
                new SphereMesh(gl, 10, 7),
                new SphereMesh(gl, 22, 15),
                new SphereMesh(gl, 56, 38),
        };
        for (int i = 0; i < 3; i++)
            opaqueDraws[i] = new double[8 * 1024];
        setRevealDistance(revealDistance);
        restart();
    }

    boolean isWebGLAvailable() {
        return gl != null;
    }

    // ======================================== Settings ========================================

    int getPartsCount() {
        return partsCount;
    }

    void setPartsCount(int partsCount) {
        if (partsCount != this.partsCount) {
            this.partsCount = partsCount;
            restart();
        }
    }

    double getRevealDistance() {
        return revealDistance;
    }

    void setRevealDistance(double revealDistance) {
        this.revealDistance = revealDistance;
        qIn = revealDistance / REVEAL_ZONE_FACTOR;
        qOut = revealDistance * REVEAL_ZONE_FACTOR;
    }

    double getSpeed() {
        return speed;
    }

    void setSpeed(double speed) {
        this.speed = speed;
    }

    void togglePause() {
        paused = !paused;
    }

    int getDrawCount() {
        return drawCount;
    }

    int getLevelsCount() {
        return levelsCount;
    }

    void restart() {
        if (gl == null)
            return;
        layouts = new SphereLayout[LAYOUT_COUNT];
        long layoutSeed = random.nextLong();
        for (int i = 0; i < LAYOUT_COUNT; i++)
            layouts[i] = new SphereLayout(partsCount, layoutSeed + i);
        lnInverseR = Math.log(1 / layouts[0].r);
        rootSeed = random.nextInt();
        rootHue = random.nextDouble();
        rootSaturation = 0.6;
        rootBrightness = 0.85;
        ancestors.clear();
        previousPath = null;
        currentPath = choosePath(layoutOf(rootSeed));
        nextPath = choosePath(layoutOf(Maths.hash(rootSeed, currentPath.childIndex)));
        s = -INTRO_LEVELS;
        levelsCount = 0;
    }

    // ======================================== Zoom logic ========================================

    private SphereLayout layoutOf(int seed) {
        return layouts[(int) (Maths.unit(Maths.hash(seed, 77)) * LAYOUT_COUNT)];
    }

    // Chooses which part of the sphere (described by its layout) the camera will dive into. The camera must not cross
    // the other parts on its way, and should see the target unobstructed at the end.
    private ZoomPath choosePath(SphereLayout layout) {
        int n = layout.n;
        double r = layout.r, clearance2 = (1.2 * r) * (1.2 * r);
        List<ZoomPath> valid = new ArrayList<>(), validFront = new ArrayList<>();
        ZoomPath best = null;
        int bestViolations = Integer.MAX_VALUE;
        double[] p = new double[3];
        for (int j = 0; j < n; j++) {
            ZoomPath path = new ZoomPath(layout, j, D0);
            int violations = 0;
            for (int k = 1; k <= 24 && violations < bestViolations; k++) {
                path.position(k / 24d, p);
                for (int m = 0; m < n; m++) {
                    if (m != j) {
                        double dx = p[0] - layout.cx[m], dy = p[1] - layout.cy[m], dz = p[2] - layout.cz[m];
                        if (dx * dx + dy * dy + dz * dz < clearance2)
                            violations++;
                    }
                }
            }
            // End position: nothing between the camera and the target
            path.position(1, p);
            double ex = path.cx - p[0], ey = path.cy - p[1], ez = path.cz - p[2], el2 = ex * ex + ey * ey + ez * ez;
            for (int m = 0; m < n; m++) {
                if (m != j) {
                    double t = Maths.clamp01(((layout.cx[m] - p[0]) * ex + (layout.cy[m] - p[1]) * ey + (layout.cz[m] - p[2]) * ez) / el2);
                    double dx = p[0] + t * ex - layout.cx[m], dy = p[1] + t * ey - layout.cy[m], dz = p[2] + t * ez - layout.cz[m];
                    if (dx * dx + dy * dy + dz * dz < 1.1 * r * 1.1 * r)
                        violations += 5;
                }
            }
            if (violations == 0) {
                valid.add(path);
                if (layout.cz[j] > 0) // front half (visible from the start position)
                    validFront.add(path);
            }
            if (violations < bestViolations) {
                bestViolations = violations;
                best = path;
            }
        }
        List<ZoomPath> candidates = !validFront.isEmpty() ? validFront : valid;
        return candidates.isEmpty() ? best : candidates.get(random.nextInt(candidates.size()));
    }

    private void advance(double deltaS) {
        s += deltaS;
        while (s >= 1) {
            rebase();
            s -= 1;
        }
    }

    // The target becomes the new root
    private void rebase() {
        ZoomPath p = currentPath;
        double inverseR = 1 / p.r;
        double[] tmp = new double[9];
        for (Frame f : ancestors) {
            tmpPosition[0] = f.x; tmpPosition[1] = f.y; tmpPosition[2] = f.z;
            p.toChild(tmpPosition);
            f.x = tmpPosition[0]; f.y = tmpPosition[1]; f.z = tmpPosition[2];
            f.size *= inverseR;
            Maths.matTransposeMul(p.rot, f.m, tmp);
            System.arraycopy(tmp, 0, f.m, 0, 9);
        }
        Frame oldRoot = new Frame();
        tmpPosition[0] = tmpPosition[1] = tmpPosition[2] = 0;
        p.toChild(tmpPosition);
        oldRoot.x = tmpPosition[0]; oldRoot.y = tmpPosition[1]; oldRoot.z = tmpPosition[2];
        oldRoot.size = inverseR;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                oldRoot.m[3 * i + j] = p.rot[3 * j + i]; // R^T
        oldRoot.childIndex = p.childIndex;
        oldRoot.seed = rootSeed; oldRoot.hue = rootHue; oldRoot.saturation = rootSaturation; oldRoot.brightness = rootBrightness;
        ancestors.add(oldRoot);
        if (ancestors.size() > MAX_ANCESTORS)
            ancestors.remove(0);
        // New root
        int targetSeed = Maths.hash(rootSeed, p.childIndex);
        rootHue = childHue(rootHue, targetSeed);
        rootSaturation = childSaturation(rootSaturation, targetSeed);
        rootBrightness = childBrightness(rootBrightness, targetSeed);
        rootSeed = targetSeed;
        previousPath = currentPath;
        currentPath = nextPath;
        nextPath = choosePath(layoutOf(Maths.hash(rootSeed, currentPath.childIndex)));
        levelsCount++;
    }

    private static double childHue(double parentHue, int childSeed) {
        return parentHue + (Maths.unit(Maths.hash(childSeed, 1)) - 0.5) * HUE_SPREAD;
    }

    private static double childSaturation(double parentSaturation, int childSeed) {
        return Math.max(0.35, Math.min(0.85, parentSaturation + (Maths.unit(Maths.hash(childSeed, 2)) - 0.5) * 0.12));
    }

    private static double childBrightness(double parentBrightness, int childSeed) {
        return Math.max(0.55, Math.min(1, parentBrightness + (Maths.unit(Maths.hash(childSeed, 3)) - 0.5) * 0.14));
    }

    // Computes the camera position & orientation at s, blending with the previous or next path near the cycle ends
    private void computeCamera() {
        currentPath.position(s, cameraPosition);
        currentPath.orientation(s, cameraQuat);
        if (s > 1 - PATH_BLEND && nextPath != null) {
            nextPath.position(s - 1, tmpPosition);
            currentPath.toParent(tmpPosition);
            nextPath.orientation(s - 1, tmpQuat);
            Maths.quatMul(currentPath.quat, tmpQuat, tmpQuat);
            double w = Maths.smoothstep((s - (1 - PATH_BLEND)) / (2 * PATH_BLEND));
            for (int i = 0; i < 3; i++)
                cameraPosition[i] += (tmpPosition[i] - cameraPosition[i]) * w;
            Maths.quatNlerpInto(cameraQuat, tmpQuat, w);
        } else if (s < PATH_BLEND && previousPath != null) {
            previousPath.position(s + 1, tmpPosition);
            previousPath.toChild(tmpPosition);
            previousPath.orientation(s + 1, tmpQuat);
            Maths.quatConjMul(previousPath.quat, tmpQuat, tmpQuat);
            double w = Maths.smoothstep((s + PATH_BLEND) / (2 * PATH_BLEND));
            for (int i = 0; i < 3; i++)
                cameraPosition[i] = tmpPosition[i] + (cameraPosition[i] - tmpPosition[i]) * w;
            Maths.quatNlerpInto(tmpQuat, cameraQuat, w);
            System.arraycopy(tmpQuat, 0, cameraQuat, 0, 4);
        }
        camX = cameraPosition[0]; camY = cameraPosition[1]; camZ = cameraPosition[2];
        // view = transpose(camera rotation)
        double[] m = matrices[MAX_DEPTH + 1];
        Maths.quatToMatrix(cameraQuat, m);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                view[3 * i + j] = m[3 * j + i];
        sigma = Math.pow(currentPath.r, s);
    }

    // ======================================== Rendering ========================================

    void onAnimationFrame(long nowNanos) {
        if (gl == null)
            return;
        double deltaSeconds = lastFrameNanos == 0 ? 0 : Math.min(0.1, (nowNanos - lastFrameNanos) / 1e9);
        lastFrameNanos = nowNanos;
        if (!paused)
            advance(speed * deltaSeconds);
        computeCamera();
        drawScene();
    }

    private void drawScene() {
        int width = gl.getDrawingBufferWidth(), height = gl.getDrawingBufferHeight();
        if (width <= 0 || height <= 0)
            return;
        double aspect = (double) width / height;
        double tanV = Math.tan(FIELD_OF_VIEW / 2), tanH = tanV * aspect;
        double halfV = Math.atan(tanV), halfH = Math.atan(tanH);
        cosV = Math.cos(halfV); sinV = Math.sin(halfV);
        cosH = Math.cos(halfH); sinH = Math.sin(halfH);
        focalPixels = height / 2d / tanV;
        setPerspective(tanV, aspect, 0.05 * sigma, 5000 * sigma);

        // Collecting the spheres to draw
        opaqueCounts[0] = opaqueCounts[1] = opaqueCounts[2] = 0;
        transparentCount = drawCount = 0;
        if (ancestors.isEmpty()) {
            Maths.identity(matrices[0]);
            visit(0, 0, 0, 1, 0, false, rootSeed, rootHue, rootSaturation, rootBrightness);
        } else {
            Frame top = ancestors.get(0);
            System.arraycopy(top.m, 0, matrices[0], 0, 9);
            visit(top.x, top.y, top.z, top.size, 0, true, top.seed, top.hue, top.saturation, top.brightness);
        }

        // Drawing them
        gl.viewport(0, 0, width, height);
        gl.clearColor(BG_R, BG_G, BG_B, 1);
        gl.clearDepth(1);
        gl.enable(gl.DEPTH_TEST);
        gl.depthFunc(gl.LEQUAL);
        gl.enable(gl.CULL_FACE);
        gl.cullFace(gl.BACK);
        gl.depthMask(true);
        gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
        gl.useProgram(program);
        gl.uniformMatrix4fv(uProjection, false, projection);
        gl.uniform3f(uLightDirection, -0.45, 0.55, 0.70);
        gl.enableVertexAttribArray(aPosition);

        // 1) Opaque spheres
        gl.disable(gl.BLEND);
        for (int meshIndex = 0; meshIndex < 3; meshIndex++) {
            int count = opaqueCounts[meshIndex];
            if (count == 0)
                continue;
            SphereMesh mesh = bindMesh(meshIndex);
            double[] d = opaqueDraws[meshIndex];
            for (int i = 0, o = 0; i < count; i++, o += 8)
                drawSphere(mesh, d, o);
        }

        // 2) Dissolving (or fogged) spheres, from back to front
        if (transparentCount > 0) {
            sortTransparentDraws();
            gl.enable(gl.BLEND);
            gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
            gl.depthMask(false);
            int boundMesh = -1;
            SphereMesh mesh = null;
            for (int i = 0; i < transparentCount; i++) {
                int index = transparentOrder[i];
                if (transparentMeshes[index] != boundMesh)
                    mesh = bindMesh(boundMesh = transparentMeshes[index]);
                drawSphere(mesh, transparentDraws, index * 8);
            }
            gl.depthMask(true);
            gl.disable(gl.BLEND);
        }
    }

    // Recursively collects the sphere (center, size and rotation matrices[depth] in root coordinates) and/or its parts.
    // The ancestors of the root (onChain and depth < ancestors count) are always decomposed into their parts, as the
    // camera is (or is about to be) inside them.
    private void visit(double x, double y, double z, double size, int depth, boolean onChain, int seed, double hue, double saturation, double brightness) {
        double dx = x - camX, dy = y - camY, dz = z - camZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Frame ancestor = onChain && depth < ancestors.size() ? ancestors.get(depth) : null;
        boolean inside = ancestor != null || distance < size;
        double[] v = view;
        double vx = v[0] * dx + v[1] * dy + v[2] * dz;
        double vy = v[3] * dx + v[4] * dy + v[5] * dz;
        double vz = v[6] * dx + v[7] * dy + v[8] * dz;
        double pixelRadius = inside ? Double.MAX_VALUE : size * focalPixels / distance;
        if (!inside) {
            double forward = -vz;
            if (forward < -size // behind the camera
                    || Math.abs(vy) * cosV - forward * sinV > size // above or below the view
                    || Math.abs(vx) * cosH - forward * sinH > size // left or right of the view
                    || pixelRadius < MIN_PIXEL_RADIUS) // too small to be seen (and so are its parts)
                return;
        }
        // Level fog (spheres much bigger than the current scale fade out)
        double level = Math.log(size / sigma) / lnInverseR;
        double fog = 1 - Maths.smoothstep((level - FOG_START) / (FOG_END - FOG_START));
        if (fog <= 0.002 && !inside)
            return;
        // How much this sphere is decomposed into its parts (0 = simple sphere, 1 = only parts)
        double composite = inside ? 1 : Maths.clamp01((qOut - distance / size) / (qOut - qIn));
        if (depth >= MAX_DEPTH || drawCount >= MAX_DRAWS) {
            if (inside)
                return;
            composite = 0;
        }
        if (composite < 1) {
            double alpha = (1 - Maths.smoothstep(composite)) * fog;
            if (alpha > 0.002) {
                Maths.hsbToRgb(hue, saturation, brightness, rgb);
                int meshIndex = pixelRadius < 10 ? 0 : pixelRadius < 90 ? 1 : 2;
                addDraw(meshIndex, vx, vy, vz, size, alpha, distance);
            }
        }
        if (composite > 0) {
            SphereLayout layout = layoutOf(seed);
            double[] m = matrices[depth], cm = matrices[depth + 1];
            double partSize = size * layout.r;
            for (int i = 0; i < layout.n; i++) {
                double lx = layout.cx[i], ly = layout.cy[i], lz = layout.cz[i];
                double px = x + size * (m[0] * lx + m[1] * ly + m[2] * lz);
                double py = y + size * (m[3] * lx + m[4] * ly + m[5] * lz);
                double pz = z + size * (m[6] * lx + m[7] * ly + m[8] * lz);
                Maths.matMul(m, layout.rot[i], cm);
                int partSeed = Maths.hash(seed, i);
                visit(px, py, pz, partSize, depth + 1, ancestor != null && i == ancestor.childIndex, partSeed,
                        childHue(hue, partSeed), childSaturation(saturation, partSeed), childBrightness(brightness, partSeed));
            }
        }
    }

    private void addDraw(int meshIndex, double vx, double vy, double vz, double size, double alpha, double distance) {
        drawCount++;
        double[] d;
        int o;
        if (alpha >= 0.998) {
            int count = opaqueCounts[meshIndex]++;
            o = count * 8;
            d = opaqueDraws[meshIndex];
            if (o + 8 > d.length)
                d = opaqueDraws[meshIndex] = grow(d);
        } else {
            int count = transparentCount++;
            if (count >= transparentMeshes.length) {
                transparentDraws = grow(transparentDraws);
                transparentMeshes = growInt(transparentMeshes);
                transparentOrder = growInt(transparentOrder);
                transparentDistances = grow(transparentDistances);
            }
            transparentMeshes[count] = meshIndex;
            transparentDistances[count] = distance;
            o = count * 8;
            d = transparentDraws;
        }
        d[o] = vx; d[o + 1] = vy; d[o + 2] = vz; d[o + 3] = size;
        d[o + 4] = rgb[0]; d[o + 5] = rgb[1]; d[o + 6] = rgb[2]; d[o + 7] = alpha;
    }

    private static double[] grow(double[] array) {
        double[] newArray = new double[array.length * 2];
        System.arraycopy(array, 0, newArray, 0, array.length);
        return newArray;
    }

    private static int[] growInt(int[] array) {
        int[] newArray = new int[array.length * 2];
        System.arraycopy(array, 0, newArray, 0, array.length);
        return newArray;
    }

    // Back to front (insertion sort - the order is mostly stable from one frame to the next)
    private void sortTransparentDraws() {
        int n = transparentCount;
        for (int i = 0; i < n; i++)
            transparentOrder[i] = i;
        for (int i = 1; i < n; i++) {
            int index = transparentOrder[i];
            double distance = transparentDistances[index];
            int j = i - 1;
            while (j >= 0 && transparentDistances[transparentOrder[j]] < distance) {
                transparentOrder[j + 1] = transparentOrder[j];
                j--;
            }
            transparentOrder[j + 1] = index;
        }
    }

    private SphereMesh bindMesh(int meshIndex) {
        SphereMesh mesh = meshes[meshIndex];
        gl.bindBuffer(gl.ARRAY_BUFFER, mesh.positionBuffer);
        gl.vertexAttribPointer(aPosition, 3, gl.FLOAT, false, 0, 0);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, mesh.indexBuffer);
        return mesh;
    }

    private void drawSphere(SphereMesh mesh, double[] d, int o) {
        gl.uniform4f(uSphere, d[o], d[o + 1], d[o + 2], d[o + 3]);
        gl.uniform4f(uColor, d[o + 4], d[o + 5], d[o + 6], d[o + 7]);
        gl.drawElements(gl.TRIANGLES, mesh.indexCount, gl.UNSIGNED_SHORT, 0);
    }

    // Column-major perspective projection matrix
    private void setPerspective(double tanHalfFov, double aspect, double near, double far) {
        double f = 1 / tanHalfFov;
        for (int i = 0; i < 16; i++)
            projection[i] = 0;
        projection[0] = f / aspect;
        projection[5] = f;
        projection[10] = (far + near) / (near - far);
        projection[11] = -1;
        projection[14] = 2 * far * near / (near - far);
    }

    private WebGLProgram initShaderProgram(String vsSource, String fsSource) {
        WebGLShader vertexShader = loadShader(gl.VERTEX_SHADER, vsSource);
        WebGLShader fragmentShader = loadShader(gl.FRAGMENT_SHADER, fsSource);
        WebGLProgram shaderProgram = gl.createProgram();
        gl.attachShader(shaderProgram, vertexShader);
        gl.attachShader(shaderProgram, fragmentShader);
        gl.linkProgram(shaderProgram);
        if (gl.getProgramParameter(shaderProgram, gl.LINK_STATUS) == null)
            Console.log("Unable to initialize the shader program: " + gl.getProgramInfoLog(shaderProgram));
        return shaderProgram;
    }

    private WebGLShader loadShader(int type, String source) {
        WebGLShader shader = gl.createShader(type);
        gl.shaderSource(shader, source);
        gl.compileShader(shader);
        if (gl.getShaderParameter(shader, gl.COMPILE_STATUS) == null)
            Console.log("An error occurred compiling the shader: " + gl.getShaderInfoLog(shader));
        return shader;
    }
}
