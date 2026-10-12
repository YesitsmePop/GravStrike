import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Full-screen strike sequence: zoom into a planet, aim through a scope, fire, destroy, zoom back out. */
public class StrikeView extends JComponent {

    private enum Phase { IDLE, ZOOM_IN, AIM, FLIGHT, IMPACT, SHATTER, ZOOM_OUT, GLOW, ABORT }

    // ---------------------------------------------------------------- tuning
    private static final int S = 4;                       // screen pixels per globe pixel (bigger = chunkier + faster)
    private static final double ZOOM_IN_T = 2.0, FLIGHT_T = 1.3, IMPACT_T = 2.4,
            SHATTER_T = 2.0, ZOOM_OUT_T = 2.6, GLOW_T = 2.4, ABORT_T = 1.5;
    private static final double SPIN = 0.32, TILT = 0.38;
    private static final double LX = -0.45, LY = 0.55, LZ = 0.70;   // light direction (upper-left, toward viewer)
    private static final int LEVELS = 8;

    private static final int[] FIRE = {
            rgb(30, 4, 0), rgb(95, 8, 0), rgb(170, 22, 0), rgb(235, 72, 0),
            rgb(255, 140, 12), rgb(255, 205, 55), rgb(255, 245, 170), rgb(255, 255, 255)
    };
    private static final int EDGE = rgb(255, 130, 25);
    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    private static final Cursor BLANK = Toolkit.getDefaultToolkit().createCustomCursor(
            new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "blank");

    private static final class Puff  { double x, y, vx, vy, size, grow, age, life; int r, g, b, a; }
    private static final class Spark { double x, y, vx, vy, age, life; int size; }
    private static final class Frag  { BufferedImage img; double x, y, cx, cy, vx, vy, ang, spin, sc, dsc; }

    private final SimulationPanel sim;
    private final World world;
    private final Runnable onChanged;
    private final Random rnd = new Random();
    private final List<Puff> puffs = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private final List<Frag> frags = new ArrayList<>();

    private Timer timer;
    private Phase phase = Phase.IDLE;
    private volatile boolean blocking;
    private long phaseStart, lastNanos;
    private double T, breakT;
    private int frame, sw, sh;

    private Body target;
    private BufferedImage snapBefore, snapAfter;
    private double p0x, p0y, r0, rFinal, scopeR, kEnd;
    private double zoomE, abortFrom, abortIris;
    private double curX, curY, curR;

    private double rot, seed;
    private int[] ramp;
    private BufferedImage buf;
    private int[] bufPx;
    private double[] rowH;
    private int bw, bh;

    private int ptrX, ptrY;
    private double aimX, aimY, lastAimX, lastAimY, servoAcc;
    private double[] impLocal;
    private double impX, impY, mTx, mTy, mSx, mSy;

    private double shake, flash;
    private boolean scopeSounded;

    public StrikeView(SimulationPanel sim, World world, Runnable onChanged) {
        this.sim = sim;
        this.world = world;
        this.onChanged = onChanged;
        setOpaque(false);
        setVisible(false);

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e)   { ptrX = e.getX(); ptrY = e.getY(); }
            @Override public void mouseDragged(MouseEvent e) { ptrX = e.getX(); ptrY = e.getY(); }
            @Override public void mousePressed(MouseEvent e) {
                ptrX = e.getX(); ptrY = e.getY();
                if (phase != Phase.ZOOM_IN && phase != Phase.AIM) return;
                if (SwingUtilities.isRightMouseButton(e)) abort();
                else if (SwingUtilities.isLeftMouseButton(e) && phase == Phase.AIM) fire();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);

        // Esc aborts. A dispatcher runs before normal key bindings, so nothing else can steal it.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(ev -> {
            if (!blocking || ev.getID() != KeyEvent.KEY_PRESSED || ev.getKeyCode() != KeyEvent.VK_ESCAPE) return false;
            abort();
            return true;
        });
    }

    public boolean isBlocking() { return blocking; }

    /** While the banner plays the view is see-through to the mouse. */
    @Override public boolean contains(int x, int y) { return blocking && super.contains(x, y); }

    // ================================================================ sequence control

    public void begin(Body b) {
        if (phase != Phase.IDLE) return;
        sw = getWidth();
        sh = getHeight();
        if (sw < 300 || sh < 300) return;
        double[] s = sim.screenOf(b);
        if (s == null) return;

        target = b;
        p0x = s[0];
        p0y = s[1];
        r0 = s[2] + Retro.PIXEL * 0.5;
        scopeR = Math.min(sw, sh) * 0.42;
        rFinal = Math.min(sw, sh) * 0.72;
        kEnd = r0 / rFinal;
        seed = (b.seed & 0xFFFF) * 0.0173 + 3.1;     // every planet gets its own terrain
        ramp = buildRamp(b.color);

        bw = (sw + S - 1) / S;
        bh = (sh + S - 1) / S;
        buf = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        bufPx = ((DataBufferInt) buf.getRaster().getDataBuffer()).getData();
        rowH = new double[bw];

        sim.setPaused(true);
        snapBefore = grab();
        snapAfter = null;

        PointerInfo pi = MouseInfo.getPointerInfo();
        if (pi != null) {
            Point pt = pi.getLocation();
            SwingUtilities.convertPointFromScreen(pt, this);
            ptrX = pt.x;
            ptrY = pt.y;
        } else {
            ptrX = sw / 2;
            ptrY = sh / 2;
        }
        aimX = lastAimX = sw / 2.0;
        aimY = lastAimY = sh / 2.0;
        servoAcc = 0;
        impLocal = null;
        puffs.clear();
        sparks.clear();
        frags.clear();
        rot = rnd.nextDouble() * Math.PI * 2;
        shake = 0;
        flash = 0;
        breakT = 0;
        zoomE = 0;
        frame = 0;
        scopeSounded = false;

        blocking = true;
        setCursor(BLANK);
        setVisible(true);
        Sfx.play("lockon");
        Sfx.play("zoom_in");

        lastNanos = System.nanoTime();
        enter(Phase.ZOOM_IN);
        pose();
        renderBuffer();

        if (timer != null) timer.stop();
        timer = new Timer(30, e -> step());
        timer.start();
    }

    private void enter(Phase p) {
        phase = p;
        phaseStart = System.nanoTime();
        T = 0;
    }

    private void abort() {
        if (phase != Phase.ZOOM_IN && phase != Phase.AIM) return;
        abortFrom = zoomE;
        abortIris = irisAmt();
        Sfx.play("deny");
        Sfx.play("zoom_out");
        enter(Phase.ABORT);
    }

    private void fire() {
        if (Math.hypot(ptrX - sw / 2.0, ptrY - sh / 2.0) > scopeR - 6) { Sfx.play("deny"); return; }
        double[] loc = surfaceAt(aimX, aimY);
        if (loc == null) return;
        impLocal = loc;
        impX = aimX;
        impY = aimY;
        mTx = impX / S;
        mTy = impY / S;
        mSx = mTx + (rnd.nextDouble() - 0.5) * bw * 0.7;
        mSy = -45;
        shake = 6;
        flash = 0.3;
        Sfx.play("launch");
        Sfx.play("missile");
        enter(Phase.FLIGHT);
    }

    private void impact() {
        enter(Phase.IMPACT);
        Sfx.play("boom");
        shake = 24;
        flash = 1.0;
        for (int i = 0; i < 150; i++) sparks.add(spark(mTx, mTy, 40 + rnd.nextDouble() * 300, 0.5 + rnd.nextDouble() * 1.3));
        dust(mTx, mTy, 34, 80, 8);
    }

    /** Cuts the glowing planet into shards, removes the body, and starts the breakup. */
    private void shatter() {
        renderGlobe(curX / S, curY / S, curR / S, IMPACT_T, irisRadius(1) / S + 2);
        int[] src = bufPx.clone();

        final int n = 46;
        double[] cxs = new double[n], cys = new double[n];
        double reach = Math.hypot(bw, bh) * 0.75;
        for (int i = 0; i < n; i++) {                       // seeds crowd around the impact point
            double a = rnd.nextDouble() * Math.PI * 2;
            double d = Math.pow(rnd.nextDouble(), 1.5) * reach;
            cxs[i] = mTx + Math.cos(a) * d;
            cys[i] = mTy + Math.sin(a) * d;
        }

        int[] cell = new int[bw * bh];
        int[] minX = new int[n], minY = new int[n], maxX = new int[n], maxY = new int[n], cnt = new int[n];
        double[] sumX = new double[n], sumY = new double[n];
        Arrays.fill(minX, Integer.MAX_VALUE);
        Arrays.fill(minY, Integer.MAX_VALUE);
        Arrays.fill(maxX, -1);
        Arrays.fill(maxY, -1);
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int i = y * bw + x;
                if ((src[i] >>> 24) == 0) { cell[i] = -1; continue; }
                int best = 0;
                double bd = Double.MAX_VALUE;
                for (int k = 0; k < n; k++) {
                    double dx = x - cxs[k], dy = y - cys[k], dd = dx * dx + dy * dy;
                    if (dd < bd) { bd = dd; best = k; }
                }
                cell[i] = best;
                cnt[best]++;
                sumX[best] += x;
                sumY[best] += y;
                minX[best] = Math.min(minX[best], x);
                minY[best] = Math.min(minY[best], y);
                maxX[best] = Math.max(maxX[best], x);
                maxY[best] = Math.max(maxY[best], y);
            }
        }

        BufferedImage[] imgs = new BufferedImage[n];
        for (int k = 0; k < n; k++)
            if (cnt[k] > 0)
                imgs[k] = new BufferedImage(maxX[k] - minX[k] + 1, maxY[k] - minY[k] + 1, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int i = y * bw + x, k = cell[i];
                if (k < 0) continue;
                boolean edge = (x + 1 < bw && cell[i + 1] >= 0 && cell[i + 1] != k)
                        || (x > 0 && cell[i - 1] >= 0 && cell[i - 1] != k)
                        || (y + 1 < bh && cell[i + bw] >= 0 && cell[i + bw] != k)
                        || (y > 0 && cell[i - bw] >= 0 && cell[i - bw] != k);
                imgs[k].setRGB(x - minX[k], y - minY[k], edge ? EDGE : src[i]);   // glowing fracture lines
            }
        }

        frags.clear();
        for (int k = 0; k < n; k++) {
            if (cnt[k] == 0) continue;
            Frag f = new Frag();
            f.img = imgs[k];
            double cxk = sumX[k] / cnt[k], cyk = sumY[k] / cnt[k];
            f.cx = cxk - minX[k] + 0.5;
            f.cy = cyk - minY[k] + 0.5;
            f.x = cxk + 0.5;
            f.y = cyk + 0.5;
            double dx = cxk - mTx, dy = cyk - mTy, dist = Math.hypot(dx, dy);
            if (dist < 3) {
                double a = rnd.nextDouble() * Math.PI * 2;
                dx = Math.cos(a);
                dy = Math.sin(a);
                dist = 1;
            }
            double speed = (70 + rnd.nextDouble() * 130) * (1.5 - 0.85 * Math.min(1, dist / reach));
            f.vx = dx / dist * speed;
            f.vy = dy / dist * speed;
            f.spin = (rnd.nextDouble() - 0.5) * 5;
            f.sc = 1;
            f.dsc = 0.25 + rnd.nextDouble() * 1.1;           // shards tumble toward the camera
            frags.add(f);
        }

        for (int i = 0; i < 220; i++) sparks.add(spark(mTx, mTy, 60 + rnd.nextDouble() * 380, 0.8 + rnd.nextDouble() * 1.4));
        dust(mTx, mTy, 40, 120, 12);
        flash = 1.0;
        shake = 34;
        breakT = 0;
        Sfx.play("shatter");

        world.bodies.remove(target);
        onChanged.run();
        snapAfter = grab();                                   // the simulation without the planet
        enter(Phase.SHATTER);
    }

    private void glow() {
        enter(Phase.GLOW);
        blocking = false;
        sim.setPaused(false);
        setCursor(Cursor.getDefaultCursor());
        Sfx.play("confirm");
    }

    private void finish() {
        if (timer != null) timer.stop();
        phase = Phase.IDLE;
        blocking = false;
        setVisible(false);
        setCursor(Cursor.getDefaultCursor());
        sim.setPaused(false);
        snapBefore = null;
        snapAfter = null;
        frags.clear();
        puffs.clear();
        sparks.clear();
    }

    // ================================================================ per-frame update

    private void step() {
        long now = System.nanoTime();
        double dt = Math.min(0.06, (now - lastNanos) / 1e9);
        lastNanos = now;
        frame++;
        T = (now - phaseStart) / 1e9;

        switch (phase) {
            case ZOOM_IN -> {
                zoomE = ease(T / ZOOM_IN_T);
                rot += SPIN * dt;
                if (!scopeSounded && T > ZOOM_IN_T - 0.9) { scopeSounded = true; Sfx.play("scope_on"); }
                if (T >= ZOOM_IN_T) enter(Phase.AIM);
            }
            case AIM -> { zoomE = 1; rot += SPIN * dt; updateAim(); }
            case FLIGHT -> {
                aimX = impX;
                aimY = impY;
                flightSmoke();
                if (T >= FLIGHT_T) impact();
            }
            case IMPACT -> { if (T >= IMPACT_T) shatter(); }
            case SHATTER -> { if (T >= SHATTER_T) { Sfx.play("zoom_out"); enter(Phase.ZOOM_OUT); } }
            case ZOOM_OUT -> { if (T >= ZOOM_OUT_T) glow(); }
            case GLOW -> { if (T >= GLOW_T) { finish(); return; } }
            case ABORT -> {
                zoomE = abortFrom * (1 - ease(T / ABORT_T));
                rot += SPIN * dt;
                if (T >= ABORT_T) { finish(); return; }
            }
            default -> { }
        }
        T = Math.max(0, (System.nanoTime() - phaseStart) / 1e9);
        if (phase == Phase.GLOW) { repaint(); return; }
        updateFx(dt);
        pose();
        renderBuffer();
        repaint();
    }

    private void updateAim() {
        double dx = ptrX - sw / 2.0, dy = ptrY - sh / 2.0, d = Math.hypot(dx, dy), lim = scopeR - 4;
        if (d > lim) { dx *= lim / d; dy *= lim / d; }
        aimX = sw / 2.0 + dx;
        aimY = sh / 2.0 + dy;
        servoAcc += Math.hypot(aimX - lastAimX, aimY - lastAimY);
        lastAimX = aimX;
        lastAimY = aimY;
        if (servoAcc > 42) { servoAcc = 0; Sfx.play("servo"); }
    }

    private void updateFx(double dt) {
        shake *= Math.pow(0.03, dt);
        if (phase == Phase.IMPACT) shake = Math.max(shake, 2 + 6 * T / IMPACT_T);   // the crust groans
        flash = Math.max(0, flash - dt * 2.0);
        if (phase == Phase.SHATTER || phase == Phase.ZOOM_OUT) breakT += dt;

        double drag = Math.pow(0.35, dt);
        for (int i = puffs.size() - 1; i >= 0; i--) {
            Puff p = puffs.get(i);
            p.age += dt;
            if (p.age >= p.life) { puffs.remove(i); continue; }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.size += p.grow * dt;
            p.vx *= drag;
            p.vy *= drag;
        }
        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.age += dt;
            if (s.age >= s.life) { sparks.remove(i); continue; }
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            s.vx *= drag;
            s.vy *= drag;
        }
        for (Frag f : frags) {
            f.x += f.vx * dt;
            f.y += f.vy * dt;
            f.ang += f.spin * dt;
            f.sc += f.dsc * dt;
        }
    }

    private void pose() {
        curR = r0 * Math.pow(rFinal / r0, zoomE);
        curX = p0x + (sw / 2.0 - p0x) * zoomE;
        curY = p0y + (sh / 2.0 - p0y) * zoomE;
    }

    private double irisAmt() {
        return switch (phase) {
            case ZOOM_IN -> clamp01((T / ZOOM_IN_T - 0.55) / 0.45);
            case AIM, FLIGHT, IMPACT, SHATTER -> 1;
            case ZOOM_OUT -> 1 - clamp01(T / 0.7);
            case ABORT -> abortIris * (1 - clamp01(T / 0.55));
            default -> 0;
        };
    }

    private double irisRadius(double amt) {
        double open = Math.hypot(sw, sh) / 2 + 30;
        return open + (scopeR - open) * easeOut(amt);
    }

    private void renderBuffer() {
        double clip = irisRadius(irisAmt()) / S + 2;       // pixels hidden behind the scope are never computed
        switch (phase) {
            case ZOOM_IN, AIM, ABORT, FLIGHT -> renderGlobe(curX / S, curY / S, curR / S, -1, clip);
            case IMPACT -> renderGlobe(curX / S, curY / S, curR / S, T, clip);
            default -> Arrays.fill(bufPx, 0);
        }
        drawFx();
    }

    // ================================================================ the planet shader

    private static int rgb(int r, int g, int b) { return 0xFF000000 | (r << 16) | (g << 8) | b; }

    private static double hash(int x, int y, int z) {
        int n = x * 374761393 + y * 668265263 + z * 1274126177;
        n = (n ^ (n >>> 13)) * 1103515245;
        n ^= n >>> 16;
        return (n & 0xFFFFFF) / 16777216.0;
    }

    private static double vnoise(double x, double y, double z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);
        double n000 = hash(ix, iy, iz),         n100 = hash(ix + 1, iy, iz);
        double n010 = hash(ix, iy + 1, iz),     n110 = hash(ix + 1, iy + 1, iz);
        double n001 = hash(ix, iy, iz + 1),     n101 = hash(ix + 1, iy, iz + 1);
        double n011 = hash(ix, iy + 1, iz + 1), n111 = hash(ix + 1, iy + 1, iz + 1);
        double a = n000 + (n100 - n000) * fx, b = n010 + (n110 - n010) * fx;
        double c = n001 + (n101 - n001) * fx, d = n011 + (n111 - n011) * fx;
        double e = a + (b - a) * fy, f = c + (d - c) * fy;
        return e + (f - e) * fz;
    }

    private double fbm(double x, double y, double z) {
        double sum = 0, amp = 0.5, f = 1;
        for (int i = 0; i < 5; i++) {
            sum += amp * vnoise(x * f + seed, y * f + seed * 0.61, z * f + seed * 1.37 + i * 17.0);
            f *= 2.03;
            amp *= 0.5;
        }
        return sum / 0.96875;
    }

    /** Bowl-and-rim crater at most one per grid cell; returns a height offset. */
    private double craters(double x, double y, double z, double f, int salt) {
        double fx = x * f + seed * 3.1, fy = y * f + seed * 1.7, fz = z * f + seed * 2.3;
        int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy), iz = (int) Math.floor(fz);
        if (hash(ix + salt, iy, iz) < 0.4) return 0;
        double cx = ix + 0.35 + 0.3 * hash(ix, iy + salt, iz);
        double cy = iy + 0.35 + 0.3 * hash(ix, iy, iz + salt);
        double cz = iz + 0.35 + 0.3 * hash(ix + salt, iy + salt, iz);
        double rr = 0.16 + 0.17 * hash(ix, iy, iz + salt * 3);
        double dx = fx - cx, dy = fy - cy, dz = fz - cz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d >= rr) return 0;
        double q = d / rr;
        if (q < 0.82) return -0.26 * (1 - (q * q) / (0.82 * 0.82));
        return 0.16 * (1 - (q - 0.82) / 0.18);
    }

    private static int fireAt(double heat, int x, int y) {
        double thr = (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16.0;
        int i = (int) Math.floor(Math.max(0, Math.min(1, heat)) * 7 + thr);
        return FIRE[Math.min(7, i)];
    }

    private int[] buildRamp(Color base) {
        int[] r = new int[LEVELS];
        for (int i = 0; i < LEVELS; i++) {
            double u = i / (double) (LEVELS - 1), rr, gg, bb;
            if (u < 0.62) {
                double shade = 0.10 + 0.90 * (u / 0.62);
                rr = base.getRed() * shade;
                gg = base.getGreen() * shade;
                bb = base.getBlue() * shade;
            } else {
                double k = (u - 0.62) / 0.38 * 0.8;
                rr = base.getRed() + (255 - base.getRed()) * k;
                gg = base.getGreen() + (255 - base.getGreen()) * k;
                bb = base.getBlue() + (255 - base.getBlue()) * k;
            }
            r[i] = rgb((int) rr, (int) gg, (int) bb);
        }
        return r;
    }

    /**
     * Draws the planet into the low-res buffer: noise terrain, bump lighting, ordered dithering and
     * contour lines. With impT >= 0 it also burns in the crater and spreading cracks.
     * Everything is in buffer pixels; pixels outside the clip circle (hidden by the scope) are skipped.
     */
    private void renderGlobe(double cx, double cy, double R, double impT, double clip) {
        Arrays.fill(bufPx, 0);
        if (R < 0.7) return;
        Arrays.fill(rowH, Double.NaN);

        double cr = Math.cos(rot), sr = Math.sin(rot), ct = Math.cos(TILT), st = Math.sin(TILT);
        boolean hit = impLocal != null && impT >= 0;
        double crater = hit ? 0.20 * (1 - Math.exp(-impT * 7)) : 0;
        double spread = hit ? 2.7 * Math.min(1, impT / 1.6) : 0;
        double glowAll = hit ? clamp01((impT - 1.6) / 0.8) : 0;
        double clip2 = clip * clip, ccx = bw / 2.0, ccy = bh / 2.0;

        int y0 = Math.max(0, (int) Math.floor(cy - R)), y1 = Math.min(bh - 1, (int) Math.ceil(cy + R));
        for (int y = y0; y <= y1; y++) {
            double ny = (cy - (y + 0.5)) / R;
            double rem = 1 - ny * ny;
            if (rem <= 0) continue;
            double half = Math.sqrt(rem) * R;
            int xa = Math.max(0, (int) Math.ceil(cx - half - 0.5));
            int xb = Math.min(bw - 1, (int) Math.floor(cx + half - 0.5));
            double prevH = Double.NaN;

            for (int x = xa; x <= xb; x++) {
                double ex = x + 0.5 - ccx, ey = y + 0.5 - ccy;
                if (ex * ex + ey * ey > clip2) continue;
                double nx = (x + 0.5 - cx) / R;
                double d2 = nx * nx + ny * ny;
                if (d2 >= 1) continue;
                double nz = Math.sqrt(1 - d2);

                double yy = ny * ct + nz * st, zz = -ny * st + nz * ct;      // view space -> planet space
                double lx = nx * cr - zz * sr, lz = nx * sr + zz * cr, ly = yy;

                double hgt = fbm(lx * 2.4, ly * 2.4, lz * 2.4)
                        + craters(lx, ly, lz, 3.2, 11) + craters(lx, ly, lz, 7.4, 29);
                hgt = Math.max(0, Math.min(1, (hgt - 0.5) * 1.5 + 0.5));

                double gx = Double.isNaN(prevH) ? 0 : hgt - prevH;           // slope from neighbours: free bump lighting
                double gy = Double.isNaN(rowH[x]) ? 0 : hgt - rowH[x];
                prevH = hgt;
                rowH[x] = hgt;
                double bump = Math.max(-0.5, Math.min(0.5, (gx + gy) * 5));

                double lit = 0.22 + 0.78 * Math.max(0, nx * LX + ny * LY + nz * LZ);
                double om = 1 - nz, om2 = om * om;
                double rim = om2 * om2 * om * 0.7;
                double b = lit * (0.30 + 0.95 * hgt) * (1 + bump) + rim;

                double thr = (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16.0;
                int lv = Math.min(LEVELS - 1, (int) Math.floor(Math.max(0, Math.min(1, b)) * (LEVELS - 1) + thr));
                double band = hgt * 9;
                if (band - Math.floor(band) < 0.09) lv = Math.min(LEVELS - 1, lv + 2);   // contour lines
                int color = ramp[lv];

                if (hit) {
                    double ddx = lx - impLocal[0], ddy = ly - impLocal[1], ddz = lz - impLocal[2];
                    double d = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                    double dd = d / (crater * (1 + 0.45 * (hgt - 0.5)) + 1e-6);
                    double flick = (((x * 73856093) ^ (y * 19349663) ^ (frame * 83492791)) & 255) / 255.0;
                    if (dd < 1) {
                        color = fireAt(0.25 + 0.75 * (1 - dd) + 0.18 * (flick - 0.5), x, y);      // molten crater
                    } else if (dd < 1.5) {
                        color = ramp[Math.max(0, lv - 4)];                                           // scorched ring
                    } else if (d < spread) {
                        double near = 1 - d / spread;
                        double cn = Math.abs(vnoise(lx * 3.1 + seed, ly * 3.1 + 5.3, lz * 3.1 + 2.9) - 0.5);
                        if (cn < 0.012 + 0.03 * near + 0.02 * glowAll)
                            color = fireAt(0.35 + 0.65 * near + 0.25 * glowAll + 0.2 * (flick - 0.5), x, y);
                    }
                }
                bufPx[y * bw + x] = color;
            }
        }
    }

    /** Planet-space unit vector under a screen point, or null if it is off the planet. */
    private double[] surfaceAt(double sx, double sy) {
        double nx = (sx - curX) / curR, ny = (curY - sy) / curR;
        double d2 = nx * nx + ny * ny;
        if (d2 >= 1) return null;
        double nz = Math.sqrt(1 - d2);
        double cr = Math.cos(rot), sr = Math.sin(rot), ct = Math.cos(TILT), st = Math.sin(TILT);
        double yy = ny * ct + nz * st, zz = -ny * st + nz * ct;
        return new double[]{nx * cr - zz * sr, yy, nx * sr + zz * cr};
    }

    // ================================================================ effects in the low-res buffer

    private Spark spark(double x, double y, double speed, double life) {
        Spark s = new Spark();
        double a = rnd.nextDouble() * Math.PI * 2;
        s.x = x;
        s.y = y;
        s.vx = Math.cos(a) * speed;
        s.vy = Math.sin(a) * speed;
        s.life = life;
        s.size = 1 + rnd.nextInt(2);
        return s;
    }

    private void dust(double x, double y, int count, double speedMax, double sizeBase) {
        for (int i = 0; i < count; i++) {
            Puff p = new Puff();
            double a = rnd.nextDouble() * Math.PI * 2, sp = 12 + rnd.nextDouble() * speedMax;
            p.x = x;
            p.y = y;
            p.vx = Math.cos(a) * sp;
            p.vy = Math.sin(a) * sp;
            p.size = sizeBase * (0.6 + rnd.nextDouble());
            p.grow = 8 + rnd.nextDouble() * 10;
            p.life = 1.4 + rnd.nextDouble() * 1.2;
            int tone = 40 + rnd.nextInt(40);
            p.r = tone + 25;
            p.g = tone + 10;
            p.b = tone;
            p.a = 150;
            puffs.add(p);
        }
    }

    private void flightSmoke() {
        double q = Math.pow(clamp01(T / FLIGHT_T), 1.7);
        double x = mSx + (mTx - mSx) * q, y = mSy + (mTy - mSy) * q;
        double ang = Math.atan2(mTy - mSy, mTx - mSx), sc = 0.45 + 1.15 * q;
        double tx = x - Math.cos(ang) * 24 * sc, ty = y - Math.sin(ang) * 24 * sc;
        for (int i = 0; i < 2; i++) {
            Puff p = new Puff();
            p.x = tx + (rnd.nextDouble() - 0.5) * 3 * sc;
            p.y = ty + (rnd.nextDouble() - 0.5) * 3 * sc;
            p.vx = (rnd.nextDouble() - 0.5) * 10;
            p.vy = (rnd.nextDouble() - 0.5) * 10;
            p.size = 2 + 3 * sc * rnd.nextDouble();
            p.grow = 5;
            p.life = 0.8 + rnd.nextDouble() * 0.5;
            p.r = 200;
            p.g = 210;
            p.b = 200;
            p.a = 170;
            puffs.add(p);
        }
    }

    private static Color fireColor(int idx, int a) {
        return new Color((FIRE[Math.max(0, Math.min(7, idx))] & 0xFFFFFF) | (Math.max(0, Math.min(255, a)) << 24), true);
    }

    private static Path2D poly(double... c) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(c[0], c[1]);
        for (int i = 2; i < c.length; i += 2) p.lineTo(c[i], c[i + 1]);
        p.closePath();
        return p;
    }

    private void drawFx() {
        if (phase == Phase.IDLE || phase == Phase.ZOOM_IN || phase == Phase.AIM
                || phase == Phase.ABORT || phase == Phase.GLOW) return;
        Graphics2D g = Retro.crisp(buf.createGraphics());
        boolean broken = phase == Phase.SHATTER || phase == Phase.ZOOM_OUT;

        for (Puff p : puffs) {
            int a = (int) (p.a * (1 - p.age / p.life));
            if (a <= 2) continue;
            g.setColor(new Color(p.r, p.g, p.b, a));
            g.fill(new Ellipse2D.Double(p.x - p.size, p.y - p.size, p.size * 2, p.size * 2));
        }
        if (phase == Phase.IMPACT) drawGroundBurst(g);
        if (broken) {
            drawCore(g);
            for (Frag f : frags) {
                AffineTransform at = new AffineTransform();
                at.translate(f.x, f.y);
                at.rotate(f.ang);
                at.scale(f.sc, f.sc);
                at.translate(-f.cx, -f.cy);
                g.drawImage(f.img, at, null);
            }
        }
        g.setStroke(new BasicStroke(1f));
        for (Spark s : sparks) {
            double f = s.age / s.life;
            int idx = Math.max(0, Math.min(7, 7 - (int) (f * 7.5)));
            g.setColor(fireColor(idx, (int) (255 * (1 - f * f))));
            g.fillRect((int) s.x, (int) s.y, s.size, s.size);
            g.drawLine((int) s.x, (int) s.y, (int) (s.x - s.vx * 0.035), (int) (s.y - s.vy * 0.035));
        }
        if (phase == Phase.FLIGHT) drawMissile(g);
        g.dispose();
    }

    private void drawMissile(Graphics2D g) {
        double q = Math.pow(clamp01(T / FLIGHT_T), 1.7);
        double x = mSx + (mTx - mSx) * q, y = mSy + (mTy - mSy) * q;
        double ang = Math.atan2(mTy - mSy, mTx - mSx), sc = 0.45 + 1.15 * q;
        AffineTransform old = g.getTransform();
        g.translate(x, y);
        g.rotate(ang);
        g.scale(sc, sc);
        double fl = 14 + rnd.nextDouble() * 12;                                  // flickering exhaust
        g.setColor(new Color(255, 120, 10));
        g.fill(poly(-20, -4, -20 - fl, 0, -20, 4));
        g.setColor(new Color(255, 230, 120));
        g.fill(poly(-20, -2.2, -20 - fl * 0.6, 0, -20, 2.2));
        g.setColor(new Color(120, 130, 125));                                    // fins
        g.fill(poly(-22, -4, -29, -12, -12, -4));
        g.fill(poly(-22, 4, -29, 12, -12, 4));
        g.setColor(new Color(205, 212, 205));                                    // body
        g.fill(new Rectangle2D.Double(-22, -4.5, 33, 9));
        g.setColor(new Color(130, 140, 135));
        g.fill(new Rectangle2D.Double(-22, 1.5, 33, 3));
        g.setColor(new Color(40, 50, 45));
        g.fill(new Rectangle2D.Double(-8, -4.5, 3, 9));
        g.setColor(new Color(235, 40, 30));                                      // nose cone
        g.fill(poly(11, -4.5, 26, 0, 11, 4.5));
        g.setTransform(old);
    }

    private void drawGroundBurst(Graphics2D g) {
        double t = T;
        double life = Math.max(0, 1 - Math.max(0, t - 0.5) / 1.7);
        double rb = 38 * easeOut(t / 0.5) * (1 + 0.25 * clamp01(t / 2));
        int cool = (int) Math.floor((1 - life) * 4);
        int alpha = (int) (255 * Math.min(1, life * 2.2));
        if (alpha > 3) {
            for (int i = 0; i < 6; i++) {                                         // stepped fireball bands
                double rad = rb * (1 - i * 0.15) * (1 + 0.06 * (rnd.nextDouble() - 0.5));
                g.setColor(fireColor(2 + i - cool, alpha));
                g.fill(new Ellipse2D.Double(mTx - rad, mTy - rad, rad * 2, rad * 2));
            }
        }
        for (int k = 0; k < 2; k++) {                                             // shock rings
            double tt = t - 0.05 - k * 0.22;
            if (tt < 0 || tt > 1.3) continue;
            double rr = 170 * easeOut(tt / 1.3);
            g.setStroke(new BasicStroke(k == 0 ? 2.2f : 1.2f));
            g.setColor(new Color(255, k == 0 ? 240 : 170, k == 0 ? 200 : 60, (int) (230 * (1 - tt / 1.3))));
            g.draw(new Ellipse2D.Double(mTx - rr, mTy - rr, rr * 2, rr * 2));
        }
    }

    private void drawCore(Graphics2D g) {
        double life = Math.max(0, 1 - breakT / 1.9);
        if (life <= 0.02) return;
        double rb = Math.hypot(bw, bh) * 0.55 * easeOut(breakT / 0.9);
        int cool = (int) Math.floor((1 - life) * 5);
        int alpha = (int) (255 * Math.min(1, life * 1.8));
        for (int i = 0; i < 7; i++) {
            double rad = rb * (1 - i * 0.13) * (1 + 0.04 * (rnd.nextDouble() - 0.5));
            g.setColor(fireColor(1 + i - cool, alpha));
            g.fill(new Ellipse2D.Double(mTx - rad, mTy - rad, rad * 2, rad * 2));
        }
    }

    // ================================================================ painting

    private BufferedImage grab() {
        BufferedImage img = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        sim.paint(g);
        g.dispose();
        return img;
    }

    /** Draws img so that image point (ox, oy) lands on screen point (px, py), magnified by m. */
    private void drawZoomed(Graphics2D g, BufferedImage img, double ox, double oy,
                            double px, double py, double m, double alpha) {
        if (img == null || m <= 0 || alpha <= 0.01) return;
        double sx1 = ox + (0 - px) / m, sx2 = ox + (sw - px) / m;
        double sy1 = oy + (0 - py) / m, sy2 = oy + (sh - py) / m;
        int ix1 = (int) Math.floor(Math.max(0, sx1)), ix2 = (int) Math.ceil(Math.min(img.getWidth(), sx2));
        int iy1 = (int) Math.floor(Math.max(0, sy1)), iy2 = (int) Math.ceil(Math.min(img.getHeight(), sy2));
        if (ix2 <= ix1 || iy2 <= iy1) return;
        int dx1 = (int) Math.round(px + (ix1 - ox) * m), dx2 = (int) Math.round(px + (ix2 - ox) * m);
        int dy1 = (int) Math.round(py + (iy1 - oy) * m), dy2 = (int) Math.round(py + (iy2 - oy) * m);
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, alpha)));
        g.drawImage(img, dx1, dy1, dx2, dy2, ix1, iy1, ix2, iy2, null);
        g.setComposite(old);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        if (phase == Phase.IDLE) return;
        Graphics2D g = Retro.crisp((Graphics2D) g0.create());
        if (phase == Phase.GLOW) { paintGlow(g); g.dispose(); return; }
        if (buf == null || curR <= 0) {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.dispose();
            return;
        }

        g.setColor(Color.BLACK);
        g.fillRect(0, 0, getWidth(), getHeight());
        if (shake > 0.4)
            g.translate((int) Math.round((rnd.nextDouble() * 2 - 1) * shake),
                    (int) Math.round((rnd.nextDouble() * 2 - 1) * shake));

        // the frozen simulation rushing past
        double m = curR / r0;
        if (phase == Phase.ZOOM_IN || phase == Phase.ABORT)
            drawZoomed(g, snapBefore, p0x, p0y, curX, curY, m, 1 - smooth(zoomE / 0.55));

        // globe and effects
        if (phase == Phase.ZOOM_OUT) {
            double e = ease(T / ZOOM_OUT_T);
            double k = Math.pow(kEnd, e);
            double px = sw / 2.0 + (p0x - sw / 2.0) * e, py = sh / 2.0 + (p0y - sh / 2.0) * e;
            drawZoomed(g, snapAfter, p0x, p0y, px, py, k * rFinal / r0, smooth(T / (ZOOM_OUT_T * 0.6)));
            AffineTransform keep = g.getTransform();
            g.translate(px, py);
            g.scale(k, k);
            g.translate(-sw / 2.0, -sh / 2.0);
            g.drawImage(buf, 0, 0, bw * S, bh * S, null);
            g.setTransform(keep);
        } else {
            g.drawImage(buf, 0, 0, bw * S, bh * S, null);
        }

        g.setColor(new Color(0, 0, 0, 55));                       // scanlines
        for (int y = S - 1; y < sh; y += S) g.fillRect(0, y, sw, 1);

        double amt = irisAmt();
        if (amt > 0.001) {
            drawScope(g, irisRadius(amt), amt);
            if (amt > 0.8) {
                if (phase == Phase.AIM || phase == Phase.FLIGHT) drawReticle(g);
                drawHud(g);
            }
        }

        if (phase == Phase.FLIGHT && ((int) (T * 10)) % 2 == 0) {     // incoming alert frame
            g.setColor(new Color(255, 40, 40, 120));
            g.setStroke(new BasicStroke(6f));
            g.drawRect(3, 3, sw - 7, sh - 7);
        }
        if (flash > 0.01) {
            g.setColor(new Color(255, 245, 225, (int) (255 * Math.min(1, flash))));
            g.fillRect(-60, -60, sw + 120, sh + 120);
        }
        g.dispose();
    }

    private void drawScope(Graphics2D g, double ir, double amt) {
        double cx = sw / 2.0, cy = sh / 2.0;
        Path2D.Double mask = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        mask.append(new Rectangle2D.Double(-90, -90, sw + 180, sh + 180), false);
        mask.append(new Ellipse2D.Double(cx - ir, cy - ir, ir * 2, ir * 2), false);
        g.setColor(Color.BLACK);
        g.fill(mask);
        if (amt < 0.6) return;

        Ellipse2D.Double lens = new Ellipse2D.Double(cx - ir, cy - ir, ir * 2, ir * 2);
        g.setColor(new Color(40, 255, 70, 14));                    // green glass tint
        g.fill(lens);
        for (int i = 0; i < 10; i++) {                             // vignette
            double rr = ir - 3 - i * 5;
            g.setStroke(new BasicStroke(5f));
            g.setColor(new Color(0, 0, 0, (int) (170 * Math.pow(1 - i / 10.0, 2))));
            g.draw(new Ellipse2D.Double(cx - rr, cy - rr, rr * 2, rr * 2));
        }
        g.setStroke(new BasicStroke(3f));                          // bezel
        g.setColor(Retro.GREEN_MID);
        g.draw(lens);
        g.setStroke(new BasicStroke(1f));
        g.setColor(Retro.GREEN_DIM);
        g.draw(new Ellipse2D.Double(cx - ir + 9, cy - ir + 9, ir * 2 - 18, ir * 2 - 18));
        for (int i = 0; i < 72; i++) {                             // compass ticks, drifting with the spin
            double a = Math.toRadians(i * 5) + rot * 0.6;
            double len = i % 6 == 0 ? 14 : 6, r1 = ir - 10, r2 = ir - 10 - len;
            g.setColor(i % 6 == 0 ? Retro.GREEN : Retro.GREEN_MID);
            g.drawLine((int) (cx + Math.cos(a) * r1), (int) (cy + Math.sin(a) * r1),
                    (int) (cx + Math.cos(a) * r2), (int) (cy + Math.sin(a) * r2));
        }
    }

    private void drawReticle(Graphics2D g) {
        boolean locked = phase == Phase.FLIGHT;
        double ax = locked ? impX : aimX, ay = locked ? impY : aimY;
        Color c = locked ? Retro.DANGER : Retro.RETICLE;
        double cx = sw / 2.0, cy = sh / 2.0;
        Shape old = g.getClip();
        g.setClip(new Ellipse2D.Double(cx - scopeR, cy - scopeR, scopeR * 2, scopeR * 2));

        int x = (int) ax, y = (int) ay, gap = 16;
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 120));
        g.drawLine(0, y, x - gap, y);
        g.drawLine(x + gap, y, sw, y);
        g.drawLine(x, 0, x, y - gap);
        g.drawLine(x, y + gap, x, sh);

        g.setColor(c);
        for (int i = 1; i <= 16; i++) {                            // mil-dot ticks
            int off = gap + i * 22, len = i % 5 == 0 ? 6 : 3;
            g.drawLine(x + off, y - len, x + off, y + len);
            g.drawLine(x - off, y - len, x - off, y + len);
            g.drawLine(x - len, y + off, x + len, y + off);
            g.drawLine(x - len, y - off, x + len, y - off);
        }
        g.drawOval(x - 9, y - 9, 18, 18);
        g.fillRect(x - 1, y - 1, 3, 3);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 6f}, 0f));
        g.drawOval(x - 70, y - 70, 140, 140);

        double b = locked ? 18 + 30 * (1 - easeOut(T / 0.3)) : 44 + 4 * Math.sin(System.currentTimeMillis() / 160.0);
        int bi = (int) b, bl = 10;
        g.setStroke(new BasicStroke(2f));
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                int bx = x + sx * bi, by = y + sy * bi;
                g.drawLine(bx, by, bx - sx * bl, by);
                g.drawLine(bx, by, bx, by - sy * bl);
            }
        }
        g.setClip(old);
    }

    private void drawHud(Graphics2D g) {
        long ms = System.currentTimeMillis();
        boolean blink = (ms / 400) % 2 == 0;
        g.setFont(Retro.font(11f));
        FontMetrics fm = g.getFontMetrics();
        int lh = fm.getHeight() + 3;
        boolean locked = phase == Phase.FLIGHT;

        double[] loc = surfaceAt(locked ? impX : aimX, locked ? impY : aimY);
        String lat = "---.-", lon = "---.-";
        if (loc != null) {
            lat = String.format("%+06.1f", Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, loc[1])))));
            double lg = Math.toDegrees(Math.atan2(loc[2], loc[0]));
            if (lg < 0) lg += 360;
            lon = String.format("%05.1f", lg);
        }
        String[] left = {
                "TGT  " + target.name.toUpperCase(),
                String.format("MASS %.1f", target.mass),
                "LAT  " + lat,
                "LON  " + lon,
                String.format("RNG  %04d KM", 1800 + (int) (140 * Math.sin(ms / 1100.0)))
        };
        String[] right = {
                "WPN  HE-9 TITAN",
                "FUZE ARMED",
                String.format("WIND %04.1f KT", 3.2 + 1.4 * Math.sin(ms / 700.0)),
                "LOCK " + (phase == Phase.AIM ? "SEARCH" : "TRACKING")
        };
        g.setColor(Retro.GREEN_MID);
        for (int i = 0; i < left.length; i++) g.drawString(left[i], 14, 50 + i * lh);
        for (int i = 0; i < right.length; i++)
            g.drawString(right[i], sw - 14 - fm.stringWidth(right[i]), 50 + i * lh);

        g.setColor(Retro.GREEN);
        String title = "TACTICAL SCOPE MK-IV";
        g.drawString(title, (sw - fm.stringWidth(title)) / 2, 24);
        if (blink) { g.setColor(Retro.DANGER); g.fillRect(14, 14, 9, 9); }
        g.setColor(Retro.GREEN_MID);
        g.drawString("REC", 28, 23);

        String status = "";
        Color sc = Retro.GREEN;
        switch (phase) {
            case AIM -> status = "AWAITING FIRE ORDER" + (blink ? "_" : " ");
            case FLIGHT -> { status = String.format("MISSILE AWAY // IMPACT T-%.1f", Math.max(0, FLIGHT_T - T)); sc = Retro.DANGER; }
            case IMPACT -> { status = "DIRECT HIT // CRUST FAILING"; sc = blink ? Retro.WHITE : Retro.DANGER; }
            case SHATTER -> { status = "PLANET FRAGMENTING"; sc = blink ? Retro.WHITE : Retro.DANGER; }
            default -> { }
        }
        if (!status.isEmpty()) {
            g.setColor(sc);
            g.drawString(status, (sw - fm.stringWidth(status)) / 2, sh - 34);
        }
        if (phase == Phase.AIM) {
            String hint = "[L-CLICK] FIRE    [R-CLICK / ESC] ABORT";
            g.setColor(Retro.GREEN_MID);
            g.drawString(hint, (sw - fm.stringWidth(hint)) / 2, sh - 14);
        }
    }

    /** Aftermath on top of the live simulation: shock rings, flying embers, and the banner. */
    private void paintGlow(Graphics2D g) {
        double u = clamp01(T / GLOW_T);
        for (int k = 0; k < 3; k++) {
            double tt = u * 1.25 - k * 0.14;
            if (tt <= 0 || tt >= 1) continue;
            double rr = 24 + 210 * easeOut(tt);
            g.setStroke(new BasicStroke(k == 0 ? 4f : 2f));
            g.setColor(new Color(255, k == 0 ? 235 : 150, k == 0 ? 190 : 40, (int) (230 * (1 - tt))));
            g.draw(new Ellipse2D.Double(p0x - rr, p0y - rr, rr * 2, rr * 2));
        }
        Random r = new Random(77);
        double tt = clamp01(u * 1.2);
        for (int i = 0; i < 30; i++) {
            double a = r.nextDouble() * Math.PI * 2, sp = 50 + r.nextDouble() * 190;
            int size = 3 + r.nextInt(4);
            g.setColor(fireColor(Math.max(0, 6 - (int) (tt * 7)), (int) (255 * (1 - tt))));
            g.fillRect((int) (p0x + Math.cos(a) * sp * tt), (int) (p0y + Math.sin(a) * sp * tt), size, size);
        }
        if (u < 0.9) {
            String t1 = "TARGET DESTROYED";
            String t2 = "> " + target.name.toUpperCase() + " NEUTRALIZED  //  BODIES REMAINING: " + world.bodies.size();
            Dimension d = Retro.chunkySize(t1, 3);
            g.setFont(Retro.font(12f));
            FontMetrics fm = g.getFontMetrics();
            int boxW = Math.max(d.width, fm.stringWidth(t2)) + 40;
            int bx = (sw - boxW) / 2, by = 56, boxH = d.height + 52;
            g.setColor(new Color(0, 0, 0, 215));
            g.fillRect(bx, by, boxW, boxH);
            g.setColor(Retro.GREEN);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(bx, by, boxW - 1, boxH - 1);
            Retro.chunkyText(g, t1, (sw - d.width) / 2, by + 14, 3, (T * 4) % 1 < 0.5 ? Retro.WHITE : Retro.GREEN);
            g.setFont(Retro.font(12f));
            g.setColor(Retro.GREEN_MID);
            g.drawString(t2, (sw - fm.stringWidth(t2)) / 2, by + d.height + 36);
        }
    }

    // ================================================================ math helpers

    private static double clamp01(double x) { return Math.max(0, Math.min(1, x)); }
    private static double smooth(double x)  { x = clamp01(x); return x * x * (3 - 2 * x); }
    private static double easeOut(double x) { x = clamp01(x); return 1 - Math.pow(1 - x, 3); }
    private static double ease(double x) {
        x = clamp01(x);
        return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
    }
}