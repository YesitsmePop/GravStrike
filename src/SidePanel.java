import javax.swing.*;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;

public class SidePanel extends JPanel {
    static final int HANDLE_W = 26;
    static final int EXPANDED_W = 300;

    private static final double MIN_MASS = 1, MAX_MASS = 2000;
    private boolean opening;

    private final World world;
    private final Placement placement;

    private final JPanel content = new JPanel(new BorderLayout());
    private final ScrollList list = new ScrollList();
    private final JScrollPane scroll = new JScrollPane(list,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private JPanel footer;
    private int shownCount;

    private double progress = 0;      // 0 = collapsed, 1 = expanded
    private boolean holdOpen = false; // true while a slider is being dragged

    private static final int NAME_MAX = 10;
    private static final Color[] PALETTE = {
            new Color(57, 255, 20),  new Color(170, 255, 60), new Color(60, 210, 120),
            new Color(120, 255, 220), new Color(80, 150, 255), new Color(190, 140, 255),
            new Color(255, 130, 200), new Color(255, 70, 70),  new Color(255, 170, 90),
            new Color(255, 230, 90),  new Color(235, 255, 238)
    };
    private Body paletteFor;   // the body whose palette strip is open

    public SidePanel(World world, Placement placement) {
        this.world = world;
        this.placement = placement;

        setOpaque(false);
        setLayout(null);

        // swallow mouse events so clicks on the drawer never reach the scene underneath
        MouseAdapter swallow = new MouseAdapter() { };
        addMouseListener(swallow);
        addMouseMotionListener(swallow);
        addMouseWheelListener(swallow);

        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(20, 8, 16, 16));

        Retro.Label title = new Retro.Label("> BODIES", Retro.GREEN_MID, 11f);
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        content.add(title, BorderLayout.NORTH);

        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        scroll.setBorder(null);
        JScrollBar bar = scroll.getVerticalScrollBar();
        bar.setUI(new Retro.ThinScrollBarUI());
        bar.setPreferredSize(new Dimension(6, 0));
        bar.setOpaque(false);
        bar.setUnitIncrement(16);
        content.add(scroll, BorderLayout.CENTER);

        add(content);
        content.setVisible(false);

        placement.addListener(this::rebuild);
        rebuild();

        new Timer(16, e -> tick()).start();
    }

    // ---- content ----

    /** Rebuilds the body list and the footer (add button, or the new-planet controls). */
    public void rebuild() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        int keep = bar.getValue();
        boolean grew = world.bodies.size() > shownCount;
        shownCount = world.bodies.size();

        list.removeAll();
        for (Body b : world.bodies) list.add(makeRow(b, true));

        if (footer != null) content.remove(footer);
        footer = buildFooter();
        content.add(footer, BorderLayout.SOUTH);

        content.revalidate();
        content.repaint();
        SwingUtilities.invokeLater(() -> bar.setValue(grew ? bar.getMaximum() : keep));
    }

    private JPanel buildFooter() {
        JPanel f = new JPanel();
        f.setOpaque(false);
        f.setLayout(new BoxLayout(f, BoxLayout.Y_AXIS));
        f.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        if (!placement.isActive()) {
            f.add(new Retro.Button("+ ADD PLANET", Retro.GREEN, 12f, 8, 12)
                    .onClick(placement::start));
            return f;
        }

        Retro.Label step = new Retro.Label("NEW PLANET :: STEP " + placement.step() + "/3", Retro.GREEN, 11f);
        step.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        f.add(step);

        f.add(makeRow(placement.pending, false));

        f.add(new Retro.Check("START IN ORBIT", placement.orbit, v -> placement.orbit = v));

        Retro.Label hint = new Retro.Label(placement.hint().toUpperCase(), Retro.GREEN_MID, 11f);
        hint.setBorder(BorderFactory.createEmptyBorder(6, 0, 10, 0));
        f.add(hint);

        f.add(new Retro.Button("CANCEL [ESC]", Retro.DANGER, 11f, 6, 12)
                .onClick(placement::cancel));
        return f;
    }

    private JPanel makeRow(Body b, boolean removable) {
        // color swatch: click to open the palette
        Swatch swatch = new Swatch(b.color);
        swatch.setToolTipText("Change color");
        swatch.onClick(() -> {
            paletteFor = (paletteFor == b) ? null : b;
            Sfx.play("click");
            rebuild();
        });

        // name: click to rename
        JPanel nameBox = new JPanel(new BorderLayout());
        nameBox.setOpaque(false);
        Retro.Label name = new Retro.Label(b.name.toUpperCase(), Retro.GREEN, 12f);
        name.setToolTipText("Click to rename");
        name.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        name.addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) {
                if (name.contains(e.getPoint())) startRename(b, nameBox, name);
            }
        });
        nameBox.add(name, BorderLayout.CENTER);

        JPanel left = new JPanel(new BorderLayout(8, 0));
        left.setOpaque(false);
        left.add(swatch, BorderLayout.WEST);
        left.add(nameBox, BorderLayout.CENTER);

        Retro.Label value = new Retro.Label(fmt(b.mass), Retro.GREEN_MID, 12f).right().minWidth(44);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(value);
        if (removable) {
            Retro.Button remove = new Retro.Button("X", Retro.DANGER, 11f, 1, 6).onClick(() -> {
                Sfx.play("remove");
                world.bodies.remove(b);
                rebuild();
            });
            remove.setToolTipText("Remove " + b.name);
            right.add(remove);
        }

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        if (paletteFor == b) {
            top.add(new Palette(b.color,
                    picked -> {
                        b.color = picked;
                        paletteFor = null;
                        Sfx.play("click");
                        rebuild();
                    },
                    () -> {
                        Color chosen = JColorChooser.showDialog(
                                SwingUtilities.getWindowAncestor(this), "BODY COLOR", b.color);
                        if (chosen != null) b.color = chosen;
                        paletteFor = null;
                        rebuild();
                    }), BorderLayout.CENTER);
        }

        JSlider slider = new JSlider(0, 1000, massToSlider(b.mass));
        slider.setOpaque(false);
        slider.setFocusable(false);
        slider.setUI(new Retro.BlockSliderUI(slider, b.color));
        slider.addChangeListener(e -> {
            b.setMass(sliderToMass(slider.getValue()));
            value.setText(fmt(b.mass));
            Sfx.play("tick");
        });
        slider.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { holdOpen = true; }
            @Override public void mouseReleased(MouseEvent e) { holdOpen = false; }
        });

        JPanel row = new JPanel(new BorderLayout(0, 4));
        row.setOpaque(false);
        row.setAlignmentX(0f);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        row.add(top, BorderLayout.NORTH);
        row.add(slider, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    // ---- hover + animation ----

    private void tick() {
        if (!isShowing()) return;
        PointerInfo info = MouseInfo.getPointerInfo();
        if (info == null) return;
        Point p = info.getLocation();
        SwingUtilities.convertPointFromScreen(p, this);
        boolean hover = p.x >= 0 && p.x < getWidth() && p.y >= 0 && p.y < getHeight();

        boolean want = hover || holdOpen;
        if(want != opening) {
            opening = want;
            Sfx.play(want ? "drawer_open" : "drawer_close");
        }
        double target = want ? 1 : 0;

        double next = progress + Math.signum(target - progress) * 0.09;
        if (Math.abs(target - next) < 0.09) next = target;
        if (next == progress) return;

        progress = next;
        content.setVisible(progress > 0.02);
        Container parent = getParent();
        if (parent != null) { parent.revalidate(); parent.repaint(); }
    }

    /** Current drawer width, eased with smoothstep. */
    public int currentWidth() {
        double t = progress * progress * (3 - 2 * progress);
        return (int) Math.round(HANDLE_W + (EXPANDED_W - HANDLE_W) * t);
    }

    @Override
    public void doLayout() {
        content.setBounds(HANDLE_W, 0, EXPANDED_W - HANDLE_W, getHeight());
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Retro.crisp((Graphics2D) g.create());

        g2.setColor(Retro.PANEL_BG);
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setColor(Retro.GREEN);
        g2.fillRect(0, 0, 1, getHeight());

        // pixel chevron: points left when collapsed (open me), right when expanded
        boolean pointRight = progress > 0.5;
        int cx = HANDLE_W / 2, cy = getHeight() / 2;
        g2.setColor(Retro.GREEN);
        for (int dy = -3; dy <= 3; dy++) {
            int off = Math.abs(dy) * 2;
            int x = pointRight ? cx + 2 - off : cx - 4 + off;
            g2.fillRect(x, cy + dy * 2 - 1, 2, 2);
        }
        g2.dispose();
    }

    // ---- helpers ----

    private static int massToSlider(double m) {
        return (int) Math.round(1000 * Math.log(m / MIN_MASS) / Math.log(MAX_MASS / MIN_MASS));
    }

    private static double sliderToMass(int v) {
        return MIN_MASS * Math.pow(MAX_MASS / MIN_MASS, v / 1000.0);
    }

    private static String fmt(double m) {
        return m >= 100 ? String.format("%.0f", m) : String.format("%.1f", m);
    }

    /** Scroll view that always matches the viewport width. */
    private static class ScrollList extends JPanel implements Scrollable {
        ScrollList() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return 80; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Swaps the name label for a text field. Enter or clicking away confirms, Esc cancels. */
    private void startRename(Body b, JPanel box, Retro.Label label) {
        FontMetrics fm = label.getFontMetrics(label.getFont());
        RenameField field = new RenameField(b.name, label.getFont());
        field.setPreferredSize(new Dimension(
                Math.min(150, fm.stringWidth("W") * NAME_MAX + 12), label.getPreferredSize().height));

        final boolean[] done = {false};
        Runnable commit = () -> {
            if (done[0]) return;
            done[0] = true;
            String t = field.getText().trim();
            if (!t.isEmpty() && !t.equals(b.name)) {
                b.name = t;
                Sfx.play("accept");
            }
            SwingUtilities.invokeLater(this::rebuild);
        };
        Runnable cancel = () -> {
            if (done[0]) return;
            done[0] = true;
            SwingUtilities.invokeLater(this::rebuild);
        };

        field.addActionListener(e -> commit.run());
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { if (!e.isTemporary()) commit.run(); }
        });
        field.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelEdit");
        field.getActionMap().put("cancelEdit", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { cancel.run(); }
        });

        box.removeAll();
        box.add(field, BorderLayout.CENTER);
        box.revalidate();
        box.repaint();
        SwingUtilities.invokeLater(() -> {
            field.requestFocusInWindow();
            field.selectAll();
        });
    }

    /** Pixel-styled text box: black, green border, no text smoothing, capped and uppercased. */
    private static class RenameField extends JTextField {
        RenameField(String text, Font font) {
            super(new PlainDocument() {
                @Override public void insertString(int off, String s, AttributeSet a) throws BadLocationException {
                    if (s == null) return;
                    int room = NAME_MAX - getLength();
                    if (room <= 0) return;
                    s = s.toUpperCase();
                    super.insertString(off, s.length() > room ? s.substring(0, room) : s, a);
                }
            }, text, 0);
            setFont(font);
            setOpaque(true);
            setBackground(Color.BLACK);
            setForeground(Retro.GREEN);
            setCaretColor(Retro.GREEN);
            setSelectionColor(Retro.GREEN_MID);
            setSelectedTextColor(Color.BLACK);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Retro.GREEN),
                    BorderFactory.createEmptyBorder(0, 3, 0, 3)));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Retro.crisp((Graphics2D) g.create());
            super.paintComponent(g2);
            g2.dispose();
        }
    }

    /** Small clickable color square. */
    private static class Swatch extends JComponent {
        private final Color color;
        private boolean hover;
        private Runnable action = () -> { };

        Swatch(Color color) {
            this.color = color;
            setPreferredSize(new Dimension(14, 14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
                @Override public void mouseReleased(MouseEvent e) {
                    if (contains(e.getPoint())) action.run();
                }
            });
        }

        Swatch onClick(Runnable r) { action = r; return this; }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Retro.crisp((Graphics2D) g.create());
            int x = (getWidth() - 12) / 2, y = (getHeight() - 12) / 2;
            g2.setColor(color);
            g2.fillRect(x + 1, y + 1, 10, 10);
            g2.setColor(hover ? Retro.WHITE : Retro.GREEN_MID);
            g2.drawRect(x, y, 11, 11);
            g2.dispose();
        }
    }

    /** Row of color squares plus a "+" cell for a custom color. */
    private static class Palette extends JComponent {
        private static final int CELL = 14, GAP = 5;
        private final Color current;
        private int hover = -1;

        Palette(Color current, Consumer<Color> onPick, Runnable onCustom) {
            this.current = current;
            setPreferredSize(new Dimension((PALETTE.length + 1) * (CELL + GAP), CELL + 6));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int i = indexAt(e.getX(), e.getY());
                    if (i != hover) { hover = i; repaint(); }
                }
                @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
                @Override public void mouseReleased(MouseEvent e) {
                    int i = indexAt(e.getX(), e.getY());
                    if (i < 0) return;
                    if (i < PALETTE.length) onPick.accept(PALETTE[i]);
                    else onCustom.run();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private int indexAt(int x, int y) {
            int i = x / (CELL + GAP);
            int top = (getHeight() - CELL) / 2;
            if (i > PALETTE.length || x - i * (CELL + GAP) >= CELL || y < top || y >= top + CELL) return -1;
            return i;
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Retro.crisp((Graphics2D) g.create());
            int y = (getHeight() - CELL) / 2;
            for (int i = 0; i <= PALETTE.length; i++) {
                int x = i * (CELL + GAP);
                if (i < PALETTE.length) {
                    g2.setColor(PALETTE[i]);
                    g2.fillRect(x, y, CELL, CELL);
                    boolean selected = PALETTE[i].equals(current);
                    g2.setColor(selected || hover == i ? Retro.WHITE : Retro.GREEN_DIM);
                    g2.drawRect(x, y, CELL - 1, CELL - 1);
                    if (selected) g2.drawRect(x + 1, y + 1, CELL - 3, CELL - 3);
                } else {                                              // "+" = custom color
                    g2.setColor(hover == i ? Retro.WHITE : Retro.GREEN_MID);
                    g2.drawRect(x, y, CELL - 1, CELL - 1);
                    g2.fillRect(x + 3, y + CELL / 2 - 1, CELL - 6, 2);
                    g2.fillRect(x + CELL / 2 - 1, y + 3, 2, CELL - 6);
                }
            }
            g2.dispose();
        }
    }
}