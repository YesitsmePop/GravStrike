import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.Random;

public class SimulationPanel extends JPanel {
    static final double FLOOR_Y = -300;
    static final int GRID_HALF = 500;
    static final int GRID_STEP = 100;

    private static final int P = Retro.PIXEL;
    private static final Color GRID_COLOR = new Color(57, 255, 20, 38);
    private static final Cursor BLANK_CURSOR = Toolkit.getDefaultToolkit().createCustomCursor(
            new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "blank");

    private record Projected(Body body, double sx, double sy, double scale, double depth) {}

    private final World world;
    private final Placement placement;
    private final Camera camera = new Camera();

    private BufferedImage buffer;   // low-res scene, scaled up when painted
    private Point dragFrom, pressPoint;
    private Point pointer = new Point();
    private boolean pointerInside;

    private volatile boolean paused;
    private Body hovered;
    private Consumer<Body> onTarget;

    private static final int STAR_COUNT = 450;      // more = denser sky
    private static final double STAR_FOCAL = 420;   // lower = wider field, so more stars on screen
    private final double[][] stars = new double[STAR_COUNT][4];   // x, y, z (unit vector), brightness
    private boolean graphVisible = true;

    public SimulationPanel(World world, Placement placement) {
        this.world = world;
        this.placement = placement;
        setBackground(Retro.BG);

        Random sr = new Random(1984);
        for (int i = 0; i < STAR_COUNT; i++) {
            double z = sr.nextDouble() * 2 - 1, a = sr.nextDouble() * Math.PI * 2, s = Math.sqrt(1 - z * z);
            double b = sr.nextDouble();
            stars[i] = new double[]{s * Math.cos(a), z, s * Math.sin(a), 0.25 + 0.75 * b * b};
        }

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                dragFrom = e.getPoint();
                pressPoint = e.getPoint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                pointer = e.getPoint();
                if (dragFrom == null || !SwingUtilities.isLeftMouseButton(e)) return;
                camera.yaw   += (e.getX() - dragFrom.x) * 0.008;
                camera.pitch += (e.getY() - dragFrom.y) * 0.008;
                camera.pitch = Math.max(-1.5, Math.min(1.5, camera.pitch));
                dragFrom = e.getPoint();
            }
            @Override public void mouseMoved(MouseEvent e)   { pointer = e.getPoint(); pointerInside = true; }
            @Override public void mouseEntered(MouseEvent e) { pointer = e.getPoint(); pointerInside = true; }
            @Override public void mouseExited(MouseEvent e)  { pointerInside = false; }
            @Override public void mouseReleased(MouseEvent e) {
                pointer = e.getPoint();
                if (pressPoint == null) return;
                if (pressPoint.distance(e.getPoint()) > 5) return;   // that was a camera drag
                if (!placement.isActive()) {
                    if (SwingUtilities.isLeftMouseButton(e) && !paused && onTarget != null) {
                        Body t = hoverBody();
                        if (t != null) { hovered = null; onTarget.accept(t); }
                    }
                    return;
                }
                if (SwingUtilities.isRightMouseButton(e)) {
                    placement.back();
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    trackPointer(e.getPoint());
                    placement.confirm();
                }
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                camera.distance *= 1 + e.getWheelRotation() * 0.08;
                camera.distance = Math.max(200, Math.min(4000, camera.distance));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "toggleMute");
        getActionMap().put("toggleMute", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
                        instanceof javax.swing.text.JTextComponent) return;   // typing a name
                Sfx.setMuted(!Sfx.isMuted());
                if (!Sfx.isMuted()) Sfx.play("click");   // confirmation blip when sound comes back
            }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), "toggleGraph");
        getActionMap().put("toggleGraph", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (paused) return;   // strike sequence is running
                if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
                        instanceof javax.swing.text.JTextComponent) return;   // typing a name
                graphVisible = !graphVisible;
                Sfx.play("click");
            }
        });

        placement.addListener(() ->
                setCursor(placement.isActive() ? BLANK_CURSOR : Cursor.getDefaultCursor()));

        new Timer(16, e -> {
            if (placement.isActive()) {
                if (pointerInside) trackPointer(pointer);   // scene is frozen while placing
            } else if (!paused) {
                world.update(0.016);
            }
            updateHover();
            repaint();
        }).start();
    }

    private void trackPointer(Point p) {
        double t = camera.closestOnAxis(p.x, p.y, getWidth(), getHeight(),
                placement.axisOrigin(), placement.axisDir());
        if (!Double.isNaN(t)) placement.setParam(t);
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth(), h = getHeight();
        int lw = Math.max(1, w / P), lh = Math.max(1, h / P);
        if (buffer == null || buffer.getWidth() != lw || buffer.getHeight() != lh)
            buffer = new BufferedImage(lw, lh, BufferedImage.TYPE_INT_RGB);

        // 1) draw the scene at low resolution, no smoothing
        Graphics2D g2 = Retro.crisp(buffer.createGraphics());
        g2.setColor(Retro.BG);
        g2.fillRect(0, 0, lw, lh);
        drawStars(g2);
        if (graphVisible || placement.isActive()) {
            drawGrid(g2);
            drawAxes(g2);
            for (Body b : world.bodies) drawDropLine(g2, b.x, b.y, b.z, b.radius, b.color);
        }
        drawTrails(g2);
        drawBodies(g2);
        drawPlacement(g2);
        g2.dispose();

        // 2) blow it up with hard pixel edges, then crisp text and scanlines on top
        Graphics2D out = Retro.crisp((Graphics2D) g.create());
        out.drawImage(buffer, 0, 0, lw * P, lh * P, null);
        drawText(out, w, h);
        drawHover(out, w, h);
        if (Retro.SCANLINES) {
            out.setColor(new Color(0, 0, 0, 60));
            for (int y = P - 1; y < h; y += P) out.fillRect(0, y, w, 1);
        }
        out.dispose();
    }

    // ---------------------------------------------------------------- helpers

    /** Camera projection, converted into low-res buffer units. */
    private double[] proj(double x, double y, double z) {
        double[] p = camera.project(x, y, z, getWidth(), getHeight());
        if (p == null) return null;
        p[0] /= P; p[1] /= P; p[2] /= P;
        return p;
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color(
                (int) (a.getRed()   + (b.getRed()   - a.getRed())   * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
    }

    private void line3D(Graphics2D g2, double x1, double y1, double z1,
                        double x2, double y2, double z2) {
        double[] a = proj(x1, y1, z1);
        double[] b = proj(x2, y2, z2);
        if (a == null || b == null) return;
        g2.draw(new Line2D.Double(a[0], a[1], b[0], b[1]));
    }

    /** Four hard bands, shadow to highlight, like a pixel-art ball. */
    private void drawSphere(Graphics2D g2, double sx, double sy, double r, Color base) {
        int cx = (int) Math.round(sx), cy = (int) Math.round(sy);
        Color light = mix(base, Color.WHITE, 0.6);
        if (r < 2.5) {
            int s = (int) Math.max(1, Math.round(r * 2));
            g2.setColor(light);
            g2.fillRect(cx - s / 2, cy - s / 2, s, s);
            return;
        }
        Color shadow = mix(Color.BLACK, base, 0.28);
        final int BANDS = 4;
        for (int i = 0; i < BANDS; i++) {
            double f = i / (double) (BANDS - 1);   // 0 = rim, 1 = highlight
            double rr = r * (1 - 0.72 * f);
            double off = -r * 0.30 * f;
            g2.setColor(mix(shadow, light, f));
            g2.fill(new Ellipse2D.Double(cx + off - rr, cy + off - rr, rr * 2, rr * 2));
        }
    }

    // ---------------------------------------------------------------- scene

    private void drawGrid(Graphics2D g2) {
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(GRID_COLOR);
        for (int i = -GRID_HALF; i <= GRID_HALF; i += GRID_STEP) {
            line3D(g2, i, FLOOR_Y, -GRID_HALF, i, FLOOR_Y, GRID_HALF);
            line3D(g2, -GRID_HALF, FLOOR_Y, i, GRID_HALF, FLOOR_Y, i);
        }
    }

    private void drawAxis(Graphics2D g2, double x, double y, double z, Color c) {
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(alpha(c, 70));
        line3D(g2, 0, 0, 0, -x, -y, -z);
        g2.setColor(alpha(c, 235));
        line3D(g2, 0, 0, 0, x, y, z);
    }

    private void drawAxes(Graphics2D g2) {
        boolean placing = placement.isActive();
        double L = GRID_HALF;
        drawAxis(g2, L, 0, 0, placing ? Retro.AXIS_X : Retro.WHITE);
        drawAxis(g2, 0, L, 0, placing ? Retro.AXIS_Y : Retro.WHITE);
        drawAxis(g2, 0, 0, L, placing ? Retro.AXIS_Z : Retro.WHITE);
    }

    private void drawDropLine(Graphics2D g2, double x, double y, double z, double radius, Color c) {
        double[] top = proj(x, y, z);
        double[] foot = proj(x, FLOOR_Y, z);
        if (top == null || foot == null) return;

        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{2f, 2f}, 0f));
        g2.setColor(alpha(c, 100));
        g2.draw(new Line2D.Double(top[0], top[1], foot[0], foot[1]));

        double flatten = Math.max(0.2, Math.abs(Math.sin(camera.pitch)));
        double r = Math.max(1.5, radius * foot[2] * 0.7);
        g2.setColor(alpha(c, 110));
        g2.fill(new Ellipse2D.Double(foot[0] - r, foot[1] - r * flatten, r * 2, r * 2 * flatten));
    }

    private void drawTrails(Graphics2D g2) {
        g2.setStroke(new BasicStroke(1f));
        for (Body b : world.bodies) {
            double[] prev = null;
            int n = b.trail.size(), i = 0;
            for (double[] p : b.trail) {
                double[] cur = proj(p[0], p[1], p[2]);
                if (prev != null && cur != null) {
                    g2.setColor(alpha(b.color, (int) (150.0 * i / n)));
                    g2.draw(new Line2D.Double(prev[0], prev[1], cur[0], cur[1]));
                }
                prev = cur;
                i++;
            }
        }
    }

    private void drawBodies(Graphics2D g2) {
        List<Projected> list = new ArrayList<>();
        for (Body b : world.bodies) {
            double[] p = proj(b.x, b.y, b.z);
            if (p != null) list.add(new Projected(b, p[0], p[1], p[2], p[3]));
        }
        list.sort(Comparator.comparingDouble(Projected::depth).reversed());
        for (Projected p : list) {
            double r = Math.max(1.5, p.body().radius * p.scale());
            drawSphere(g2, p.sx(), p.sy(), r, p.body().color);
        }
    }

    // ---------------------------------------------------------------- placement (scene part)

    private Color stageColor(Placement.Stage s) {
        return switch (s) {
            case X -> Retro.AXIS_X;
            case Z -> Retro.AXIS_Z;
            default -> Retro.AXIS_Y;
        };
    }

    private void drawPlacement(Graphics2D g2) {
        if (!placement.isActive()) return;
        Body pb = placement.pending;
        Placement.Stage stage = placement.stage;
        double px = placement.px, py = placement.py, pz = placement.pz;
        Color axisColor = stageColor(stage);

        // locked-in values as dashed guides
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{2f, 2f}, 0f));
        if (stage != Placement.Stage.X) {
            g2.setColor(alpha(Retro.AXIS_X, 200));
            line3D(g2, 0, 0, 0, px, 0, 0);
        }
        if (stage == Placement.Stage.Y) {
            g2.setColor(alpha(Retro.AXIS_Z, 200));
            line3D(g2, px, 0, 0, px, 0, pz);
        }

        // the active axis: wide dim glow, bright 1px core, tick squares
        double[] o = placement.axisOrigin(), a = placement.axisDir();
        double lo = placement.lo(), hi = placement.hi();
        double ax1 = o[0] + a[0] * lo, ay1 = o[1] + a[1] * lo, az1 = o[2] + a[2] * lo;
        double ax2 = o[0] + a[0] * hi, ay2 = o[1] + a[1] * hi, az2 = o[2] + a[2] * hi;

        g2.setStroke(new BasicStroke(3f));
        g2.setColor(alpha(axisColor, 50));
        line3D(g2, ax1, ay1, az1, ax2, ay2, az2);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(axisColor);
        line3D(g2, ax1, ay1, az1, ax2, ay2, az2);

        for (double t = Math.ceil(lo / GRID_STEP) * GRID_STEP; t <= hi; t += GRID_STEP) {
            double[] tick = proj(o[0] + a[0] * t, o[1] + a[1] * t, o[2] + a[2] * t);
            if (tick != null) g2.fillRect((int) Math.round(tick[0]) - 1, (int) Math.round(tick[1]) - 1, 3, 3);
        }

        // ghost planet, drop line, ring
        drawDropLine(g2, px, py, pz, pb.radius, pb.color);
        double[] p = proj(px, py, pz);
        if (p != null) {
            double r = Math.max(2, pb.radius * p[2]);
            drawSphere(g2, p[0], p[1], r, pb.color);
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(axisColor);
            double ring = r + 2;
            g2.draw(new Ellipse2D.Double(Math.round(p[0]) - ring, Math.round(p[1]) - ring, ring * 2, ring * 2));
        }
    }

    // ---------------------------------------------------------------- text layer (full resolution)

    private void label(Graphics2D out, String s, double x, double y, double z, Color c, int w, int h) {
        double[] p = camera.project(x, y, z, w, h);
        if (p == null) return;
        out.setColor(c);
        out.drawString(s, (int) (Math.round(p[0] / P) * P) + 6, (int) (Math.round(p[1] / P) * P) - 6);
    }

    private void drawText(Graphics2D out, int w, int h) {
        out.setFont(Retro.font(12f));
        FontMetrics fm = out.getFontMetrics();
        boolean placing = placement.isActive();

        if (graphVisible || placing) {
            label(out, "X", GRID_HALF, 0, 0, placing ? Retro.AXIS_X : Retro.WHITE, w, h);
            label(out, "Y", 0, GRID_HALF, 0, placing ? Retro.AXIS_Y : Retro.WHITE, w, h);
            label(out, "Z", 0, 0, GRID_HALF, placing ? Retro.AXIS_Z : Retro.WHITE, w, h);
        }

        // top-left terminal readout
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        int yaw = (int) ((Math.round(Math.toDegrees(camera.yaw)) % 360 + 360) % 360);
        int pitch = (int) Math.round(Math.toDegrees(camera.pitch));
        out.setColor(Retro.GREEN);
        int y = 20;
        out.drawString("GRAV-STRIKE // SYS ONLINE" + (blink ? "_" : " "), 14, y);
        out.setColor(Retro.GREEN_MID);
        y += fm.getHeight();
        out.drawString(String.format("BODIES %02d", world.bodies.size()), 14, y);
        y += fm.getHeight();
        out.drawString(String.format("YAW %03d  PITCH %+03d", yaw, pitch), 14, y);
        y += fm.getHeight();
        out.setColor(Sfx.isMuted() ? Retro.DANGER : Retro.GREEN_MID);
        out.drawString(Sfx.isMuted() ? "SOUND OFF [M]" : "SOUND ON  [M]", 14, y);
        y += fm.getHeight();
        out.setColor(Retro.GREEN_MID);
        out.drawString(graphVisible ? "GRAPH ON   [G]" : "GRAPH OFF  [G]", 14, y);
        y += fm.getHeight();
        out.setColor(Retro.GREEN_MID);
        out.drawString("RUNNING V. 10.12.26", 14, y);

        if (!placing) {
            out.drawString("DRAG: ORBIT   WHEEL: ZOOM", 14, h - 14);
            return;
        }

        // coordinate readout beside the ghost planet
        Body pb = placement.pending;
        double[] p = camera.project(placement.px, placement.py, placement.pz, w, h);
        if (p != null) {
            double r = pb.radius * p[2];
            String s = switch (placement.stage) {
                case X -> "X=" + Math.round(placement.px);
                case Z -> "Z=" + Math.round(placement.pz);
                default -> "Y=" + Math.round(placement.py);
            };
            out.setColor(Retro.WHITE);
            out.drawString(s, (int) (p[0] + r + 12), (int) (p[1] - r - 6));
        }

        // instruction banner
        String text = ("STEP " + placement.step() + "/3 > " + placement.hint()
                + "   R-CLICK: BACK   ESC: CANCEL").toUpperCase();
        int bw = fm.stringWidth(text) + 24, bh = fm.getHeight() + 12;
        int bx = (w - bw) / 2, by = h - bh - 24;
        out.setColor(Color.BLACK);
        out.fillRect(bx, by, bw, bh);
        out.setColor(Retro.GREEN);
        out.drawRect(bx, by, bw - 1, bh - 1);
        out.drawString(text, bx + 12, by + 6 + fm.getAscent());
    }

    /** Used by StrikeView: freezes physics and hover while the strike sequence runs. */
    public void setPaused(boolean p) {
        paused = p;
        if (p) hovered = null;
    }

    /** Used by StrikeView: {screenX, screenY, screenRadius} for a body, or null if it's behind the camera. */
    public double[] screenOf(Body b) {
        double[] p = camera.project(b.x, b.y, b.z, getWidth(), getHeight());
        if (p == null) return null;
        return new double[]{p[0], p[1], Math.max(b.radius * p[2], 1.5 * P)};
    }

    public void setTargetHandler(Consumer<Body> handler) { onTarget = handler; }

    /** Frontmost body under the pointer, or null. Small planets get a minimum hit radius. */
    private Body hoverBody() {
        if (paused || placement.isActive() || !pointerInside) return null;
        int w = getWidth(), h = getHeight();
        Body best = null;
        double bestDepth = Double.MAX_VALUE;
        for (Body b : world.bodies) {
            double[] p = camera.project(b.x, b.y, b.z, w, h);
            if (p == null) continue;
            double r = Math.max(b.radius * p[2] + 3, 10);
            if (Math.hypot(pointer.x - p[0], pointer.y - p[1]) <= r && p[3] < bestDepth) {
                best = b;
                bestDepth = p[3];
            }
        }
        return best;
    }

    private void updateHover() {
        Body now = hoverBody();
        if (now != hovered) {
            hovered = now;
            if (!placement.isActive())
                setCursor(now != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        }
    }

    /** Green corner brackets and a tag around the planet under the cursor. */
    private void drawHover(Graphics2D out, int w, int h) {
        Body b = hovered;
        if (b == null || paused || placement.isActive()) return;
        double[] p = camera.project(b.x, b.y, b.z, w, h);
        if (p == null) return;

        int cx = (int) (Math.round(p[0] / P) * P), cy = (int) (Math.round(p[1] / P) * P);
        int r = (int) Math.max(b.radius * p[2], 1.5 * P) + 9
                + ((System.currentTimeMillis() / 250) % 2 == 0 ? 0 : 2);   // breathing
        int len = Math.max(8, r / 2), t = 2;
        out.setColor(Retro.GREEN);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                int x = cx + sx * r, y = cy + sy * r;
                out.fillRect(sx < 0 ? x : x - len, sy < 0 ? y : y - t, len, t);   // horizontal arm
                out.fillRect(sx < 0 ? x : x - t, sy < 0 ? y : y - len, t, len);   // vertical arm
            }
        }
        out.setFont(Retro.font(11f));
        out.drawString("TGT: " + b.name.toUpperCase() + "  [CLICK: TARGET]", cx + r + 8, cy - r + 4);
    }

    private void drawStars(Graphics2D g2) {
        int w = getWidth(), h = getHeight();
        int lw = w / P, lh = h / P;
        long tick = System.currentTimeMillis() / 400;
        for (int i = 0; i < stars.length; i++) {
            double[] s = stars[i];
            double[] p = camera.projectSky(s[0], s[1], s[2], STAR_FOCAL, w, h);
            if (p == null) continue;
            double px = p[0] / P, py = p[1] / P;
            if (px < 1 || py < 1 || px >= lw - 1 || py >= lh - 1) continue;
            int x = (int) px, y = (int) py;

            double b = s[3];
            if ((tick + i * 7) % 23 == 0) b *= 0.4;                 // slow twinkle
            int v = (int) (35 + 150 * b);
            g2.setColor(new Color((int) (v * 0.6), v, (int) (v * 0.65)));
            g2.fillRect(x, y, 1, 1);
            if (s[3] > 0.92) {                                       // brightest stars get a tiny cross
                g2.setColor(new Color((int) (v * 0.25), (int) (v * 0.45), (int) (v * 0.3)));
                g2.fillRect(x - 1, y, 1, 1);
                g2.fillRect(x + 1, y, 1, 1);
                g2.fillRect(x, y - 1, 1, 1);
                g2.fillRect(x, y + 1, 1, 1);
            }
        }
    }
}