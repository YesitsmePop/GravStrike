import java.util.ArrayList;
import java.util.List;

public class World {
    static final double G = 1000;
    static final double SOFTENING = 25;
    static final int MAX_TRAIL = 400;

    final List<Body> bodies = new ArrayList<>();

    public void update(double dt) {
        for (int i = 0; i < bodies.size(); i++) {
            Body a = bodies.get(i);
            for (int j = i + 1; j < bodies.size(); j++) {
                Body b = bodies.get(j);
                double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
                double distSq = dx * dx + dy * dy + dz * dz + SOFTENING;
                double dist = Math.sqrt(distSq);
                double force = G * a.mass * b.mass / distSq;
                double fx = force * dx / dist;
                double fy = force * dy / dist;
                double fz = force * dz / dist;
                a.vx += fx / a.mass * dt; a.vy += fy / a.mass * dt; a.vz += fz / a.mass * dt;
                b.vx -= fx / b.mass * dt; b.vy -= fy / b.mass * dt; b.vz -= fz / b.mass * dt;
            }
        }
        for (Body b : bodies) {
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.z += b.vz * dt;
            b.trail.addLast(new double[]{b.x, b.y, b.z});
            if (b.trail.size() > MAX_TRAIL) b.trail.removeFirst();
        }
    }
}