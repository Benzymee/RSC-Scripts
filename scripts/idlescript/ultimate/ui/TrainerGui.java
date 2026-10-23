package scripting.idlescript.ultimate.ui;

import bot.Main;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;
import models.entities.ItemId;
import scripting.idlescript.ultimate.Ids;
import scripting.idlescript.ultimate.TrainerConfig;

/** Settings window. Closing it starts the script with whatever is on screen. */
public final class TrainerGui {
  private TrainerGui() {}

  public static void open(final TrainerConfig config, final Runnable onStart) {
    final JFrame frame = new JFrame("Benzyme's AIO Trainer");
    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    final AtomicBoolean started = new AtomicBoolean(false);
    final Runnable finish =
        new Runnable() {
          public void run() {
            if (started.compareAndSet(false, true)) onStart.run();
          }
        };
    JPanel rows = new JPanel();
    rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
    final JCheckBox weighted = new JCheckBox("Weighted rotation (otherwise the list runs in order)", config.weighted);
    rows.add(weighted);
    rows.add(label("Each row is one timed activity. Weight is only used in weighted mode."));
    for (TrainerConfig.ActivitySetting setting : config.activities) {
      rows.add(row(setting));
    }
    JPanel options = new JPanel(new GridLayout(0, 2, 8, 6));
    options.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    final JComboBox<FoodChoice> food = new JComboBox<FoodChoice>(FoodChoice.values());
    for (int i = 0; i < food.getItemCount(); i++) {
      if (food.getItemAt(i).id == config.foodId) food.setSelectedIndex(i);
    }
    final JSpinner eat =
        new JSpinner(new SpinnerNumberModel(clamp(config.eatPercent, 10, 90), 10, 90, 5));
    final JSpinner loot =
        new JSpinner(new SpinnerNumberModel(Math.max(0, config.minLootValue), 0, 100000, 5));
    final JCheckBox bones = new JCheckBox("Loot and bury bones", config.lootBones);
    final JCheckBox hides = new JCheckBox("Loot cowhides", config.lootHides);
    final JSpinner coins =
        new JSpinner(new SpinnerNumberModel(Math.max(0, config.minCoins), 0, 1000000, 50));
    final JComboBox<String> style =
        new JComboBox<String>(new String[] {"Rotate", "Accurate", "Aggressive", "Defensive", "Controlled"});
    style.setSelectedItem(config.meleeStyle);
    final JSpinner variance =
        new JSpinner(new SpinnerNumberModel(Math.max(0, config.variance), 0, 60, 1));
    options.add(new JLabel("Food"));
    options.add(food);
    options.add(new JLabel("Eat below hits %"));
    options.add(eat);
    options.add(new JLabel("Minimum loot value"));
    options.add(loot);
    options.add(bones);
    options.add(hides);
    options.add(new JLabel("Coins to keep"));
    options.add(coins);
    options.add(new JLabel("Melee style"));
    options.add(style);
    options.add(new JLabel("Swap variance (+/- min)"));
    options.add(variance);
    JButton start = new JButton("Start");
    start.addActionListener(
        event -> {
          apply(config, weighted, food, eat, loot, bones, hides, coins, style, variance);
          config.save();
          frame.dispose();
          finish.run();
        });
    frame.addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosed(java.awt.event.WindowEvent event) {
            apply(config, weighted, food, eat, loot, bones, hides, coins, style, variance);
            config.save();
            finish.run();
          }
        });
    JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    south.add(start);
    JPanel root = new JPanel(new BorderLayout());
    root.add(new JScrollPane(rows), BorderLayout.CENTER);
    root.add(options, BorderLayout.SOUTH);
    frame.add(root, BorderLayout.CENTER);
    frame.add(south, BorderLayout.SOUTH);
    frame.pack();
    frame.setSize(new Dimension(720, 640));
    frame.setLocationRelativeTo(null);
    if (Main.primaryBG != null) {
      root.setBackground(Main.primaryBG);
      rows.setBackground(Main.primaryBG);
    }
    frame.setVisible(true);
  }

  private static JPanel row(TrainerConfig.ActivitySetting setting) {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    JCheckBox enabled = new JCheckBox(setting.name, setting.enabled);
    enabled.addActionListener(event -> setting.enabled = enabled.isSelected());
    JSpinner minutes =
        new JSpinner(new SpinnerNumberModel(Math.max(1, setting.minutes), 1, 240, 1));
    minutes.addChangeListener(event -> setting.minutes = ((Number) minutes.getValue()).intValue());
    JSpinner weight = new JSpinner(new SpinnerNumberModel(Math.max(1, setting.weight), 1, 20, 1));
    weight.addChangeListener(event -> setting.weight = ((Number) weight.getValue()).intValue());
    panel.add(enabled);
    panel.add(new JLabel("minutes"));
    panel.add(minutes);
    panel.add(new JLabel("weight"));
    panel.add(weight);
    panel.add(new JLabel(setting.summary));
    return panel;
  }

  private static JLabel label(String text) {
    return new JLabel(text);
  }

  private static void apply(
      TrainerConfig config,
      JCheckBox weighted,
      JComboBox<FoodChoice> food,
      JSpinner eat,
      JSpinner loot,
      JCheckBox bones,
      JCheckBox hides,
      JSpinner coins,
      JComboBox<String> style,
      JSpinner variance) {
    config.weighted = weighted.isSelected();
    config.foodId = ((FoodChoice) food.getSelectedItem()).id;
    config.eatPercent = ((Number) eat.getValue()).intValue();
    config.minLootValue = ((Number) loot.getValue()).intValue();
    config.lootBones = bones.isSelected();
    config.lootHides = hides.isSelected();
    config.minCoins = ((Number) coins.getValue()).intValue();
    config.meleeStyle = String.valueOf(style.getSelectedItem());
    config.variance = Math.max(0, ((Number) variance.getValue()).intValue());
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private enum FoodChoice {
    CABBAGE("Cabbage (picked)", Ids.CABBAGE),
    SHRIMP("Shrimp", ItemId.SHRIMP.getId()),
    MEAT("Cooked meat", Ids.COOKED_MEAT),
    CAKE("Cake", Ids.CAKE);

    final String label;
    final int id;

    FoodChoice(String label, int id) {
      this.label = label;
      this.id = id;
    }

    public String toString() {
      return label;
    }
  }
}
