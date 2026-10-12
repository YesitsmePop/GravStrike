import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class Placement {
    public enum Stage { IDLE, X, Z, Y }

    private static final Color[] PALETTE = {
            new Color(170, 255, 90), new Color(90, 255, 170), new Color(210, 255, 150),
            new Color(60, 220, 120), new Color(140, 255, 60), new Color(120, 255, 220)
    };

    private final World world;
    private final List<Runnable> listeners = new ArrayList<>();
    private int counter = 0;
    private long lastNotch = Long.MIN_VALUE;

    public Stage stage = Stage.IDLE;
    public Body pending;
    public double px, py, pz;      // current candidate position
    public boolean orbit = true;   // give the new planet an orbital velocity

    public Placement(World world) { this.world = world; }

    public void addListener(Runnable r) { listeners.add(r); }
    private void fire() { for (Runnable r : listeners) r.run(); }

    public boolean isActive() { return stage != Stage.IDLE; }

    public void start() {
        if (isActive()) return;
        counter++;
        pending = new Body("Planet " + counter, 0, 0, 0, 0, 0, 0, 5,
                PALETTE[(counter - 1) % PALETTE.length]);
        px = py = pz = 0;
        stage = Stage.X;
        Sfx.play("open");
        fire();
    }

    public void cancel() {
        if (!isActive()) return;
        stage = Stage.IDLE;
        pending = null;
        Sfx.play("back");
        fire();
    }

    /** Lock the current coordinate and move on to the next axis. */
    public void confirm() {
        switch (stage) {
            case X -> {
                stage = Stage.Z;
                Sfx.play("lock");
            }
            case Z -> {
                stage = Stage.Y;
                Sfx.play("lock");
            }
            case Y -> {
                finish(); Sfx.play("place");
            }
            default -> { }
        }
        if (isActive()) fire();   // finish() fires on its own
    }

    /** Step back one axis. */
    public void back() {
        if (stage == Stage.Y) { stage = Stage.Z; py = 0; Sfx.play("back"); fire(); }
        else if (stage == Stage.Z) { stage = Stage.X; pz = 0; Sfx.play("back"); fire(); }
    }

    private void finish() {
        pending.x = px; pending.y = py; pending.z = pz;
        if (orbit) giveOrbitalVelocity(pending);
        world.bodies.add(pending);
        pending = null;
        stage = Stage.IDLE;
        fire();
    }

    private void giveOrbitalVelocity(Body b) {
        Body heavy = null;
        for (Body o : world.bodies) if (heavy == null || o.mass > heavy.mass) heavy = o;
        if (heavy == null) return;

        double rx = b.x - heavy.x, ry = b.y - heavy.y, rz = b.z - heavy.z;
        double r = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (r < 1e-6) return;

        // tangent = r x up (horizontal); fall back to r x X when r is vertical
        double tx = -rz, ty = 0, tz = rx;
        double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (len < 1e-6) { tx = 0; ty = rz; tz = -ry; len = Math.sqrt(ty * ty + tz * tz); }
        if (len < 1e-6) return;

        double speed = Math.sqrt(World.G * heavy.mass * r * r / Math.pow(r * r + World.SOFTENING, 1.5));
        b.vx = heavy.vx + tx / len * speed;
        b.vy = heavy.vy + ty / len * speed;
        b.vz = heavy.vz + tz / len * speed;
    }

    // ---- geometry of the active axis ----

    public double[] axisOrigin() {
        return switch (stage) {
            case Z -> new double[]{px, 0, 0};
            case Y -> new double[]{px, 0, pz};
            default -> new double[]{0, 0, 0};
        };
    }

    public double[] axisDir() {
        return switch (stage) {
            case Z -> new double[]{0, 0, 1};
            case Y -> new double[]{0, 1, 0};
            default -> new double[]{1, 0, 0};
        };
    }

    public double lo() { return stage == Stage.Y ? SimulationPanel.FLOOR_Y : -SimulationPanel.GRID_HALF; }
    public double hi() { return SimulationPanel.GRID_HALF; }

    /** Set the coordinate being chosen on the active axis (clamped to the axis range). */
    public void setParam(double t) {
        t = Math.max(lo(), Math.min(hi(), t));
        long notch = Math.round(t / 50);
        if(notch != lastNotch) { lastNotch = notch; Sfx.play("tick");}
        switch (stage) {
            case X -> px = t;
            case Z -> pz = t;
            case Y -> py = t;
            default -> { }
        }
    }

    public int step() {
        return switch (stage) {
            case X -> 1;
            case Z -> 2;
            case Y -> 3;
            default -> 0;
        };
    }

    public String hint() {
        return switch (stage) {
            case X -> "Slide along X, click to lock";
            case Z -> "Slide along Z, click to lock";
            case Y -> "Set height (Y), click to place";
            default -> "";
        };
    }
}