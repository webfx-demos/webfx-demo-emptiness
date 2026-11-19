package dev.webfx.demo.emptiness;

import java.util.Random;

/**
 * How a sphere of radius 1 is composed of smaller spheres: the centers of its parts (all of the same radius r, all
 * fully inside the unit ball) and the rotation of each part (so that the parts of a part are not simply a scaled copy
 * of the same arrangement).
 *
 * <p>The parts fill a fixed fraction of the volume, so that from far they merge into the appearance of one solid sphere,
 * while leaving enough empty space between them to reveal, from close, that there is no such solid sphere.</p>
 *
 * @author Bruno Salmon
 */
final class SphereLayout {

    // Fraction of the sphere volume occupied by its parts
    private static final double PACKING = 0.27;
    // Minimal gap between two parts, as a factor of their diameter
    private static final double GAP = 1.12;
    private static final int RELAXATION_ITERATIONS = 120;

    final int n;
    final double r;
    final double[] cx, cy, cz;       // centers of the parts (in the unit sphere coordinates)
    final double[] ax, ay, az, angle; // rotation of each part (axis + angle)
    final double[][] rot;             // same rotations as matrices

    SphereLayout(int n, long seed) {
        this.n = n;
        r = Math.cbrt(PACKING / n);
        cx = new double[n]; cy = new double[n]; cz = new double[n];
        ax = new double[n]; ay = new double[n]; az = new double[n]; angle = new double[n];
        rot = new double[n][9];
        Random random = new Random(seed);
        double maxCenterRadius = 1 - r;
        // Random initial positions inside the ball
        for (int i = 0; i < n; i++) {
            double x, y, z;
            do {
                x = 2 * random.nextDouble() - 1;
                y = 2 * random.nextDouble() - 1;
                z = 2 * random.nextDouble() - 1;
            } while (x * x + y * y + z * z > 1);
            cx[i] = x * maxCenterRadius; cy[i] = y * maxCenterRadius; cz[i] = z * maxCenterRadius;
        }
        // Relaxation: pushing overlapping parts apart, while keeping them inside the ball
        double minDistance = 2 * r * GAP, minDistance2 = minDistance * minDistance;
        for (int iteration = 0; iteration < RELAXATION_ITERATIONS; iteration++) {
            boolean moved = false;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double dx = cx[j] - cx[i], dy = cy[j] - cy[i], dz = cz[j] - cz[i];
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 < minDistance2) {
                        double d = Math.sqrt(d2);
                        if (d < 1e-9) { // same position => random direction
                            dx = random.nextDouble() - 0.5; dy = random.nextDouble() - 0.5; dz = random.nextDouble() - 0.5;
                            d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        }
                        double push = (minDistance - Math.sqrt(d2)) / 2 / d;
                        cx[i] -= dx * push; cy[i] -= dy * push; cz[i] -= dz * push;
                        cx[j] += dx * push; cy[j] += dy * push; cz[j] += dz * push;
                        moved = true;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                double d = Math.sqrt(cx[i] * cx[i] + cy[i] * cy[i] + cz[i] * cz[i]);
                if (d > maxCenterRadius) {
                    double k = maxCenterRadius / d;
                    cx[i] *= k; cy[i] *= k; cz[i] *= k;
                }
            }
            if (!moved)
                break;
        }
        // A random moderate rotation for each part (15° to 45°, random axis, random sign)
        for (int i = 0; i < n; i++) {
            double z = 2 * random.nextDouble() - 1, phi = 2 * Math.PI * random.nextDouble(), s = Math.sqrt(1 - z * z);
            ax[i] = s * Math.cos(phi); ay[i] = s * Math.sin(phi); az[i] = z;
            angle[i] = (random.nextBoolean() ? 1 : -1) * Math.toRadians(15 + 30 * random.nextDouble());
            Maths.axisAngleToMatrix(ax[i], ay[i], az[i], angle[i], rot[i]);
        }
    }
}
