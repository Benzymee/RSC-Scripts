package scripting.idlescript.goblin;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * Start window for Benzyme's Goblin Killer. It never blocks a tab forever: closing it, or letting
 * the countdown run out, starts with the settings shown.
 */
public final class GoblinGui {
  private static final int AUTO_START_SECONDS = 30;
  private static final String[] STYLES = {"controlled", "strength", "attack", "defence"};
  private static final String[] STYLE_LABELS = {
    "Controlled", "Strength (Aggressive)", "Attack (Accurate)", "Defence (Defensive)"
  };

  /** Receives the chosen settings once. */
  public interface Listener {
    void start(boolean buryBones, String style, int eatPercent);
  }

  private GoblinGui() {}

  public static void open(boolean bury, String style, int eatPercent, Listener listener) {
    JFrame frame = new JFrame("Benzyme's Goblin Killer");
    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

    JCheckBox buryBox = new JCheckBox("Pick up and bury bones", bury);
    JComboBox<String> styleBox = new JComboBox<>(STYLE_LABELS);
    int selected = 0;
    for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(style)) selected = i;
    styleBox.setSelectedIndex(selected);
    JSpinner eat = new JSpinner(new SpinnerNumberModel(eatPercent, 1, 99, 5));
    JLabel countdown = new JLabel(" ");
    JButton startButton = new JButton("Start");

    JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
    form.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
    form.add(new JLabel("Bones"));
    form.add(buryBox);
    form.add(new JLabel("Combat style"));
    form.add(styleBox);
    form.add(new JLabel("Eat at HP %"));
    form.add(eat);

    JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    bottom.add(countdown);
    bottom.add(startButton);

    JLabel info =
        new JLabel(
            "<html>Kills goblins at the Lumbridge goblin hut.<br>"
                + "Parameters skip this window: bury / nobury, style=..., eat=50, nogui</html>");
    info.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));

    frame.getContentPane().setLayout(new BorderLayout());
    frame.getContentPane().add(info, BorderLayout.NORTH);
    frame.getContentPane().add(form, BorderLayout.CENTER);
    frame.getContentPane().add(bottom, BorderLayout.SOUTH);

    final boolean[] done = {false};
    final int[] left = {AUTO_START_SECONDS};
    final Timer timer = new Timer(1000, null);
    Runnable finish =
        () -> {
          if (done[0]) return;
          done[0] = true;
          timer.stop();
          listener.start(
              buryBox.isSelected(),
              STYLES[Math.max(0, styleBox.getSelectedIndex())],
              ((Number) eat.getValue()).intValue());
          frame.dispose();
        };
    timer.addActionListener(
        e -> {
          left[0]--;
          if (left[0] <= 0) finish.run();
          else countdown.setText("Auto-start in " + left[0] + "s");
        });
    // Any interaction cancels the countdown.
    buryBox.addActionListener(e -> stopCountdown(timer, countdown));
    styleBox.addActionListener(e -> stopCountdown(timer, countdown));
    eat.addChangeListener(e -> stopCountdown(timer, countdown));
    startButton.addActionListener(e -> finish.run());
    frame.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent e) {
            finish.run();
          }
        });

    countdown.setText("Auto-start in " + AUTO_START_SECONDS + "s");
    frame.pack();
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);
    timer.start();
  }

  private static void stopCountdown(Timer timer, JLabel label) {
    if (timer.isRunning()) {
      timer.stop();
      label.setText(" ");
    }
  }
}
