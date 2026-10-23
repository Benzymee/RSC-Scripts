import bot.Main;
import controller.Controller;
import scripting.idlescript.IdleScript;

/** Example external script: copy this file up into the scripts folder and reload. */
public class ExampleScript extends IdleScript {
  @Override
  public int start(String[] params) {
    Controller controller = Main.getController(); // or IdleScript's own 'controller' field
    controller.displayMessage("@gre@ExampleScript started");
    while (controller.isRunning()) {
      controller.setStatus("@cya@Example script running");
      controller.sleep(1000);
    }
    return 1000;
  }
}
