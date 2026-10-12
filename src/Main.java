import javax.swing.*;
import java.awt.Color;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Retro.install();
            new LoginWindow(Main::launch).setVisible(true);
        });
    }

    private static void launch() {
        World world = new World();
        world.bodies.add(new Body("Z-Star",   0, 0, 0,    0, 0, 0,   500, new Color(225, 255, 170)));
        world.bodies.add(new Body("Coruscant", 150, 0, 0, 0, 0, 58, 4, new Color(100, 125, 145)));
        world.bodies.add(new Body("Tatooine", 0, 200, 0, 50, 0, 0, 5, new Color(201, 154, 85)));
        world.bodies.add(new Body("Hoth", 0, 0, 250, 35, 28, 0, 3, new Color(200, 229, 242)));

        Placement placement = new Placement(world);
        SimulationPanel sim = new SimulationPanel(world, placement);
        SidePanel side = new SidePanel(world, placement);
        TerminateButton terminate = new TerminateButton();
        StrikeView strike = new StrikeView(sim, world, side::rebuild);
        sim.setTargetHandler(strike::begin);

        JFrame frame = new JFrame("GRAV-STRIKE");
        frame.setUndecorated(true);
        ImageIcon icon = new ImageIcon(Main.class.getResource("/gravpic.png"));
        frame.setIconImage(icon.getImage());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(new OverlayPane(sim, side, terminate, strike));
        frame.setSize(900, 700);
        frame.setLocationRelativeTo(null);
        terminate.onClick(() -> TerminateOverlay.run(frame));
        frame.setVisible(true);

        Sfx.startHum();
    }
}