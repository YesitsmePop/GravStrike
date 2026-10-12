public class Camera {
    double yaw = 0.6, pitch = 0.35;
    double distance = 900, focal = 800;

    /** Returns {screenX, screenY, scale, depth}, or null if behind the camera. */
    public double[] project(double x, double y, double z, int w, int h) {
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double x1 = x * cy + z * sy;
        double z1 = -x * sy + z * cy;

        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double y1 = y * cp + z1 * sp;
        double z2 = -y * sp + z1 * cp;

        double depth = z2 + distance;
        if (depth < 1) return null;

        double scale = focal / depth;
        return new double[]{w / 2.0 + x1 * scale, h / 2.0 - y1 * scale, scale, depth};
    }

    /** Inverse of the camera rotation: view space back to world space. */
    private double[] unrotate(double x1, double y1, double z2) {
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double y = y1 * cp - z2 * sp;
        double z1 = y1 * sp + z2 * cp;
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double x = x1 * cy - z1 * sy;
        double z = x1 * sy + z1 * cy;
        return new double[]{x, y, z};
    }

    /**
     * Casts a ray through screen pixel (mx, my) and returns the t along the line
     * p + t*a (a must be a unit vector) that lies closest to that ray.
     * Returns NaN when the camera looks almost straight along the axis.
     */
    public double closestOnAxis(int mx, int my, int w, int h, double[] p, double[] a) {
        double[] o = unrotate(0, 0, -distance);
        double[] d = unrotate((mx - w / 2.0) / focal, (h / 2.0 - my) / focal, 1);
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        for (int i = 0; i < 3; i++) d[i] /= len;

        double b = a[0] * d[0] + a[1] * d[1] + a[2] * d[2];
        double denom = 1 - b * b;
        if (denom < 0.004) return Double.NaN;

        double[] w0 = {p[0] - o[0], p[1] - o[1], p[2] - o[2]};
        double ad = a[0] * w0[0] + a[1] * w0[1] + a[2] * w0[2];
        double dw = d[0] * w0[0] + d[1] * w0[1] + d[2] * w0[2];
        return (b * dw - ad) / denom;
    }

    /** Projects a direction on a distant sky sphere: rotation only, so stars drift as you orbit but never get closer. */
    public double[] projectSky(double x, double y, double z, double focalLen, int w, int h) {
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double x1 = x * cy + z * sy;
        double z1 = -x * sy + z * cy;

        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double y1 = y * cp + z1 * sp;
        double z2 = -y * sp + z1 * cp;

        if (z2 < 0.15) return null;   // behind the camera
        return new double[]{w / 2.0 + x1 / z2 * focalLen, h / 2.0 - y1 / z2 * focalLen};
    }
}