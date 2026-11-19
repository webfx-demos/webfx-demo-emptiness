package dev.webfx.demo.emptiness;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Chooses which part of a sphere the camera dives into next.
 *
 * <p>To keep the feeling of emptiness, the camera should fly through empty space only: the target is a part on the
 * front of the sphere (facing the camera), which the camera sees unobstructed during the whole dive, and the camera
 * never passes close to another part or stem on its way.</p>
 *
 * @author Bruno Salmon
 */
final class TargetChooser {

    // Number of camera positions checked along the path
    private static final int PATH_SAMPLES = 24;
    // Number of camera positions from which the target must be visible
    private static final int SIGHT_SAMPLES = 9;
    // Minimal distances (in part radii) from the camera to the other parts, from the camera to the stems, and from the
    // line of sight to the other parts (the thickest stem is 0.4 r)
    private static final double PART_CLEARANCE = 1.2, STEM_CLEARANCE = 0.6, SIGHT_CLEARANCE = 1.1;
    // The parts are checked from the closest to the camera, and the target is chosen randomly among the first valid ones
    // (up to 5, and fewer for spheres with few parts, so the target stays in front)
    private static final int MAX_FRONT_CANDIDATES = 5;

    private TargetChooser() {}

    static ZoomPath choosePath(SphereLayout layout, double d0, Random random) {
        int n = layout.n;
        double r = layout.r;
        double partClearance2 = sq(PART_CLEARANCE * r), stemClearance2 = sq(STEM_CLEARANCE * r), sightClearance2 = sq(SIGHT_CLEARANCE * r);
        // Parts sorted from the closest to the start position (0, 0, d0) = from the front to the back
        Integer[] order = new Integer[n];
        double[] distances = new double[n];
        for (int j = 0; j < n; j++) {
            order[j] = j;
            distances[j] = sq(layout.cx[j]) + sq(layout.cy[j]) + sq(d0 - layout.cz[j]);
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(distances[a], distances[b]));
        int frontCandidates = Math.max(1, Math.min(MAX_FRONT_CANDIDATES, n / 10));
        List<ZoomPath> valid = new ArrayList<>();
        ZoomPath best = null;
        int bestViolations = Integer.MAX_VALUE;
        double[] p = new double[3];
        for (int o = 0; o < n && valid.size() < frontCandidates; o++) {
            int j = order[o];
            ZoomPath path = new ZoomPath(layout, j, d0);
            int violations = 0;
            // 1) The camera doesn't pass close to another part or stem
            for (int k = 1; k <= PATH_SAMPLES && violations <= bestViolations; k++) {
                path.position((double) k / PATH_SAMPLES, p);
                for (int m = 0; m < n; m++) {
                    if (m != j && sq(p[0] - layout.cx[m]) + sq(p[1] - layout.cy[m]) + sq(p[2] - layout.cz[m]) < partClearance2)
                        violations++;
                }
                for (int b = 0; b < layout.stemA.length; b++) {
                    if (distanceToSegment2(p[0], p[1], p[2], layout, layout.stemA[b], layout.stemB[b]) < stemClearance2)
                        violations++;
                }
            }
            // 2) The target is visible during the whole dive (nothing between the camera and the target)
            for (int k = 0; k < SIGHT_SAMPLES && violations <= bestViolations; k++) {
                path.position((double) k / (SIGHT_SAMPLES - 1), p);
                double ex = layout.cx[j] - p[0], ey = layout.cy[j] - p[1], ez = layout.cz[j] - p[2];
                double el2 = ex * ex + ey * ey + ez * ez;
                for (int m = 0; m < n; m++) {
                    if (m != j) {
                        double t = Maths.clamp01(((layout.cx[m] - p[0]) * ex + (layout.cy[m] - p[1]) * ey + (layout.cz[m] - p[2]) * ez) / el2);
                        if (sq(p[0] + t * ex - layout.cx[m]) + sq(p[1] + t * ey - layout.cy[m]) + sq(p[2] + t * ez - layout.cz[m]) < sightClearance2)
                            violations++;
                    }
                }
            }
            if (violations == 0)
                valid.add(path);
            if (violations < bestViolations) { // the front-most one in case of equality (as parts are sorted)
                bestViolations = violations;
                best = path;
            }
        }
        // 3) Choosing randomly among the front-most valid parts (or the least bad one if none is valid)
        return valid.isEmpty() ? best : valid.get(random.nextInt(valid.size()));
    }

    // Squared distance from the point (x, y, z) to the segment between the parts a and b of the layout
    private static double distanceToSegment2(double x, double y, double z, SphereLayout layout, int a, int b) {
        double ax = layout.cx[a], ay = layout.cy[a], az = layout.cz[a];
        double ex = layout.cx[b] - ax, ey = layout.cy[b] - ay, ez = layout.cz[b] - az;
        double t = Maths.clamp01(((x - ax) * ex + (y - ay) * ey + (z - az) * ez) / (ex * ex + ey * ey + ez * ez));
        return sq(ax + t * ex - x) + sq(ay + t * ey - y) + sq(az + t * ez - z);
    }

    private static double sq(double v) {
        return v * v;
    }
}
