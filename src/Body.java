import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Deque;

public class Body {
    String name;
    final int seed;           // terrain seed: fixed at creation, so renaming doesn't change the planet's look
    double x, y, z, vx, vy, vz, mass, radius;
    Color color;
    final Deque<double[]> trail = new ArrayDeque<>();

    public Body(String name, double x, double y, double z,
                double vx, double vy, double vz,
                double mass, Color color) {
        this.name = name;
        this.seed = name.hashCode();
        this.x = x; this.y = y; this.z = z;
        this.vx = vx; this.vy = vy; this.vz = vz;
        this.color = color;
        setMass(mass);
    }

    public void setMass(double m) {
        this.mass = m;
        this.radius = Math.max(3, Math.cbrt(m) * 2);
    }
}