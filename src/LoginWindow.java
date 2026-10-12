import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.util.Random;

public class LoginWindow extends JFrame {
    static final String ACCESS_CODE = "MOONROCK26";
    static final int MAX_STRIKES = 3;

    private static final int W = 520, H = 380;

    public LoginWindow(Runnable onGranted) {
        super("GRAV-STRIKE // SECURE ACCESS");
        setUndecorated(true);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        GatePanel gate = new GatePanel(() -> { onGranted.run(); dispose(); });
        setContentPane(gate);
        setSize(W, H);
        setLocationRelativeTo(null);
        ImageIcon icon = new ImageIcon(Main.class.getResource("/gravpic.png"));
        setIconImage(icon.getImage());
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { gate.requestFocusInWindow(); }
        });
    }

    private static class GatePanel extends JPanel {
        private enum Phase { CODE, CHECK, SCAN, GRANTED, DENIED }

        private static final int TOKEN_LEN = 5;
        private static final int TOKEN_MS = 20_000;
        private static final String TOKEN_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        private static final Rectangle SENSOR = new Rectangle(56, 160, 120, 120);

        private static final String[] BOOT = {
                ">> CLEARANCE LEVEL: OPERATOR",
                ">> VERIFYING OPERATOR.......... OK",
                ">> MOUNTING GRAVITY ENGINE..... OK",
                ">> CALIBRATING BODIES.......... OK",
                ">> ACCESS GRANTED. WELCOME, OPERATOR."
        };
        private static final int BOOT_CHARS = java.util.Arrays.stream(BOOT).mapToInt(String::length).sum();

        private static final String[] LOCK = {
                "...#####...",
                "..#.....#..",
                "..#.....#..",
                "..#.....#..",
                ".#########.",
                ".#########.",
                ".####.####.",
                ".####.####.",
                ".#########.",
                ".#########.",
                ".#########."
        };

        private final Runnable onGranted;
        private final Random rnd = new Random();
        private final StringBuilder input = new StringBuilder();
        private final Timer timer;

        private Phase phase = Phase.CODE;
        private int deniedStage;
        private int strikes;
        private String message = "AUTHORIZED OPERATORS ONLY.";
        private Color messageColor = Retro.GREEN_MID;
        private String token = "";
        private long tokenExpires, shakeUntil, phaseStart = System.currentTimeMillis();
        private double scan;
        private boolean spaceHeld, mouseHeld, finished;
        private int typed;
        private int lastSeg;

        GatePanel(Runnable onGranted) {
            this.onGranted = onGranted;
            setBackground(Color.BLACK);
            setFocusable(true);

            addKeyListener(new KeyAdapter() {
                @Override public void keyTyped(KeyEvent e) { typedChar(e.getKeyChar()); }
                @Override public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_ESCAPE -> System.exit(0);
                        case KeyEvent.VK_BACK_SPACE -> {
                            if (input.length() > 0) { input.setLength(input.length() - 1); Sfx.play("back");}
                        }
                        case KeyEvent.VK_ENTER -> submit();
                        case KeyEvent.VK_SPACE -> spaceHeld = true;
                        default -> { }
                    }
                }
                @Override public void keyReleased(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_SPACE) spaceHeld = false;
                }
            });

            MouseAdapter mouse = new MouseAdapter() {
                private Point grab;
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    grab = e.getPoint();
                    if (phase == Phase.SCAN && SENSOR.contains(e.getPoint())) {
                        mouseHeld = true;
                        grab = null;
                    }
                }
                @Override public void mouseReleased(MouseEvent e) { mouseHeld = false; grab = null; }
                @Override public void mouseDragged(MouseEvent e) {
                    if (grab == null) return;
                    Window win = SwingUtilities.getWindowAncestor(GatePanel.this);
                    Point p = e.getLocationOnScreen();
                    win.setLocation(p.x - grab.x, p.y - grab.y);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);

            timer = new Timer(33, e -> tick());
            timer.start();
        }

        // ------------------------------------------------------------ logic

        private static boolean codeChar(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
        }

        private void typedChar(char raw) {
            char c = Character.toUpperCase(raw);
            boolean ok = switch (phase) {
                case CODE -> codeChar(c) && input.length() < 16;
                case CHECK -> TOKEN_CHARS.indexOf(c) >= 0 && input.length() < TOKEN_LEN;
                default -> false;
            };
            if (ok) { input.append(c); Sfx.play("key");}
        }

        private void say(String msg, Color c) { message = msg; messageColor = c; }

        private void newToken() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < TOKEN_LEN; i++) sb.append(TOKEN_CHARS.charAt(rnd.nextInt(TOKEN_CHARS.length())));
            token = sb.toString();
            tokenExpires = System.currentTimeMillis() + TOKEN_MS;
        }

        private int stage() {
            return switch (phase) {
                case CODE -> 0;
                case CHECK -> 1;
                case SCAN -> 2;
                case GRANTED -> 3;
                case DENIED -> deniedStage;
            };
        }

        private void submit() {
            String in = input.toString();
            switch (phase) {
                case CODE -> {
                    if (in.equals(ACCESS_CODE)) {
                        phase = Phase.CHECK;
                        input.setLength(0);
                        newToken();
                        say("CODE ACCEPTED. COMPLETE SECURITY CHECK.", Retro.GREEN);
                        Sfx.play("accept");
                    } else {
                        fail("INVALID ACCESS CODE");
                    }
                }
                case CHECK -> {
                    if (in.equals(token)) {
                        phase = Phase.SCAN;
                        input.setLength(0);
                        say("TOKEN VERIFIED. BIOMETRIC SCAN REQUIRED.", Retro.GREEN);
                        Sfx.play("accept");
                    } else {
                        fail("TOKEN MISMATCH");
                        newToken();
                    }
                }
                default -> { }
            }
        }

        private void fail(String msg) {
            strikes++;
            Sfx.play(strikes >= MAX_STRIKES ? "alarm" : "deny");
            input.setLength(0);
            shakeUntil = System.currentTimeMillis() + 450;
            if (strikes >= MAX_STRIKES) {
                deniedStage = stage();
                phase = Phase.DENIED;
                phaseStart = System.currentTimeMillis();
            } else {
                say(msg + "  [" + strikes + "/" + MAX_STRIKES + "]", Retro.DANGER);
            }
        }

        private void tick() {
            long now = System.currentTimeMillis();
            switch (phase) {
                case CHECK -> {
                    if (now > tokenExpires) {
                        newToken();
                        input.setLength(0);
                        say("TOKEN EXPIRED. NEW TOKEN ISSUED.", Retro.DANGER);
                        Sfx.play("deny");
                    }
                }
                case SCAN -> {
                    boolean hold = spaceHeld || mouseHeld;
                    scan = Math.max(0, Math.min(1, scan + (hold ? 0.018 : -0.03)));
                    int seg = (int) (scan * 18);
                    if (hold && seg > lastSeg) Sfx.play("scan");   // one blip per bar segment
                    lastSeg = seg;
                    if (scan >= 1) {
                        phase = Phase.GRANTED;
                        phaseStart = now;
                    }
                }
                case GRANTED -> {
                    typed += 2;
                    if (typed <= BOOT_CHARS && typed % 4 == 0) Sfx.play("type");               // typewriter
                    if (typed > BOOT_CHARS && typed - 2 <= BOOT_CHARS) Sfx.play("granted");    // fanfare with banner
                    if (!finished && typed > BOOT_CHARS + 40) {
                        finished = true;
                        timer.stop();
                        onGranted.run();
                    }
                }
                case DENIED -> {
                    if (now - phaseStart > 3000) System.exit(0);
                }
                default -> { }
            }
            repaint();
        }

        // ------------------------------------------------------------ painting

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = Retro.crisp((Graphics2D) g0.create());
            long now = System.currentTimeMillis();
            boolean denied = phase == Phase.DENIED;
            Color accent = denied ? Retro.DANGER : phase == Phase.GRANTED ? Retro.WHITE : Retro.GREEN;

            g.setColor(Color.BLACK);
            g.fillRect(0, 0, W, H);

            AffineTransform home = g.getTransform();
            if (now < shakeUntil) g.translate(rnd.nextInt(11) - 5, rnd.nextInt(5) - 2);

            drawChrome(g, accent);
            drawHeader(g, accent);
            drawSteps(g);
            switch (phase) {
                case CODE -> drawCode(g, now);
                case CHECK -> drawCheck(g, now);
                case SCAN -> drawScan(g, now);
                case GRANTED -> drawGranted(g, now);
                case DENIED -> drawDenied(g, now);
            }
            drawFooter(g);

            g.setTransform(home);
            if (denied && (now / 160) % 2 == 0) {
                g.setColor(new Color(255, 0, 0, 45));
                g.fillRect(0, 0, W, H);
            }
            g.setColor(new Color(0, 0, 0, 70));          // scanlines
            for (int y = 2; y < H; y += 3) g.fillRect(0, y, W, 1);
            g.dispose();
        }

        private void drawBitmap(Graphics2D g, String[] bmp, int x, int y, int s, Color c) {
            g.setColor(c);
            for (int row = 0; row < bmp.length; row++)
                for (int col = 0; col < bmp[row].length(); col++)
                    if (bmp[row].charAt(col) == '#') g.fillRect(x + col * s, y + row * s, s, s);
        }

        private void centered(Graphics2D g, String s, int y) {
            g.drawString(s, (W - g.getFontMetrics().stringWidth(s)) / 2, y);
        }

        private void drawChrome(Graphics2D g, Color accent) {
            g.setColor(accent);
            g.fillRect(0, 0, W, 24);
            g.drawRect(0, 0, W - 1, H - 1);
            g.setColor(phase == Phase.DENIED ? new Color(110, 20, 20) : Retro.GREEN_DIM);
            g.drawRect(6, 32, W - 13, H - 39);

            g.setFont(Retro.font(11f));
            FontMetrics fm = g.getFontMetrics();
            int by = (24 - fm.getHeight()) / 2 + fm.getAscent();
            g.setColor(Color.BLACK);
            g.drawString("GRAV-STRIKE // SECURE ACCESS TERMINAL", 10, by);
            String esc = "[ESC] ABORT";
            g.drawString(esc, W - 10 - fm.stringWidth(esc), by);
        }

        private void drawHeader(Graphics2D g, Color accent) {
            drawBitmap(g, LOCK, 26, 44, 3, accent);
            Retro.chunkyText(g, "RESTRICTED ACCESS", 74, 44, 2, accent);
            g.setFont(Retro.font(10f));
            g.setColor(Retro.GREEN_MID);
            g.drawString("AUTHORIZED OPERATORS ONLY :: ALL ACCESS IS LOGGED", 74, 82);
        }

        private void drawSteps(Graphics2D g) {
            String[] names = {"1 ACCESS CODE", "2 SECURITY CHECK", "3 BIOMETRIC"};
            int stage = stage();
            g.setFont(Retro.font(10f));
            FontMetrics fm = g.getFontMetrics();
            for (int i = 0; i < 3; i++) {
                int x = 24 + i * 160, w = 152, y = 92, h = 22;
                boolean done = i < stage, active = i == stage;
                Color c = active ? (phase == Phase.DENIED ? Retro.DANGER : Retro.GREEN)
                        : done ? Retro.GREEN_MID : Retro.GREEN_DIM;
                g.setColor(c);
                if (active) g.fillRect(x, y, w, h);
                else g.drawRect(x, y, w - 1, h - 1);
                g.setColor(active ? Color.BLACK : c);
                g.drawString(names[i] + (done ? " OK" : ""), x + 8,
                        y + (h - fm.getHeight()) / 2 + fm.getAscent());
            }
        }

        private void drawField(Graphics2D g, Rectangle r, String text, long now) {
            g.setColor(Color.BLACK);
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(Retro.GREEN);
            g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
            g.setColor(Retro.GREEN_DIM);
            g.drawRect(r.x + 2, r.y + 2, r.width - 5, r.height - 5);

            g.setFont(Retro.font(14f));
            FontMetrics fm = g.getFontMetrics();
            String shown = "> " + text;
            int base = r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent();
            g.setColor(Retro.GREEN);
            g.drawString(shown, r.x + 12, base);
            if ((now / 450) % 2 == 0)
                g.fillRect(r.x + 12 + fm.stringWidth(shown) + 3, r.y + 8, 9, r.height - 16);
        }

        private void drawCode(Graphics2D g, long now) {
            g.setFont(Retro.font(12f));
            g.setColor(Retro.GREEN);
            g.drawString("> ENTER ACCESS CODE", 24, 150);
            drawField(g, new Rectangle(24, 162, 472, 34), "*".repeat(input.length()), now);

            g.setFont(Retro.font(11f));
            g.setColor(Retro.GREEN_MID);
            g.drawString("TYPE CODE, PRESS [ENTER]. 3 STRIKES = LOCKOUT.", 24, 222);

            Random r = new Random(now / 140);          // fake data stream
            g.setColor(Retro.GREEN_DIM);
            for (int row = 0; row < 3; row++) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 20; i++) sb.append(String.format("%02X ", r.nextInt(256)));
                g.drawString(sb.toString(), 24, 262 + row * 16);
            }
        }

        private void drawCheck(Graphics2D g, long now) {
            g.setFont(Retro.font(12f));
            g.setColor(Retro.GREEN);
            g.drawString("> SECURITY CHECK: RETYPE THE TOKEN", 24, 150);

            Rectangle box = new Rectangle(24, 160, 300, 60);
            g.setColor(Color.BLACK);
            g.fillRect(box.x, box.y, box.width, box.height);
            g.setColor(Retro.GREEN_MID);
            g.drawRect(box.x, box.y, box.width - 1, box.height - 1);

            Random r = new Random(now / 130);
            g.setFont(Retro.font(26f));
            FontMetrics fm = g.getFontMetrics();
            int cw = 52;
            int x0 = box.x + (box.width - cw * TOKEN_LEN) / 2;
            for (int i = 0; i < TOKEN_LEN; i++) {
                String ch = String.valueOf(token.charAt(i));
                g.setColor(i % 2 == 0 ? Retro.GREEN : Retro.WHITE);
                g.drawString(ch, x0 + i * cw + (cw - fm.stringWidth(ch)) / 2,
                        box.y + (box.height - fm.getHeight()) / 2 + fm.getAscent() + r.nextInt(9) - 4);
            }
            g.setColor(new Color(57, 255, 20, 110));      // strike-through noise
            for (int i = 0; i < 3; i++)
                g.drawLine(box.x + 2, box.y + 4 + r.nextInt(box.height - 8),
                        box.x + box.width - 3, box.y + 4 + r.nextInt(box.height - 8));
            for (int i = 0; i < 60; i++)
                g.fillRect(box.x + 2 + r.nextInt(box.width - 6), box.y + 2 + r.nextInt(box.height - 6), 2, 2);

            double left = Math.max(0, tokenExpires - now);
            double frac = left / TOKEN_MS;
            boolean low = frac < 0.25;
            int lit = (int) Math.ceil(frac * 11);
            g.setFont(Retro.font(11f));
            g.setColor(low ? Retro.DANGER : Retro.GREEN_MID);
            g.drawString("TOKEN EXPIRES IN " + (int) Math.ceil(left / 1000) + "S", 344, 178);
            for (int i = 0; i < 11; i++) {
                g.setColor(i < lit ? (low ? Retro.DANGER : Retro.GREEN) : Retro.GREEN_DIM);
                g.fillRect(344 + i * 13, 190, 10, 14);
            }

            drawField(g, new Rectangle(24, 236, 300, 34), input.toString(), now);
            g.setFont(Retro.font(11f));
            g.setColor(Retro.GREEN_MID);
            g.drawString("RETYPE THE TOKEN, PRESS [ENTER]", 24, 292);
        }

        private void drawScan(Graphics2D g, long now) {
            g.setFont(Retro.font(12f));
            g.setColor(Retro.GREEN);
            g.drawString("> BIOMETRIC VERIFICATION", 24, 150);

            boolean holding = spaceHeld || mouseHeld;
            Rectangle s = SENSOR;
            g.setColor(Color.BLACK);
            g.fillRect(s.x, s.y, s.width, s.height);

            Shape oldClip = g.getClip();
            g.setClip(s);
            double litY = s.y + s.height - scan * s.height;
            int cx = s.x + s.width / 2, cy = s.y + s.height / 2 + 8;
            for (int ring = 0; ring < 6; ring++) {                  // pixel fingerprint
                double rad = 9 + ring * 7;
                for (double a = 0; a < Math.PI * 2; a += 3.0 / rad) {
                    double sa = Math.sin(a);
                    if (sa < -0.6 && ring % 2 == 1) continue;
                    if (((int) (a * 6) + ring * 3) % 8 == 0) continue;
                    int px = (int) ((cx + rad * Math.cos(a) * 0.9) / 3) * 3;
                    int py = (int) ((cy - rad * sa * 1.1) / 3) * 3;
                    g.setColor(py >= litY ? Retro.GREEN : Retro.GREEN_DIM);
                    g.fillRect(px, py, 2, 2);
                }
            }
            if (scan > 0) {
                g.setColor(Retro.WHITE);
                g.fillRect(s.x, (int) litY, s.width, 2);
            }
            g.setClip(oldClip);
            g.setColor(holding ? Retro.GREEN : Retro.GREEN_MID);
            g.drawRect(s.x, s.y, s.width - 1, s.height - 1);
            g.drawRect(s.x + 1, s.y + 1, s.width - 3, s.height - 3);

            g.setFont(Retro.font(11f));
            g.setColor(Retro.GREEN_MID);
            g.drawString("HOLD [SPACE] OR PRESS AND", 210, 184);
            g.drawString("HOLD THE SENSOR PAD.", 210, 200);
            int lit = (int) Math.round(scan * 18);
            for (int i = 0; i < 18; i++) {
                g.setColor(i < lit ? Retro.GREEN : Retro.GREEN_DIM);
                g.fillRect(210 + i * 15, 216, 12, 16);
            }
            g.setFont(Retro.font(12f));
            g.setColor(holding ? Retro.GREEN : Retro.GREEN_MID);
            g.drawString(holding ? "READING... " + (int) (scan * 100) + "%"
                    : ((now / 400) % 2 == 0 ? "AWAITING INPUT_" : "AWAITING INPUT "), 210, 258);
        }

        private void drawGranted(Graphics2D g, long now) {
            g.setFont(Retro.font(12f));
            FontMetrics fm = g.getFontMetrics();
            int remaining = typed;
            for (int i = 0; i < BOOT.length && remaining > 0; i++) {
                int n = Math.min(BOOT[i].length(), remaining);
                remaining -= n;
                String shown = BOOT[i].substring(0, n);
                int y = 162 + i * 22;
                g.setColor(i == BOOT.length - 1 ? Retro.WHITE : Retro.GREEN);
                g.drawString(shown, 24, y);
                if (remaining <= 0 && (now / 200) % 2 == 0)
                    g.fillRect(24 + fm.stringWidth(shown) + 3, y - 11, 8, 13);
            }
            if (typed > BOOT_CHARS && (now / 250) % 2 == 0) {
                String banner = "ACCESS GRANTED";
                Dimension d = Retro.chunkySize(banner, 3);
                Retro.chunkyText(g, banner, (W - d.width) / 2, 270, 3, Retro.WHITE);
            }
        }

        private void drawDenied(Graphics2D g, long now) {
            String t = "ACCESS DENIED";
            Dimension d = Retro.chunkySize(t, 4);
            Retro.chunkyText(g, t, (W - d.width) / 2, 150, 4,
                    (now / 160) % 2 == 0 ? Retro.DANGER : new Color(140, 20, 20));
            g.setFont(Retro.font(13f));
            g.setColor(Retro.DANGER);
            centered(g, "LOCKOUT ENGAGED // INTRUSION LOGGED", 240);
            int left = Math.max(0, (int) Math.ceil((3000 - (now - phaseStart)) / 1000.0));
            centered(g, "TERMINATING SESSION IN " + left, 264);
        }

        private void drawFooter(Graphics2D g) {
            if (phase != Phase.GRANTED && phase != Phase.DENIED && !message.isEmpty()) {
                g.setFont(Retro.font(12f));
                g.setColor(messageColor);
                g.drawString(message, 24, H - 50);
            }
            g.setFont(Retro.font(10f));
            g.setColor(Retro.GREEN_MID);
            g.drawString("STRIKES", 24, H - 22);
            for (int i = 0; i < MAX_STRIKES; i++) {
                g.setColor(i < strikes ? Retro.DANGER : Retro.GREEN_DIM);
                g.fillRect(84 + i * 16, H - 32, 12, 12);
            }
            g.setColor(Retro.GREEN_DIM);
            String node = "NODE 7F-0042 // SECURE LINK";
            g.drawString(node, W - 24 - g.getFontMetrics().stringWidth(node), H - 22);
        }
    }
}