package adris.belfegor.chains;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.mixins.DeathScreenAccessor;
import adris.belfegor.tasksystem.TaskChain;
import adris.belfegor.tasksystem.TaskRunner;
import adris.belfegor.util.time.TimerGame;
import adris.belfegor.util.time.TimerReal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

public class DeathMenuChain extends TaskChain {

    // Sometimes we fuck up, so we might want to retry considering the death screen.
    private final TimerReal _deathRetryTimer = new TimerReal(8);
    private final TimerGame _reconnectTimer = new TimerGame(1);
    private final TimerGame _waitOnDeathScreenBeforeRespawnTimer = new TimerGame(2);
    private ServerInfo _prevServerEntry = null;
    private boolean _reconnecting = false;
    private int _deathCount = 0;
    private Class _prevScreen = null;


    public DeathMenuChain(TaskRunner runner) {
        super(runner);
    }

    private boolean shouldAutoRespawn(Belfegor mod) {
        return mod.getModSettings().isAutoRespawn();
    }

    private boolean shouldAutoReconnect(Belfegor mod) {
        return mod.getModSettings().isAutoReconnect();
    }

    @Override
    protected void onStop(Belfegor mod) {

    }

    @Override
    public void onInterrupt(Belfegor mod, TaskChain other) {

    }

    @Override
    protected void onTick(Belfegor mod) {

    }

    @Override
    public float getPriority(Belfegor mod) {
        //MinecraftClient.getInstance().getCurrentServerEntry().address;
//        MinecraftClient.getInstance().
        Screen screen = MinecraftClient.getInstance().currentScreen;
        // This might fix Weird fail to respawn that happened only once
        if (_prevScreen == DeathScreen.class) {
            if (_deathRetryTimer.elapsed()) {
                Debug.logMessage("(RESPAWN RETRY WEIRD FIX...)");
                _deathRetryTimer.reset();
                _prevScreen = null;
            }
        } else {
            _deathRetryTimer.reset();
        }
        // Keep track of the last server we were on so we can re-connect.
        if (Belfegor.inGame()) {
            _prevServerEntry = MinecraftClient.getInstance().getCurrentServerEntry();
        }

        if (screen instanceof DeathScreen) {
            if (_waitOnDeathScreenBeforeRespawnTimer.elapsed()) {
                _waitOnDeathScreenBeforeRespawnTimer.reset();
                if (shouldAutoRespawn(mod)) {
                    _deathCount++;
                    Debug.logMessage("RESPAWNING... (this is death #" + _deathCount + ")");
                    if (MinecraftClient.getInstance().player == null) return Float.NEGATIVE_INFINITY;
                    Text screenMessage = ((DeathScreenAccessor) screen).getMessage();
                    String deathMessage = screenMessage != null ? screenMessage.getString() : "Unknown"; //"(not implemented yet)"; //screen.children().toString();
                    MinecraftClient.getInstance().player.requestRespawn();
                    MinecraftClient.getInstance().setScreen(null);
                    for (String i : mod.getModSettings().getDeathCommand().split(" & ")) {
                        String command = i.replace("{deathmessage}", deathMessage);
                        String prefix = mod.getModSettings().getCommandPrefix();
                        while (MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().player.isAlive()) ;
                        if (MinecraftClient.getInstance().player == null) break;
                        if (!command.isEmpty()) {
                            if (command.startsWith(prefix)) {
                                Belfegor.getCommandExecutor().execute(command, () -> {
                                }, Throwable::printStackTrace);
                            } else if (command.startsWith("/")) {
                                MinecraftClient.getInstance().player.networkHandler.sendChatCommand(command.substring(1));
                            } else {
                                MinecraftClient.getInstance().player.networkHandler.sendChatMessage(command);
                            }
                        }
                    }
                } else {
                    // Cancel if we die and are not auto-respawning.
                    mod.cancelUserTask();
                }
            }
        } else {
            if (Belfegor.inGame()) {
                _waitOnDeathScreenBeforeRespawnTimer.reset();
            }
            if (screen instanceof DisconnectedScreen) {
                if (shouldAutoReconnect(mod)) {
                    Debug.logMessage("RECONNECTING: Going to Multiplayer Screen");
                    _reconnecting = true;
                    MinecraftClient.getInstance().setScreen(new MultiplayerScreen(new TitleScreen()));
                } else {
                    // Cancel if we disconnect and are not auto-reconnecting.
                    mod.cancelUserTask();
                }
            } else if (screen instanceof MultiplayerScreen && _reconnecting && _reconnectTimer.elapsed()) {
                _reconnectTimer.reset();
                Debug.logMessage("RECONNECTING: Going ");
                _reconnecting = false;

                if (_prevServerEntry == null) {
                    Debug.logWarning("Failed to re-connect to server, no server entry cached.");
                } else {
                    MinecraftClient client = MinecraftClient.getInstance();
                    ConnectScreen.connect(screen, client, ServerAddress.parse(_prevServerEntry.address), _prevServerEntry, false, null);
                    //ConnectScreen.connect(screen, client, ServerAddress.parse(_prevServerEntry.address), _prevServerEntry);
                    //client.setScreen(new ConnectScreen(screen, client, _prevServerEntry));
                }
            }
        }
        if (screen != null)
            _prevScreen = screen.getClass();
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getName() {
        return "Death Menu Respawn Handling";
    }
}
