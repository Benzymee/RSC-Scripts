package scripting.idlescript.BensErnestTwoHander;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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
import javax.swing.WindowConstants;

/** Setup window of Ben's Ernest -> 2H Goblins (remembered by the client's setup memory). */
final class SetupWindow {
  interface Listener {
    void start(String style, String area, boolean skipQuest, int stopLevel);
  }

  private SetupWindow() {}

  static void open(
      String style, String area, boolean skipQuest, int stopLevel, Listener ok, Runnable cancel) {
    JFrame f = new JFrame(BensErnestTwoHander.DISPLAY_NAME);
    f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    final boolean[] started = {false};

    JComboBox<String> styleBox = new JComboBox<>(BensErnestTwoHander.STYLE_LABELS);
    for (int i = 0; i < BensErnestTwoHander.STYLES.length; i++)
      if (BensErnestTwoHander.STYLES[i].equals(style)) styleBox.setSelectedIndex(i);
    JComboBox<String> areaBox = new JComboBox<>(BensErnestTwoHander.AREA_LABELS);
    for (int i = 0; i < BensErnestTwoHander.AREA_KEYS.length; i++)
      if (BensErnestTwoHander.AREA_KEYS[i].equals(area)) areaBox.setSelectedIndex(i);
    JCheckBox skip = new JCheckBox("Skip Ernest the Chicken (go straight to the sword)", skipQuest);
    JSpinner stop = new JSpinner(new SpinnerNumberModel(stopLevel, 0, 99, 1));

    JLabel title = new JLabel(BensErnestTwoHander.DISPLAY_NAME);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
    title.setForeground(new Color(0xB8860B));
    JLabel info =
        new JLabel(
            "<html><div style='width:330px'>Ernest the Chicken (keeps the 300 coins) &rarr; Gaius, Taverley: Iron 2-handed"
                + " Sword &rarr; bank the rest at Falador West &rarr; Lumbridge goblins.<br>"
                + "After a death the sword is re-wielded and it walks back.</div></html>");

    JPanel form = new JPanel(new GridBagLayout());
    form.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
    GridBagConstraints g = new GridBagConstraints();
    g.insets = new Insets(4, 4, 4, 4);
    g.anchor = GridBagConstraints.WEST;
    g.fill = GridBagConstraints.HORIZONTAL;
    g.gridx = 0;
    g.gridy = 0;
    g.gridwidth = 2;
    form.add(title, g);
    g.gridy++;
    form.add(info, g);
    g.gridwidth = 1;
    g.gridy++;
    form.add(new JLabel("Combat style"), g);
    g.gridx = 1;
    form.add(styleBox, g);
    g.gridx = 0;
    g.gridy++;
    form.add(new JLabel("Goblin area"), g);
    g.gridx = 1;
    form.add(areaBox, g);
    g.gridx = 0;
    g.gridy++;
    g.gridwidth = 2;
    form.add(skip, g);
    g.gridwidth = 1;
    g.gridy++;
    form.add(new JLabel("Stop at level (Att/Str/Def, 0 = never)"), g);
    g.gridx = 1;
    form.add(stop, g);

    JButton start = new JButton("Start");
    JButton cancelButton = new JButton("Cancel");
    JPanel buttons = new JPanel();
    buttons.add(start);
    buttons.add(cancelButton);

    start.addActionListener(
        e -> {
          started[0] = true;
          ok.start(
              BensErnestTwoHander.STYLES[Math.max(0, styleBox.getSelectedIndex())],
              BensErnestTwoHander.AREA_KEYS[Math.max(0, areaBox.getSelectedIndex())],
              skip.isSelected(),
              (Integer) stop.getValue());
          f.dispose();
        });
    cancelButton.addActionListener(e -> f.dispose());
    f.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent e) {
            if (!started[0]) cancel.run();
          }
        });

    f.getContentPane().setLayout(new BorderLayout());
    f.getContentPane().add(form, BorderLayout.CENTER);
    f.getContentPane().add(buttons, BorderLayout.SOUTH);
    f.getRootPane().setDefaultButton(start);
    f.pack();
    f.setLocationRelativeTo(null);
    f.setVisible(true);
  }
}
