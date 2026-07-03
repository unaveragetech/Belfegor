package adris.belfegor.tasks.development;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasksystem.ITaskUsesContainer;
import adris.belfegor.tasksystem.ITaskSuppressesMobDefense;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.StorageHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.screen.BlastFurnaceScreenHandler;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Developer screen-handler audit.
 *
 * Runs a real ordered screen test in a cheat-enabled test world:
 * - opens player inventory client-side and verifies it is not mistaken for an
 *   external handled container;
 * - creates fixture blocks with /setblock;
 * - opens each fixture through Belfegor's normal block interaction task;
 * - verifies the expected Minecraft ScreenHandler class;
 * - closes the screen and moves to the next fixture.
 */
public class ScreenAuditTask extends Task implements ITaskUsesContainer, ITaskSuppressesMobDefense {

    private static final int COMMAND_COOLDOWN_TICKS = 8;
    private static final int OPEN_TIMEOUT_TICKS = 20 * 8;
    private static final int CLOSE_TIMEOUT_TICKS = 20 * 3;

    private enum Phase {
        INIT,
        SETUP,
        OPEN,
        VERIFY,
        CLOSE,
        NEXT,
        DONE
    }

    private record ScreenCheck(String name,
                               String fixtureBlockId,
                               Class<? extends ScreenHandler> handlerClass,
                               boolean playerInventory) {}

    private final List<ScreenCheck> _checks = List.of(
            new ScreenCheck("player_inventory", null, null, true),
            new ScreenCheck("crafting_table", "minecraft:crafting_table", CraftingScreenHandler.class, false),
            new ScreenCheck("chest", "minecraft:chest", GenericContainerScreenHandler.class, false),
            new ScreenCheck("barrel", "minecraft:barrel", GenericContainerScreenHandler.class, false),
            new ScreenCheck("shulker_box", "minecraft:shulker_box", ShulkerBoxScreenHandler.class, false),
            new ScreenCheck("furnace", "minecraft:furnace", FurnaceScreenHandler.class, false),
            new ScreenCheck("smoker", "minecraft:smoker", SmokerScreenHandler.class, false),
            new ScreenCheck("blast_furnace", "minecraft:blast_furnace", BlastFurnaceScreenHandler.class, false),
            new ScreenCheck("brewing_stand", "minecraft:brewing_stand", BrewingStandScreenHandler.class, false)
    );

    private Phase _phase = Phase.INIT;
    private int _index = 0;
    private int _ticks = 0;
    private int _cooldownTicks = 0;
    private int _passed = 0;
    private int _failed = 0;
    private BlockPos _fixturePos;
    private Task _openTask;
    private File _logFile;

    @Override
    protected void onStart(Belfegor mod) {
        _phase = Phase.INIT;
        _index = 0;
        _ticks = 0;
        _cooldownTicks = 0;
        _passed = 0;
        _failed = 0;
        _fixturePos = null;
        _openTask = null;
        _logFile = createLogFile();
        writeLog("START screen audit at=" + Instant.now());
        mod.log("Screen audit started. Requires cheats/op because it uses /setblock fixtures.");
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (_cooldownTicks > 0) {
            _cooldownTicks--;
            return null;
        }

        switch (_phase) {
            case INIT -> {
                if (mod.getPlayer() == null) {
                    finish(mod);
                    return null;
                }
                BlockPos playerPos = mod.getPlayer().getBlockPos();
                _fixturePos = playerPos.add(2, 0, 0);
                _phase = Phase.SETUP;
                return null;
            }
            case SETUP -> {
                if (_index >= _checks.size()) {
                    finish(mod);
                    return null;
                }
                ScreenCheck check = _checks.get(_index);
                _ticks = 0;
                _openTask = null;
                StorageHelper.closeScreen();
                if (check.playerInventory()) {
                    if (mod.getPlayer() != null) {
                        MinecraftClient.getInstance().setScreen(new InventoryScreen(mod.getPlayer()));
                    }
                    _phase = Phase.VERIFY;
                    _cooldownTicks = COMMAND_COOLDOWN_TICKS;
                    return null;
                }
                sendCommand(mod, "setblock " + pos(_fixturePos.up()) + " minecraft:air", "SETUP");
                sendCommand(mod, "setblock " + pos(_fixturePos.up(2)) + " minecraft:air", "SETUP");
                prepareDryFixturePad(mod);
                sendCommand(mod, "setblock " + pos(_fixturePos) + " " + check.fixtureBlockId(), "SETUP");
                _phase = Phase.OPEN;
                _cooldownTicks = COMMAND_COOLDOWN_TICKS;
                return null;
            }
            case OPEN -> {
                ScreenCheck check = _checks.get(_index);
                if (handlerMatches(mod, check)) {
                    _phase = Phase.VERIFY;
                    return null;
                }
                if (++_ticks > OPEN_TIMEOUT_TICKS) {
                    fail(check, "open timeout current=" + describeCurrentScreen(mod));
                    _phase = Phase.CLOSE;
                    _ticks = 0;
                    return null;
                }
                if (_openTask == null || _openTask.stopped() || _openTask.isFinished(mod)) {
                    _openTask = new InteractWithBlockTask(_fixturePos);
                }
                setDebugState("Screen audit opening " + check.name() + " " + (_index + 1) + "/" + _checks.size());
                return _openTask;
            }
            case VERIFY -> {
                ScreenCheck check = _checks.get(_index);
                if (check.playerInventory()) {
                    boolean inventoryOpen = StorageHelper.isPlayerInventoryOpen();
                    boolean handledContainerOpen = StorageHelper.isHandledContainerOpen();
                    if (inventoryOpen && !handledContainerOpen) {
                        pass(check, "player inventory recognized without external-container false positive");
                    } else {
                        fail(check, "inventoryOpen=" + inventoryOpen
                                + " handledContainerOpen=" + handledContainerOpen
                                + " current=" + describeCurrentScreen(mod));
                    }
                } else if (handlerMatches(mod, check) && StorageHelper.isHandledContainerOpen()) {
                    pass(check, "opened expected handler " + check.handlerClass().getSimpleName());
                } else {
                    fail(check, "unexpected handler current=" + describeCurrentScreen(mod)
                            + " handledContainerOpen=" + StorageHelper.isHandledContainerOpen());
                }
                StorageHelper.closeScreen();
                _phase = Phase.CLOSE;
                _ticks = 0;
                return null;
            }
            case CLOSE -> {
                if (MinecraftClient.getInstance().currentScreen == null) {
                    _phase = Phase.NEXT;
                    return null;
                }
                if (++_ticks > CLOSE_TIMEOUT_TICKS) {
                    fail(_checks.get(_index), "close timeout current=" + describeCurrentScreen(mod));
                    MinecraftClient.getInstance().setScreen(null);
                    _phase = Phase.NEXT;
                    return null;
                }
                StorageHelper.closeScreen();
                return null;
            }
            case NEXT -> {
                _index++;
                _phase = Phase.SETUP;
                _cooldownTicks = COMMAND_COOLDOWN_TICKS;
                return null;
            }
            case DONE -> {
                return null;
            }
        }
        return null;
    }

    private boolean handlerMatches(Belfegor mod, ScreenCheck check) {
        if (check.playerInventory()) {
            return StorageHelper.isPlayerInventoryOpen();
        }
        ScreenHandler handler = mod.getPlayer() == null ? null : mod.getPlayer().currentScreenHandler;
        return handler != null && check.handlerClass().isInstance(handler);
    }

    private void pass(ScreenCheck check, String reason) {
        _passed++;
        writeLog("PASS screen=" + check.name() + " reason=" + reason);
    }

    private void fail(ScreenCheck check, String reason) {
        _failed++;
        writeLog("FAIL screen=" + check.name() + " reason=" + reason);
    }

    private void finish(Belfegor mod) {
        writeLog("DONE passed=" + _passed + " failed=" + _failed + " checks=" + _checks.size());
        mod.log("Screen audit complete. Passed=" + _passed + " failed=" + _failed
                + " log=" + (_logFile == null ? "unavailable" : _logFile.getPath()));
        _phase = Phase.DONE;
    }

    private String describeCurrentScreen(Belfegor mod) {
        String currentScreenName = MinecraftClient.getInstance().currentScreen == null
                ? "none"
                : MinecraftClient.getInstance().currentScreen.getClass().getName();
        ScreenHandler currentHandler = mod.getPlayer() == null ? null : mod.getPlayer().currentScreenHandler;
        String currentHandlerName = currentHandler == null ? "none" : currentHandler.getClass().getName();
        return "screen=" + currentScreenName + " handler=" + currentHandlerName;
    }

    private String pos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private void prepareDryFixturePad(Belfegor mod) {
        if (mod.getPlayer() == null) return;
        BlockPos standPos = _fixturePos.add(-2, 0, 0);
        BlockPos minAir = standPos.add(-1, 0, -2);
        BlockPos maxAir = standPos.add(4, 3, 2);
        BlockPos minFloor = standPos.add(-1, -1, -2);
        BlockPos maxFloor = standPos.add(4, -1, 2);
        sendCommand(mod, "fill " + pos(minAir) + " " + pos(maxAir) + " minecraft:air", "SETUP");
        sendCommand(mod, "fill " + pos(minFloor) + " " + pos(maxFloor) + " minecraft:cobblestone", "SETUP");
        sendCommand(mod, "tp @s " + standPos.getX() + " " + standPos.getY() + " " + standPos.getZ(), "SETUP");
        writeLog("SETUP dry fixture pad stand=" + pos(standPos) + " fixture=" + pos(_fixturePos));
    }

    private void sendCommand(Belfegor mod, String command, String label) {
        if (mod.getPlayer() == null || mod.getPlayer().networkHandler == null) return;
        writeLog(label + " /" + command);
        mod.getPlayer().networkHandler.sendChatCommand(command);
    }

    private File createLogFile() {
        try {
            File dir = new File("belfegor");
            dir.mkdirs();
            return new File(dir, "screen_audit_" + System.currentTimeMillis() + ".log");
        } catch (Exception e) {
            return null;
        }
    }

    private void writeLog(String line) {
        if (_logFile == null) return;
        try (FileWriter writer = new FileWriter(_logFile, true)) {
            writer.write(line);
            writer.write(System.lineSeparator());
        } catch (IOException ignored) {
        }
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        writeLog("STOP interrupt=" + (interruptTask == null ? "null" : interruptTask.toString())
                + " passed=" + _passed + " failed=" + _failed);
        StorageHelper.closeScreen();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ScreenAuditTask;
    }

    @Override
    protected String toDebugString() {
        return "Screen audit " + Math.min(_index + 1, _checks.size()) + "/" + _checks.size()
                + " phase=" + _phase;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
