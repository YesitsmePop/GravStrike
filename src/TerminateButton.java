import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Big red pixel button with a hard drop shadow, bevel, and a flashing hover state. */
public class TerminateButton extends JComponent {
    private boolean hover, pressed;
    private Runnable action = () -> { };

    public TerminateButton() {
        setPreferredSize(new Dimension(250, 64));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover = true; Sfx.play("hover"); repaint(); }
            @Override public void mouseExited(MouseEvent e)  { hover = false; pressed = false; repaint(); }
            @Override public void mousePressed(MouseEvent e) { pressed = true; repaint(); }
            @Override public void mouseReleased(MouseEvent e) {
                boolean fire = pressed && contains(e.getPoint());
                pressed = false;
                repaint();
                if (fire) action.run();
            }
        });
        new Timer(100, e -> repaint()).start();   // drives the idle pulse and hover flash
    }

    public TerminateButton onClick(Runnable r) { action = r; return this; }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = Retro.crisp((Graphics2D) g0.create());
        int w = getWidth() - 4, h = getHeight() - 4;
        int o = pressed ? 3 : 0;
        long now = System.currentTimeMillis();
        boolean flash = hover && (now / 110) % 2 == 0;
        boolean pulse = !hover && (now / 600) % 2 == 0;

        if (!pressed) {                                   // hard drop shadow
            g.setColor(new Color(70, 0, 0));
            g.fillRect(4, 4, w, h);
        }
        g.setColor(flash ? new Color(255, 110, 110)
                : pulse ? new Color(225, 20, 20) : new Color(255, 36, 36));
        g.fillRect(o, o, w, h);

        g.setColor(new Color(255, 150, 150));             // bevel: light top-left
        g.fillRect(o, o, w, 3);
        g.fillRect(o, o, 3, h);
        g.setColor(new Color(120, 0, 0));                 // bevel: dark bottom-right
        g.fillRect(o, o + h - 3, w, 3);
        g.fillRect(o + w - 3, o, 3, h);

        String label = "TERMINATE";
        Dimension d = Retro.chunkySize(label, 3);
        Retro.chunkyText(g, label, o + (w - d.width) / 2, o + (h - d.height) / 2, 3,
                hover ? Color.WHITE : Color.BLACK);
        g.dispose();
    }
}