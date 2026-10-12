import javax.swing.*;
import java.awt.Dimension;

public class OverlayPane extends JLayeredPane {
    private final JComponent base, button, strike;
    private final SidePanel side;

    public OverlayPane(JComponent base, SidePanel side, JComponent button, JComponent strike) {
        this.base = base;
        this.side = side;
        this.button = button;
        this.strike = strike;
        add(base, JLayeredPane.DEFAULT_LAYER);
        add(button, JLayeredPane.PALETTE_LAYER);
        add(side, JLayeredPane.MODAL_LAYER);
        add(strike, JLayeredPane.DRAG_LAYER);
    }

    @Override
    public void doLayout() {
        base.setBounds(0, 0, getWidth(), getHeight());
        strike.setBounds(0, 0, getWidth(), getHeight());
        int sw = side.currentWidth();
        side.setBounds(getWidth() - sw, 0, sw, getHeight());

        Dimension d = button.getPreferredSize();
        button.setBounds(getWidth() - sw - 18 - d.width, 18, d.width, d.height);
    }
}