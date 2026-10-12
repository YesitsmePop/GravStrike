import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.image.BufferedImage;
import java.util.Random;

/** Glass-pane takeover that plays the shutdown sequence, then exits the JVM. */
public class TerminateOverlay extends JComponent {
    private static final double WARN = 3.0;   // warning + countdown
    private static final double VCOL = 0.45;  // squash to a line
    private static final double HCOL = 0.30;  // line shrinks to a dot
    private static final double DOT  = 0.45;  // dot fades

    private static final String[] LINES = {
            "!! TERMINATE SEQUENCE INITIATED !!",
            "DISENGAGING GRAVITY WELLS........",
            "PURGING ORBITAL BODIES...........",
            "SEVERING OPERATOR LINK...........",
            "exit - 004"
    };

    private static boolean running;

    private final JFrame frame;
    private final Point home;
    private final Random rnd = new Random();
    private final long start = System.nanoTime();
    private BufferedImage snapshot;
    private Timer timer;
    private int lastSecond = -1;

    public static void run(JFrame frame) {
        if (running) return;
        running = true;
        TerminateOverlay o = new TerminateOverlay(frame);
        frame.setGlassPane(o);
        o.setVisible(true);
        o.timer = new Timer(16, e -> o.step());
        o.timer.start();
        Sfx.play("alarm");
        Sfx.stopHum();
    }

    private TerminateOverlay(JFrame frame) {
        this.frame = frame;
        this.home = frame.getLocation();
        setOpaque(false);
        MouseAdapter swallow = new MouseAdapter() { };   // block all input to the app below
        addMouseListener(swallow);
        addMouseMotionListener(swallow);
        addMouseWheelListener(swallow);
    }

    private double elapsed() { return (System.nanoTime() - start) / 1e9; }

    private void step() {
        double t = elapsed();
        if (t < WARN) {
            int sec = (int) t;
            if (sec != lastSecond) { lastSecond = sec; Sfx.play("countdown"); }   // T-3, T-2, T-1
            int mag = 2 + (int) (t * 3);
            frame.setLocation(home.x + rnd.nextInt(mag * 2 + 1) - mag,
                    home.y + rnd.nextInt(mag * 2 + 1) - mag);
        } else {
            frame.setLocation(home);
            if (snapshot == null) { takeSnapshot(); Sfx.play("powerdown"); }
        }
        if (t > WARN + VCOL + HCOL + DOT) {
            timer.stop();
            frame.dispose();
            System.exit(0);
        }
        repaint();
    }

    private void takeSnapshot() {
        Container cp = frame.getContentPane();
        snapshot = new BufferedImage(Math.max(1, cp.getWidth()), Math.max(1, cp.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = snapshot.createGraphics();
        cp.paint(sg);
        sg.dispose();
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = Retro.crisp((Graphics2D) g0.create());
        double t = elapsed();
        int w = getWidth(), h = getHeight();
        if (t < WARN) paintWarning(g, w, h, t);
        else paintCollapse(g, w, h, t - WARN);
        g.dispose();
    }

    private void paintWarning(Graphics2D g, int w, int h, double t) {
        g.setColor(new Color(0, 0, 0, (int) Math.min(185, 50 + t * 70)));
        g.fillRect(0, 0, w, h);
        double pulse = 0.5 + 0.5 * Math.sin(t * 16);
        g.setColor(new Color(255, 0, 0, (int) (28 + 60 * pulse)));
        g.fillRect(0, 0, w, h);

        Random r = new Random((long) (t * 25));            // glitch bars
        int bars = 3 + (int) (t * 5);
        for (int i = 0; i < bars; i++) {
            g.setColor(r.nextInt(3) == 0 ? new Color(255, 255, 255, 60) : new Color(255, 30, 30, 120));
            g.fillRect(0, r.nextInt(h), w, 2 + r.nextInt(12));
        }

        g.setColor(Color.BLACK);                            // scrolling hazard tape
        g.fillRect(0, 0, w, 12);
        g.fillRect(0, h - 12, w, 12);
        int shift = (int) (t * 140) % 32;
        g.setColor(new Color(255, 36, 36));
        for (int x = -32 + shift; x < w; x += 32) {
            g.fillRect(x, 0, 16, 12);
            g.fillRect(w - x - 16, h - 12, 16, 12);
        }

        int bw = Math.min(600, w - 60), bh = 176;           // terminal box
        int bx = (w - bw) / 2, by = (h - bh) / 2 + 50;
        g.setColor(Color.BLACK);
        g.fillRect(bx, by, bw, bh);
        g.setColor(Retro.DANGER);
        g.drawRect(bx, by, bw - 1, bh - 1);
        g.drawRect(bx + 2, by + 2, bw - 5, bh - 5);

        g.setFont(Retro.font(13f));
        FontMetrics fm = g.getFontMetrics();
        int remaining = (int) (t * 60);
        int ly = by + 30;
        for (int i = 0; i < LINES.length && remaining > 0; i++) {
            int n = Math.min(LINES[i].length(), remaining);
            remaining -= n;
            g.setColor(i == 0 ? Color.WHITE : Retro.DANGER);
            g.drawString(LINES[i].substring(0, n), bx + 20, ly);
            ly += fm.getHeight() + 8;
        }

        int n = Math.max(1, 3 - (int) t);                   // T-3, T-2, T-1
        String cd = "T-" + n;
        Dimension d = Retro.chunkySize(cd, 8);
        Retro.chunkyText(g, cd, (w - d.width) / 2, Math.max(30, by - d.height - 20), 8,
                (t - Math.floor(t)) < 0.18 ? Color.WHITE : Retro.DANGER);
    }

    private void paintCollapse(Graphics2D g, int w, int h, double u) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        if (snapshot == null) return;

        if (u < VCOL) {                                     // squash to a bright line
            double p = u / VCOL;
            int ch = Math.max(3, (int) (h * (1 - p * p)));
            int y = (h - ch) / 2;
            g.drawImage(snapshot, 0, y, w, ch, null);
            g.setColor(new Color(255, 255, 255, (int) (210 * p)));
            g.fillRect(0, y, w, ch);
        } else if (u < VCOL + HCOL) {                       // line shrinks to a dot
            double p = (u - VCOL) / HCOL;
            int lw = Math.max(6, (int) (w * (1 - p * p)));
            g.setColor(new Color(255, 255, 255, 60));
            g.fillRect((w - lw) / 2, h / 2 - 7, lw, 14);
            g.setColor(Color.WHITE);
            g.fillRect((w - lw) / 2, h / 2 - 1, lw, 3);
        } else {                                            // dot fades out
            double p = Math.min(1, (u - VCOL - HCOL) / DOT);
            int a = (int) (255 * (1 - p));
            int size = (int) (8 * (1 - p)) + 2;
            g.setColor(new Color(255, 255, 255, a / 4));
            g.fillRect(w / 2 - size * 2, h / 2 - size * 2, size * 4, size * 4);
            g.setColor(new Color(255, 255, 255, a));
            g.fillRect(w / 2 - size / 2, h / 2 - size / 2, size, size);
        }
    }
}