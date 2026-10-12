import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.function.Consumer;
import java.awt.image.BufferedImage;

public final class Retro {
    private Retro() { }

    /** Size of one "screen pixel" in the 3D scene. 2 = finer, 4 = chunkier. */
    public static final int PIXEL = 3;
    public static final boolean SCANLINES = true;

    public static final Color BG        = Color.BLACK;
    public static final Color GREEN     = new Color(57, 255, 20);
    public static final Color GREEN_MID = new Color(30, 150, 30);
    public static final Color GREEN_DIM = new Color(12, 70, 20);
    public static final Color WHITE     = new Color(235, 255, 238);
    public static final Color RETICLE   = new Color(194, 24, 91);
    public static final Color DANGER    = new Color(255, 70, 70);
    public static final Color PANEL_BG  = new Color(0, 0, 0, 238);

    public static final Color AXIS_X = new Color(255, 70, 70);
    public static final Color AXIS_Y = new Color(70, 255, 100);
    public static final Color AXIS_Z = new Color(80, 150, 255);

    private static Font custom;
    private static boolean loaded;

    /** Call once at startup: styles tooltips to match. */
    public static void install() {
        UIManager.put("ToolTip.background", Color.BLACK);
        UIManager.put("ToolTip.foreground", GREEN);
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(GREEN));
        UIManager.put("ToolTip.font", font(11f));
    }

    /**
     * Uses pixel.ttf from the project root if present (e.g. VT323 or Press Start 2P),
     * otherwise bold monospaced.
     */
    public static Font font(float size) {
        if (!loaded) {
            loaded = true;
            try {
                File f = new File("pixel.ttf");
                if (f.exists()) custom = Font.createFont(Font.TRUETYPE_FONT, f);
            } catch (Exception ignored) { }
        }
        if (custom != null) return custom.deriveFont(size * 1.4f);
        return new Font(Font.MONOSPACED, Font.BOLD, Math.round(size));
    }

    /** Turns off every kind of smoothing. */
    public static Graphics2D crisp(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        return g2;
    }

    // ---------------------------------------------------------------- chunky text

    private static FontMetrics probeMetrics() {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = crisp(probe.createGraphics());
        pg.setFont(font(10f));
        FontMetrics fm = pg.getFontMetrics();
        pg.dispose();
        return fm;
    }

    /** Size in pixels that chunkyText will occupy. */
    public static Dimension chunkySize(String s, int scale) {
        FontMetrics fm = probeMetrics();
        return new Dimension(Math.max(1, fm.stringWidth(s)) * scale, fm.getHeight() * scale);
    }

    /** Draws text rendered small and blown up with hard pixel edges. (x, y) is the top-left. */
    public static void chunkyText(Graphics2D g2, String s, int x, int y, int scale, Color c) {
        FontMetrics fm = probeMetrics();
        int w = Math.max(1, fm.stringWidth(s)), h = fm.getHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = crisp(img.createGraphics());
        ig.setFont(font(10f));
        ig.setColor(c);
        ig.drawString(s, 0, fm.getAscent());
        ig.dispose();

        Object old = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(img, x, y, w * scale, h * scale, null);
        if (old != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
    }

    // ---------------------------------------------------------------- widgets

    /** Aliased text label with an optional square color marker. */
    public static class Label extends JComponent {
        private String text;
        private final Color color;
        private Color dot;
        private boolean right;
        private int minWidth;

        public Label(String text, Color color, float size) {
            this.text = text;
            this.color = color;
            setFont(font(size));
            setAlignmentX(0f);
        }

        public Label dot(Color c)     { dot = c; return this; }
        public Label right()          { right = true; return this; }
        public Label minWidth(int w)  { minWidth = w; return this; }
        public void setText(String t) { text = t; repaint(); }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = fm.stringWidth(text) + (dot != null ? 16 : 0);
            return new Dimension(Math.max(w, minWidth), fm.getHeight() + 2);
        }
        @Override public Dimension getMaximumSize() {
            return new Dimension(Short.MAX_VALUE, getPreferredSize().height);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = crisp((Graphics2D) g.create());
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int base = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            int x0 = 0;
            if (dot != null) {
                g2.setColor(dot);
                g2.fillRect(0, (getHeight() - 8) / 2, 8, 8);
                x0 = 16;
            }
            g2.setColor(color);
            g2.drawString(text, right ? getWidth() - fm.stringWidth(text) : x0, base);
            g2.dispose();
        }
    }

    /** Bordered button that inverts on hover. */
    public static class Button extends JComponent {
        private final String text;
        private final Color color;
        private final int padV, padH;
        private boolean hover;
        private Runnable action = () -> { };

        public Button(String text, Color color, float size, int padV, int padH) {
            this.text = text;
            this.color = color;
            this.padV = padV;
            this.padH = padH;
            setFont(font(size));
            setAlignmentX(0f);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; Sfx.play("hover"); repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
                @Override public void mouseReleased(MouseEvent e) {
                    if (contains(e.getPoint())) action.run();
                }
            });
        }

        public Button onClick(Runnable r) { action = r; return this; }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + padH * 2, fm.getHeight() + padV * 2);
        }
        @Override public Dimension getMaximumSize() {
            return new Dimension(Short.MAX_VALUE, getPreferredSize().height);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = crisp((Graphics2D) g.create());
            g2.setFont(getFont());
            g2.setColor(hover ? color : new Color(color.getRed(), color.getGreen(), color.getBlue(), 28));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(color);
            g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g2.setColor(hover ? Color.BLACK : color);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, (getWidth() - fm.stringWidth(text)) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Square checkbox. */
    public static class Check extends JComponent {
        private final String text;
        private boolean selected;

        public Check(String text, boolean selected, Consumer<Boolean> onChange) {
            this.text = text;
            this.selected = selected;
            setFont(font(11f));
            setAlignmentX(0f);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseReleased(MouseEvent e) {
                    if (!contains(e.getPoint())) return;
                    Check.this.selected = !Check.this.selected;
                    Sfx.play("click");
                    onChange.accept(Check.this.selected);
                    repaint();
                }
            });
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(20 + fm.stringWidth(text), fm.getHeight() + 4);
        }
        @Override public Dimension getMaximumSize() {
            return new Dimension(Short.MAX_VALUE, getPreferredSize().height);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = crisp((Graphics2D) g.create());
            g2.setFont(getFont());
            int by = (getHeight() - 11) / 2;
            g2.setColor(selected ? GREEN : GREEN_MID);
            g2.drawRect(0, by, 10, 10);
            if (selected) g2.fillRect(3, by + 3, 5, 5);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, 20, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Segmented block bar with a square thumb. */
    public static class BlockSliderUI extends BasicSliderUI {
        private final Color accent;

        public BlockSliderUI(JSlider s, Color accent) {
            super(s);
            this.accent = accent;
        }

        @Override protected Dimension getThumbSize() { return new Dimension(6, 16); }

        @Override public void paintTrack(Graphics g) {
            Graphics2D g2 = crisp((Graphics2D) g.create());
            int cy = trackRect.y + trackRect.height / 2;
            int mid = thumbRect.x + thumbRect.width / 2;
            for (int x = trackRect.x; x + 4 <= trackRect.x + trackRect.width; x += 6) {
                g2.setColor(x + 4 <= mid ? accent : GREEN_DIM);
                g2.fillRect(x, cy - 3, 4, 6);
            }
            g2.dispose();
        }

        @Override public void paintThumb(Graphics g) {
            g.setColor(WHITE);
            g.fillRect(thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height);
        }

        @Override public void paintFocus(Graphics g) { }
    }

    public static class ThinScrollBarUI extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() { }
        @Override protected JButton createDecreaseButton(int orientation) { return zero(); }
        @Override protected JButton createIncreaseButton(int orientation) { return zero(); }

        private JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            return b;
        }

        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(new Color(12, 70, 20, 90));
            g.fillRect(r.x + 2, r.y, r.width - 4, r.height);
        }

        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            g.setColor(GREEN_MID);
            g.fillRect(r.x + 1, r.y, r.width - 2, r.height);
        }
    }
}