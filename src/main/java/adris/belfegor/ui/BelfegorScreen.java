package adris.belfegor.ui;

import adris.belfegor.Belfegor;
import adris.belfegor.macros.MacroChain;
import adris.belfegor.macros.MacroRunner;
import adris.belfegor.macros.MacroStep;
import adris.belfegor.macros.MacroStorage;
import adris.belfegor.memory.ShulkerMemory;
import adris.belfegor.tasks.container.ShulkerInteractionTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.tasksystem.TaskChain;
import adris.belfegor.tasksystem.TaskRunner;
import adris.belfegor.commandsystem.CommandDocumentation;
import com.google.gson.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class BelfegorScreen extends Screen {
    private final Belfegor mod;
    private int selectedTab = 0;
    private static final String[] TAB_NAMES = {"Tasks", "Macros", "Commands", "Settings", "Shulkers", "Schematics", "Log"};

    // Colors - modern dark theme
    private static final int BG = 0xFF0D1117;
    private static final int BG_LIGHTER = 0xFF161B22;
    private static final int BG_LIGHT = 0xFF21262D;
    private static final int ACCENT = 0xFF58A6FF;
    private static final int SUCCESS = 0xFF3FB950;
    private static final int WARNING = 0xFFD29922;
    private static final int ERROR = 0xFFF85149;
    private static final int TEXT = 0xFFC9D1D9;
    private static final int TEXT_DIM = 0xFF8B949E;
    private static final int TEXT_BRIGHT = 0xFFF0F6FC;
    private static final int BORDER = 0xFF30363D;
    private static final int HIGHLIGHT = 0xFF1F2937;

    // Setting category colors
    private static final int CAT_DISPLAY = 0xFF58A6FF;
    private static final int CAT_COMMANDS = 0xFFD29922;
    private static final int CAT_COMBAT = 0xFFF85149;
    private static final int CAT_PATHFINDING = 0xFFA371F7;
    private static final int CAT_RESOURCE = 0xFF3FB950;
    private static final int CAT_CRAFTING = 0xFF79C0FF;
    private static final int CAT_SURVIVAL = 0xFFFFA657;
    private static final int CAT_MOVEMENT = 0xFFD2A8FF;
    private static final int CAT_ITEMS = 0xFF7EE787;
    private static final int CAT_SMELTING = 0xFFFF7B72;
    private static final int CAT_AUTONOMOUS = 0xFF56D4DD;
    private static final int CAT_LOGGING = 0xFF8B949E;

    private TextFieldWidget commandInput;
    private TextFieldWidget commandSearchInput;
    private TextFieldWidget macroNameInput;
    private TextFieldWidget macroStepInput;
    private TextFieldWidget macroDescInput;
    private TextFieldWidget logFilterInput;
    private TextFieldWidget settingsSearchInput;
    private TextFieldWidget settingsValueInput;
    private int selectedMacroIndex = -1;
    private int selectedMacroStepIndex = -1;
    private int scrollOffset = 0;
    private int settingsScrollOffset = 0;
    private int logScrollOffset = 0;
    private int shulkerScrollOffset = 0;
    private int schematicScrollOffset = 0;
    private int commandScrollOffset = 0;
    private int selectedCommandIndex = 0;
    private final List<CommandExampleHitbox> commandExampleHitboxes = new ArrayList<>();
    private final List<UiButtonHitbox> macroButtonHitboxes = new ArrayList<>();
    private final List<CommandExampleHitbox> schematicCommandHitboxes = new ArrayList<>();
    private String lastClickedCommandExample = "";
    private long lastCommandExampleClickTime = 0;
    private String statusMessage = "";
    private long statusTime = 0;
    private long lastShulkerUiSync = 0;

    // Settings editing state
    private JsonObject settingsJson;
    private String editingKey = null;
    private String editingType = null; // "boolean", "number", "string", "array"
    private int editingArrayIndex = -1; // for array editing

    public final EventLogPanel eventLog = new EventLogPanel();

    public BelfegorScreen(Belfegor mod) {
        super(Text.literal("Belfegor"));
        this.mod = mod;
    }

    @Override
    protected void init() {
        int inputY = this.height - 26;
        commandInput = new TextFieldWidget(textRenderer, 10, inputY, this.width - 80, 20, Text.literal(""));
        commandInput.setPlaceholder(Text.literal("Type command... (e.g. get diamond 3)"));
        commandInput.setMaxLength(256);
        addDrawableChild(commandInput);

        commandSearchInput = new TextFieldWidget(textRenderer, 10, 32, 210, 18, Text.literal(""));
        commandSearchInput.setPlaceholder(Text.literal("Search commands..."));
        commandSearchInput.setVisible(false);
        commandSearchInput.setEditable(false);
        addDrawableChild(commandSearchInput);

        macroNameInput = new TextFieldWidget(textRenderer, 10, 50, 150, 18, Text.literal(""));
        macroNameInput.setPlaceholder(Text.literal("Macro name"));
        macroNameInput.setVisible(false);
        macroNameInput.setEditable(false);
        addDrawableChild(macroNameInput);

        macroStepInput = new TextFieldWidget(textRenderer, 170, 50, this.width - 180, 18, Text.literal(""));
        macroStepInput.setPlaceholder(Text.literal("Command for this step"));
        macroStepInput.setMaxLength(256);
        macroStepInput.setVisible(false);
        macroStepInput.setEditable(false);
        addDrawableChild(macroStepInput);

        macroDescInput = new TextFieldWidget(textRenderer, 10, 72, this.width - 20, 18, Text.literal(""));
        macroDescInput.setPlaceholder(Text.literal("Description"));
        macroDescInput.setVisible(false);
        macroDescInput.setEditable(false);
        addDrawableChild(macroDescInput);

        logFilterInput = new TextFieldWidget(textRenderer, 10, 30, 200, 18, Text.literal(""));
        logFilterInput.setPlaceholder(Text.literal("Filter log..."));
        logFilterInput.setVisible(false);
        logFilterInput.setEditable(false);
        addDrawableChild(logFilterInput);

        settingsSearchInput = new TextFieldWidget(textRenderer, 10, 30, this.width - 20, 18, Text.literal(""));
        settingsSearchInput.setPlaceholder(Text.literal("Search settings..."));
        settingsSearchInput.setVisible(false);
        settingsSearchInput.setEditable(false);
        addDrawableChild(settingsSearchInput);

        settingsValueInput = new TextFieldWidget(textRenderer, 10, 50, this.width - 20, 18, Text.literal(""));
        settingsValueInput.setPlaceholder(Text.literal("Enter value..."));
        settingsValueInput.setMaxLength(1024);
        settingsValueInput.setVisible(false);
        settingsValueInput.setEditable(false);
        addDrawableChild(settingsValueInput);

        loadSettingsJson();
        if (!settingsJson.has("autoShulkerMode")) {
            settingsJson.addProperty("autoShulkerMode", false);
        }
        if (!settingsJson.has("autoShulkerSortMode")) {
            settingsJson.addProperty("autoShulkerSortMode", "detection");
        }
        if (!settingsJson.has("autoShulkerTimerSeconds")) {
            settingsJson.addProperty("autoShulkerTimerSeconds", 60);
        }
        if (!settingsJson.has("autoShulkerInventoryThreshold")) {
            settingsJson.addProperty("autoShulkerInventoryThreshold", 80);
        }
    }

    // ======================== SETTINGS JSON I/O ========================

    private Path getSettingsPath() {
        return Path.of(MinecraftClient.getInstance().runDirectory.getAbsolutePath(), "belfegor", "belfegor_settings.json");
    }

    private void loadSettingsJson() {
        try {
            Path path = getSettingsPath();
            if (Files.exists(path)) {
                String content = Files.readString(path);
                settingsJson = JsonParser.parseString(content).getAsJsonObject();
            } else {
                settingsJson = new JsonObject();
            }
        } catch (Exception e) {
            settingsJson = new JsonObject();
        }
    }

    private void saveSettingsJson() {
        try {
            Path dir = getSettingsPath().getParent();
            Files.createDirectories(dir);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(getSettingsPath(), gson.toJson(settingsJson));
            // Reload in-memory settings so they take effect immediately
            mod.reloadModSettings();
            showStatus("Settings saved");
        } catch (Exception e) {
            showStatus("Error saving: " + e.getMessage());
        }
    }

    // ======================== SETTINGS CATEGORY HELPERS ========================

    private String getCategoryForKey(String key) {
        if (key.startsWith("show") || key.startsWith("chat") || key.equals("hideAllWarningLogs")) return "DISPLAY";
        if (key.equals("commandPrefix") || key.equals("idleCommand") || key.equals("deathCommand")) return "COMMANDS";
        if (key.startsWith("mob") || key.startsWith("force") || key.startsWith("dodge") || key.startsWith("kill")
                || key.startsWith("entity") || key.startsWith("critical") || key.startsWith("autoEquip")
                || key.startsWith("prefer") || key.startsWith("avoidAttack")) return "COMBAT";
        if (key.startsWith("allow") || key.equals("sprintWhilePathing") || key.equals("avoidScaffoldPlacement")) return "PATHFINDING";
        if (key.startsWith("resource") || key.startsWith("collect") || key.startsWith("autoTool")
                || key.startsWith("always") || key.startsWith("maxResource") || key.startsWith("preferLarger")) return "RESOURCE";
        if (key.startsWith("spread") || key.startsWith("openInv") || key.startsWith("container")
                || key.startsWith("autoClear") || key.startsWith("crafting") || key.startsWith("retry")
                || key.startsWith("maxCraft")) return "CRAFTING";
        if (key.startsWith("autoEat") || key.startsWith("eat") || key.startsWith("autoCollect")
                || key.startsWith("minimum") || key.startsWith("food") || key.startsWith("autoSleep")
                || key.startsWith("replant") || key.startsWith("autoMLG") || key.startsWith("avoidDrown")
                || key.startsWith("extinguish") || key.startsWith("autoClose") || key.startsWith("autoRe")
                || key.startsWith("autoRespawn")) return "SURVIVAL";
        if (key.startsWith("overworld") || key.startsWith("nether") || key.startsWith("homeBase")
                || key.startsWith("return")) return "MOVEMENT";
        if (key.startsWith("avoidSearch") || key.startsWith("avoidOcean") || key.startsWith("areas")) return "BLOCK PROTECTION";
        if (key.startsWith("dontThrow") || key.startsWith("throw") || key.startsWith("reserved")
                || key.startsWith("important") || key.startsWith("autoEquip") || key.startsWith("autoDeposit")
                || key.startsWith("inventory")) return "ITEM MANAGEMENT";
        if (key.startsWith("useBlast") || key.startsWith("limitFuel") || key.startsWith("supported")) return "SMELTING";
        if (key.startsWith("autoBonemeal") || key.startsWith("follow") || key.startsWith("maxWander")
                || key.startsWith("defend") || key.startsWith("homeBaseDefense")) return "AUTONOMOUS";
        if (key.startsWith("verbose") || key.startsWith("log")) return "LOGGING";
        return "OTHER";
    }

    private int getCategoryColor(String cat) {
        return switch (cat) {
            case "DISPLAY" -> CAT_DISPLAY;
            case "COMMANDS" -> CAT_COMMANDS;
            case "COMBAT" -> CAT_COMBAT;
            case "PATHFINDING" -> CAT_PATHFINDING;
            case "RESOURCE" -> CAT_RESOURCE;
            case "CRAFTING" -> CAT_CRAFTING;
            case "SURVIVAL" -> CAT_SURVIVAL;
            case "MOVEMENT", "BLOCK PROTECTION" -> CAT_MOVEMENT;
            case "ITEM MANAGEMENT" -> CAT_ITEMS;
            case "SMELTING" -> CAT_SMELTING;
            case "AUTONOMOUS" -> CAT_AUTONOMOUS;
            case "LOGGING" -> CAT_LOGGING;
            default -> TEXT_DIM;
        };
    }

    private String formatKey(String key) {
        // Convert camelCase to readable: "allowParkour" -> "Allow Parkour"
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (i == 0) {
                sb.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                sb.append(' ').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ======================== RENDER ========================

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, BG);
        renderTabBar(ctx, mouseX, mouseY);
        renderContent(ctx, mouseX, mouseY, delta);
        renderStatusBar(ctx);

        if (!statusMessage.isEmpty() && System.currentTimeMillis() - statusTime < 2500) {
            int tw = textRenderer.getWidth(statusMessage) + 16;
            int tx = (this.width - tw) / 2;
            int ty = this.height - 52;
            ctx.fill(tx - 2, ty - 2, tx + tw + 2, ty + 14, 0xDD1C2128);
            drawText(ctx, statusMessage, tx + 8, ty, SUCCESS);
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}

    private void renderTabBar(DrawContext ctx, int mouseX, int mouseY) {
        ctx.fill(0, 0, this.width, 28, BG_LIGHTER);
        ctx.fill(0, 28, this.width, 29, BORDER);
        int tabWidth = this.width / TAB_NAMES.length;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int x = i * tabWidth;
            boolean active = (i == selectedTab);
            if (active) {
                ctx.fill(x, 0, x + tabWidth, 29, BG);
                ctx.fill(x, 27, x + tabWidth, 29, ACCENT);
            }
            drawCenteredText(ctx, TAB_NAMES[i], x + tabWidth / 2, 9, active ? TEXT_BRIGHT : TEXT_DIM);
        }
    }

    private void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int y = 32;
        switch (selectedTab) {
            case 0 -> renderTasksTab(ctx, mouseX, mouseY, y);
            case 1 -> renderMacrosTab(ctx, mouseX, mouseY, y);
            case 2 -> renderCommandsTab(ctx, mouseX, mouseY, y);
            case 3 -> renderSettingsTab(ctx, mouseX, mouseY, y);
            case 4 -> renderShulkersTab(ctx, mouseX, mouseY, y);
            case 5 -> renderSchematicsTab(ctx, mouseX, mouseY, y);
            case 6 -> renderLogTab(ctx, mouseX, mouseY, y);
        }
    }

    // ======================== TASKS TAB ========================

    private void renderTasksTab(DrawContext ctx, int mouseX, int mouseY, int y) {
        TaskRunner runner = mod.getTaskRunner();
        if (runner == null) { drawText(ctx, "Not initialized.", 14, y + 4, TEXT_DIM); return; }

        TaskChain activeChain = runner.getCurrentTaskChain();
        String chainName = activeChain != null ? activeChain.getName() : "None";

        ctx.fill(10, y, this.width - 10, y + 18, BG_LIGHTER);
        drawText(ctx, "Active", 14, y + 3, TEXT_DIM);
        drawText(ctx, chainName, 60, y + 3, activeChain != null ? SUCCESS : TEXT_DIM);
        y += 22;

        ctx.fill(10, y, this.width - 10, y + 16, BG_LIGHTER);
        drawText(ctx, "BARITONE", 14, y + 3, ACCENT);
        y += 18;
        boolean pathing = mod.getClientBaritone().getPathingBehavior().isPathing();
        drawText(ctx, "  Pathing: " + (pathing ? "ACTIVE" : "idle"), 14, y, pathing ? SUCCESS : TEXT_DIM);
        y += 13;
        drawText(ctx, "  Goal: " + mod.getClientBaritone().getCustomGoalProcess().getGoal(), 14, y, TEXT_DIM);
        y += 16;

        if (mod.getPlayer() != null) {
            int occupied = 0;
            for (var stack : mod.getPlayer().getInventory().main) {
                if (!stack.isEmpty()) occupied++;
            }
            drawText(ctx, "  Position: " + mod.getPlayer().getBlockPos().toShortString()
                    + "  Health: " + String.format("%.1f", mod.getPlayer().getHealth())
                    + "  Inventory: " + occupied + "/36", 14, y, TEXT_DIM);
            y += 13;
            drawText(ctx, "  Cursor: "
                    + adris.belfegor.util.helpers.StorageHelper.getItemStackInCursorSlot()
                    + "  Shulkers remembered: " + ShulkerMemory.getInstance().size(),
                    14, y, TEXT_DIM);
            y += 16;
        }

        ctx.fill(10, y, this.width - 10, y + 1, BORDER);
        y += 4;

        ctx.fill(10, y, this.width - 10, y + 16, BG_LIGHTER);
        drawText(ctx, "TASK CHAINS", 14, y + 3, ACCENT);
        y += 18;

        List<TaskChain> allChains = runner.getAllChains();
        if (allChains != null) {
            for (TaskChain chain : allChains) {
                if (y + 14 > this.height - 40) break;
                boolean isActive = chain == activeChain;
                ctx.fill(12, y, this.width - 12, y + 13, isActive ? 0xFF0D2818 : BG_LIGHTER);
                String icon = isActive ? ">" : chain.isActive() ? "+" : " ";
                drawText(ctx, " [" + icon + "] " + chain.getName() + "  (p=" + String.format("%.0f", chain.getPriority(mod)) + ")", 14, y + 1, isActive ? SUCCESS : chain.isActive() ? ACCENT : TEXT_DIM);

                if (isActive) {
                    List<Task> tasks = chain.getTasks();
                    if (tasks != null && !tasks.isEmpty()) {
                        y += 14;
                        for (int t = 0; t < Math.min(tasks.size(), 6); t++) {
                            if (y + 11 > this.height - 40) break;
                            Task task = tasks.get(t);
                            String prefix = t < tasks.size() - 1 ? "|-" : "\\-";
                            drawText(ctx, "     " + prefix + " " + task, 20, y + 1, task.isActive() ? TEXT_BRIGHT : TEXT_DIM);
                            y += 11;
                        }
                    }
                }
                y += 15;
            }
        }

        y += 4;
        ctx.fill(10, y, this.width - 10, y + 16, BG_LIGHTER);
        drawText(ctx, "USER TASK", 14, y + 3, WARNING);
        y += 18;
        if (mod.getUserTaskChain().isActive()) {
            Task current = mod.getUserTaskChain().getCurrentTask();
            drawText(ctx, current != null ? current.toString() : "active", 14, y, TEXT);
        } else {
            drawText(ctx, "  none", 14, y, TEXT_DIM);
        }
    }

    // ======================== SHULKERS TAB ========================

    private void renderShulkersTab(DrawContext ctx, int mouseX, int mouseY, int y) {
        long now = System.currentTimeMillis();
        if (mod != null && mod.getPlayer() != null && now - lastShulkerUiSync > 1000) {
            ShulkerInteractionTask.syncCarriedShulkerMemory(mod);
            lastShulkerUiSync = now;
        }
        boolean auto = settingsJson != null
                && settingsJson.has("autoShulkerMode")
                && settingsJson.get("autoShulkerMode").getAsBoolean();
        ctx.fill(10, y, this.width - 10, y + 22, BG_LIGHTER);
        drawText(ctx, "AUTO SHULKER", 14, y + 6, TEXT_BRIGHT);
        int buttonX = this.width - 94;
        ctx.fill(buttonX, y + 3, this.width - 14, y + 19, auto ? 0xFF174D2A : 0xFF4D1F24);
        drawCenteredText(ctx, auto ? "ENABLED" : "DISABLED",
                (buttonX + this.width - 14) / 2, y + 7, auto ? SUCCESS : ERROR);
        y += 28;

        String mode = settingsJson != null && settingsJson.has("autoShulkerSortMode")
                ? settingsJson.get("autoShulkerSortMode").getAsString()
                : "detection";
        int timerSeconds = settingsJson != null && settingsJson.has("autoShulkerTimerSeconds")
                ? settingsJson.get("autoShulkerTimerSeconds").getAsInt()
                : 60;
        int threshold = settingsJson != null && settingsJson.has("autoShulkerInventoryThreshold")
                ? settingsJson.get("autoShulkerInventoryThreshold").getAsInt()
                : 80;
        ctx.fill(10, y, this.width - 10, y + 48, BG_LIGHTER);
        drawText(ctx, "Sort mode", 14, y + 5, TEXT_BRIGHT);
        int modeButtonX = this.width - 132;
        ctx.fill(modeButtonX, y + 3, this.width - 14, y + 19, 0xFF17324D);
        drawCenteredText(ctx, mode.equalsIgnoreCase("timer") ? "TIMER" : "DETECTION",
                (modeButtonX + this.width - 14) / 2, y + 7, ACCENT);
        drawText(ctx, "Timer: every " + timerSeconds + "s while idle", 14, y + 21, TEXT_DIM);
        drawText(ctx, "Detection: sort when inventory is " + threshold + "% full", 14, y + 34, TEXT_DIM);
        y += 56;

        ctx.fill(10, y, this.width - 10, y + 66, BG_LIGHTER);
        drawText(ctx, "Auto-sort item rules", 14, y + 5, ACCENT);
        drawText(ctx, "[x] Store ordinary resources, blocks, mob drops, ores, ingots, food, plants, and clutter",
                16, y + 19, TEXT);
        drawText(ctx, "[ ] Never store shulker boxes inside shulker boxes",
                16, y + 32, WARNING);
        drawText(ctx, "[ ] Never auto-store tools, weapons, armor, shields, bows, tridents, or damaged gear",
                16, y + 45, WARNING);
        y += 74;

        List<ShulkerMemory.ShulkerEntry> entries = ShulkerMemory.getInstance().getAllShulkers();
        entries.sort(Comparator.comparingLong(entry -> -entry.lastUpdated));
        if (shulkerScrollOffset >= entries.size()) {
            shulkerScrollOffset = 0;
        }

        int totalItems = 0;
        int totalStacks = 0;
        for (ShulkerMemory.ShulkerEntry entry : entries) {
            totalStacks += entry.contents.size();
            for (ShulkerMemory.ShulkerItem item : entry.contents) {
                totalItems += item.count;
            }
        }
        ctx.fill(10, y, this.width - 10, y + 32, BG_LIGHTER);
        drawText(ctx, "Indexed shulkers: " + entries.size()
                        + "   Item types: " + totalStacks
                        + "   Total items: " + totalItems,
                14, y + 5, TEXT_BRIGHT);
        drawText(ctx, "Memory file: belfegor/belfegor_shulker_memory.json",
                14, y + 18, TEXT_DIM);
        y += 40;

        drawText(ctx, "Cataloged shulker sub-inventories", 14, y, ACCENT);
        y += 15;
        drawText(ctx, "Resources here are withdrawn before gathering or crafting replacements.",
                14, y, TEXT_DIM);
        y += 17;

        int index = 0;
        int drawIndex = 0;
        for (ShulkerMemory.ShulkerEntry entry : entries) {
            if (index++ < shulkerScrollOffset) continue;
            int height = 28 + Math.max(1, (entry.contents.size() + 3) / 4) * 12;
            if (y + height > this.height - 36) break;
            ctx.fill(10, y, this.width - 10, y + height - 3,
                    drawIndex++ % 2 == 0 ? BG_LIGHTER : BG_LIGHT);
            String location = "inventory".equals(entry.location)
                    ? "Inventory slot " + entry.inventorySlot + " - " + entry.shulkerItem
                    : "World " + entry.x + ", " + entry.y + ", " + entry.z;
            drawText(ctx, location, 14, y + 4, TEXT_BRIGHT);
            drawText(ctx, entry.contents.size() + " item types", this.width - 100, y + 4, TEXT_DIM);
            int itemY = y + 17;
            int col = 0;
            if (entry.contents.isEmpty()) {
                drawText(ctx, "(empty)", 16, itemY, TEXT_DIM);
            } else {
                for (ShulkerMemory.ShulkerItem item : entry.contents) {
                    int colWidth = Math.max(120, (this.width - 28) / 4);
                    String label = item.itemName + " x" + item.count;
                    if (label.length() > 22) label = label.substring(0, 19) + "...";
                    drawText(ctx, label, 16 + col * colWidth, itemY, TEXT);
                    if (++col == 4) {
                        col = 0;
                        itemY += 12;
                    }
                }
            }
            y += height;
        }
        if (entries.isEmpty()) {
            drawText(ctx, "No shulker boxes have been cataloged yet.", 14, y + 4, TEXT_DIM);
        }
    }

    // ======================== MACROS TAB ========================

    private void renderMacrosTab(DrawContext ctx, int mouseX, int mouseY, int y) {
        macroButtonHitboxes.clear();
        MacroRunner runner = getMacroRunner();
        List<MacroChain> macros = MacroStorage.getMacros();
        int listW = this.width * 2 / 5;

        ctx.fill(10, y, listW, this.height - 36, BG_LIGHTER);
        drawText(ctx, "Macros (" + macros.size() + ")", 14, y + 4, TEXT_BRIGHT);
        renderButton(ctx, 14, y + 20, 58, y + 36, "New", "macro:new", mouseX, mouseY, ACCENT);
        renderButton(ctx, 76, y + 20, 134, y + 36, "Save", "macro:save", mouseX, mouseY, SUCCESS);
        renderButton(ctx, 138, y + 20, 204, y + 36, "Reload", "macro:reload", mouseX, mouseY, WARNING);
        int listY = y + 42;

        if (runner != null && runner.isRunning()) {
            ctx.fill(12, listY, listW - 2, listY + 14, 0xFF2A2A0A);
            drawText(ctx, "> " + runner.getStatusString(), 14, listY + 2, WARNING);
            listY += 16;
        }

        int maxVisible = (this.height - 80) / 16;
        int startIdx = Math.max(0, scrollOffset);
        for (int i = startIdx; i < macros.size() && i - startIdx < maxVisible; i++) {
            MacroChain macro = macros.get(i);
            boolean selected = i == selectedMacroIndex;
            ctx.fill(12, listY, listW - 2, listY + 14, selected ? HIGHLIGHT : BG_LIGHTER);
            drawText(ctx, macro.getName() + " (" + macro.getSteps().size() + ")", 16, listY + 2, selected ? TEXT_BRIGHT : TEXT);
            listY += 16;
        }

        int rightX = listW + 10;
        macroNameInput.setX(rightX + 4);
        macroNameInput.setY(y + 42);
        macroNameInput.setWidth(Math.max(120, (this.width - rightX - 24) / 3));
        macroStepInput.setX(rightX + 8);
        macroStepInput.setY(y + 78);
        macroStepInput.setWidth(Math.max(160, this.width - rightX - 24));
        macroDescInput.setX(rightX + macroNameInput.getWidth() + 12);
        macroDescInput.setY(y + 42);
        macroDescInput.setWidth(Math.max(160, this.width - macroDescInput.getX() - 14));
        ctx.fill(rightX, y, this.width - 10, this.height - 36, BG_LIGHTER);
        if (selectedMacroIndex >= 0 && selectedMacroIndex < macros.size()) {
            MacroChain m = macros.get(selectedMacroIndex);
            drawText(ctx, "Macro editor", rightX + 4, y + 4, TEXT_BRIGHT);
            int buttonY = y + 20;
            renderButton(ctx, rightX + 4, buttonY, rightX + 60, buttonY + 16, "Run", "macro:run", mouseX, mouseY, SUCCESS);
            renderButton(ctx, rightX + 64, buttonY, rightX + 124, buttonY + 16, runner != null && runner.isPaused() ? "Resume" : "Pause", "macro:pause", mouseX, mouseY, WARNING);
            renderButton(ctx, rightX + 128, buttonY, rightX + 188, buttonY + 16, "Stop", "macro:stop", mouseX, mouseY, ERROR);
            renderButton(ctx, rightX + 192, buttonY, rightX + 266, buttonY + 16, "Duplicate", "macro:duplicate", mouseX, mouseY, ACCENT);
            renderButton(ctx, rightX + 270, buttonY, rightX + 330, buttonY + 16, "Delete", "macro:delete", mouseX, mouseY, ERROR);
            renderButton(ctx, rightX + 334, buttonY, rightX + 404, buttonY + 16, m.isLoop() ? "Loop:on" : "Loop:off", "macro:loop", mouseX, mouseY, m.isLoop() ? SUCCESS : TEXT_DIM);

            int ry = y + 42;
            drawText(ctx, "Name and description are editable. Press Save after changes.", rightX + 4, ry, TEXT_DIM);
            ry += 38;
            renderButton(ctx, rightX + 4, ry, rightX + 84, ry + 16, "Add step", "macro:add-step", mouseX, mouseY, SUCCESS);
            renderButton(ctx, rightX + 88, ry, rightX + 156, ry + 16, "Remove", "macro:remove-step", mouseX, mouseY, ERROR);
            renderButton(ctx, rightX + 160, ry, rightX + 210, ry + 16, "Up", "macro:step-up", mouseX, mouseY, ACCENT);
            renderButton(ctx, rightX + 214, ry, rightX + 274, ry + 16, "Down", "macro:step-down", mouseX, mouseY, ACCENT);
            ry += 22;
            drawText(ctx, "Steps:", rightX + 4, ry, TEXT);
            ry += 14;
            for (int i = 0; i < m.getSteps().size(); i++) {
                if (ry + 13 > this.height - 42) break;
                MacroStep step = m.getSteps().get(i);
                ctx.fill(rightX + 2, ry - 1, this.width - 12, ry + 11, i == selectedMacroStepIndex ? HIGHLIGHT : BG_LIGHTER);
                drawText(ctx, (i + 1) + ". " + step.toString(), rightX + 6, ry, step.isEnabled() ? TEXT : TEXT_DIM);
                ry += 13;
            }
            if (!m.getInputs().isEmpty()) {
                ry += 8;
                drawText(ctx, "Inputs:", rightX + 4, ry, TEXT);
                ry += 14;
                for (var e : m.getInputs().entrySet()) {
                    drawText(ctx, e.getKey() + " = " + e.getValue(), rightX + 6, ry, SUCCESS);
                    ry += 12;
                }
            }
        } else {
            drawText(ctx, "Select a macro or click New.", rightX + 4, y + 4, TEXT_DIM);
            drawText(ctx, "Macros run one command at a time and wait for the active user task to finish before advancing.",
                    rightX + 4, y + 18, TEXT_DIM);
        }
    }

    // ======================== COMMANDS TAB ========================

    private void renderCommandsTab(DrawContext ctx, int mouseX, int mouseY, int y) {
        commandExampleHitboxes.clear();
        List<adris.belfegor.commandsystem.Command> commands = getFilteredCommands();
        int listW = Math.max(220, this.width / 3);
        int top = 54;
        ctx.fill(10, top, listW, this.height - 58, BG_LIGHTER);
        drawText(ctx, "COMMANDS (" + commands.size() + ")", 14, top + 5, ACCENT);

        int listY = top + 21;
        int maxVisible = Math.max(1, (this.height - 112) / 16);
        commandScrollOffset = Math.min(commandScrollOffset,
                Math.max(0, commands.size() - maxVisible));
        if (selectedCommandIndex >= commands.size()) {
            selectedCommandIndex = Math.max(0, commands.size() - 1);
        }
        for (int i = commandScrollOffset;
             i < commands.size() && i - commandScrollOffset < maxVisible; i++) {
            boolean selected = i == selectedCommandIndex;
            ctx.fill(12, listY, listW - 2, listY + 14,
                    selected ? HIGHLIGHT : BG_LIGHTER);
            var command = commands.get(i);
            drawText(ctx, "@" + command.getName(), 16, listY + 2,
                    selected ? TEXT_BRIGHT : ACCENT);
            String category = CommandDocumentation.categoryFor(command.getName());
            drawText(ctx, category, listW - textRenderer.getWidth(category) - 8, listY + 2,
                    selected ? WARNING : TEXT_DIM);
            listY += 16;
        }

        int rightX = listW + 10;
        ctx.fill(rightX, top, this.width - 10, this.height - 58, BG_LIGHTER);
        if (commands.isEmpty()) {
            drawText(ctx, "No commands match the search.", rightX + 8, top + 8, TEXT_DIM);
            return;
        }

        var command = commands.get(selectedCommandIndex);
        int detailY = top + 7;
        String prefix = mod.getModSettings().getCommandPrefix();
        drawText(ctx, prefix + command.getName(), rightX + 8, detailY, TEXT_BRIGHT);
        String category = CommandDocumentation.categoryFor(command.getName());
        drawText(ctx, category, this.width - textRenderer.getWidth(category) - 18, detailY, WARNING);
        detailY += 17;
        for (String line : wrapText(command.getDetailedDescription(), this.width - rightX - 28)) {
            drawText(ctx, line, rightX + 8, detailY, TEXT);
            detailY += 12;
        }
        detailY += 5;
        drawText(ctx, "USAGE", rightX + 8, detailY, ACCENT);
        detailY += 14;
        drawText(ctx, prefix + command.getHelpRepresentation(), rightX + 12, detailY, SUCCESS);
        detailY += 18;

        drawText(ctx, "INPUTS", rightX + 8, detailY, ACCENT);
        detailY += 14;
        var arguments = command.getArguments();
        if (arguments.length == 0) {
            drawText(ctx, "No inputs.", rightX + 12, detailY, TEXT_DIM);
            detailY += 14;
        } else {
            for (var argument : arguments) {
                if (detailY > this.height - 112) break;
                String header = argument.getName() + "  [" + argument.getTypeName() + "]  "
                        + (argument.hasDefault() ? "optional" : "required");
                drawText(ctx, header, rightX + 12, detailY,
                        argument.hasDefault() ? WARNING : TEXT_BRIGHT);
                detailY += 12;
                for (String line : wrapText("Expected: " + argument.getExpectedValues(),
                        this.width - rightX - 38)) {
                    drawText(ctx, line, rightX + 18, detailY, TEXT_DIM);
                    detailY += 11;
                }
                detailY += 4;
            }
        }

        if (detailY < this.height - 86) {
            drawText(ctx, "RUNNABLE EXAMPLES - double-click to run", rightX + 8, detailY, ACCENT);
            detailY += 14;
            for (String example : command.getExamples()) {
                if (detailY > this.height - 72) break;
                boolean hover = mouseX >= rightX + 10 && mouseX <= this.width - 18
                        && mouseY >= detailY - 2 && mouseY <= detailY + 11;
                ctx.fill(rightX + 10, detailY - 2, this.width - 18, detailY + 11,
                        hover ? HIGHLIGHT : BG_LIGHT);
                drawText(ctx, example, rightX + 14, detailY, SUCCESS);
                commandExampleHitboxes.add(new CommandExampleHitbox(
                        rightX + 10, detailY - 2, this.width - 18, detailY + 11, example));
                detailY += 15;
            }
        }
        drawText(ctx, "Enter a command below and press Enter.",
                10, this.height - 52, TEXT_DIM);
    }

    private List<adris.belfegor.commandsystem.Command> getFilteredCommands() {
        if (Belfegor.getCommandExecutor() == null) return new ArrayList<>();
        String search = commandSearchInput == null
                ? "" : commandSearchInput.getText().trim().toLowerCase();
        List<adris.belfegor.commandsystem.Command> result =
                new ArrayList<>(Belfegor.getCommandExecutor().allCommands());
        result.removeIf(command -> !search.isEmpty()
                && !command.getName().toLowerCase().contains(search)
                && !CommandDocumentation.categoryFor(command.getName()).toLowerCase().contains(search)
                && !command.getDescription().toLowerCase().contains(search)
                && !command.getHelpRepresentation().toLowerCase().contains(search));
        result.sort(Comparator.comparing(adris.belfegor.commandsystem.Command::getName));
        return result;
    }

    // ======================== SCHEMATICS TAB ========================

    private void renderSchematicsTab(DrawContext ctx, int mouseX, int mouseY, int y) {
        schematicCommandHitboxes.clear();
        Path importDir = Path.of(MinecraftClient.getInstance().runDirectory.getAbsolutePath(),
                "belfegor", "schematics", "imported");
        ctx.fill(10, y, this.width - 10, this.height - 36, BG_LIGHTER);
        drawText(ctx, "SCHEMATIC BUILDER", 14, y + 5, ACCENT);
        y += 20;
        drawText(ctx, "Import command:", 14, y, TEXT_DIM);
        y += 13;
        drawText(ctx, "@build base import \"C:\\path\\to\\building.litematic\" optional_name", 18, y, SUCCESS);
        y += 16;
        drawText(ctx, "Placement origin is your current player block. Belfegor copies the file, parses the block palette,",
                14, y, TEXT);
        y += 12;
        drawText(ctx, "creates a staging chest, collects/deposits required resources, then builds and remembers the structure.",
                14, y, TEXT);
        y += 18;
        drawText(ctx, "Imported folder: " + importDir, 14, y, TEXT_DIM);
        y += 18;

        List<Path> files = listSchematicFiles(importDir);
        drawText(ctx, "KNOWN IMPORTS (" + files.size() + ")", 14, y, ACCENT);
        y += 16;
        if (files.isEmpty()) {
            drawText(ctx, "No imported schematics yet. Run the import command with a .litematic file path.",
                    18, y, TEXT_DIM);
            return;
        }

        int maxVisible = Math.max(1, (this.height - y - 46) / 36);
        schematicScrollOffset = Math.min(schematicScrollOffset, Math.max(0, files.size() - maxVisible));
        for (int i = schematicScrollOffset;
             i < files.size() && i - schematicScrollOffset < maxVisible; i++) {
            Path file = files.get(i);
            String fileName = file.getFileName().toString();
            ctx.fill(14, y - 2, this.width - 14, y + 31, BG_LIGHT);
            drawText(ctx, fileName, 20, y, TEXT_BRIGHT);
            y += 12;
            String command = "@build base import \"" + file.toAbsolutePath() + "\" "
                    + fileName.replaceAll("\\.[^.]+$", "").replaceAll("[^a-zA-Z0-9_.-]", "_");
            boolean hover = mouseX >= 20 && mouseX <= this.width - 18 && mouseY >= y - 2 && mouseY <= y + 11;
            ctx.fill(18, y - 2, this.width - 18, y + 11, hover ? HIGHLIGHT : BG_LIGHT);
            drawText(ctx, command, 20, y, SUCCESS);
            schematicCommandHitboxes.add(new CommandExampleHitbox(18, y - 2, this.width - 18, y + 11, command));
            y += 24;
        }
    }

    private List<Path> listSchematicFiles(Path importDir) {
        try {
            Files.createDirectories(importDir);
            try (var stream = Files.list(importDir)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                            return name.endsWith(".litematic")
                                    || name.endsWith(".json")
                                    || name.endsWith(".belfegor_schematic");
                        })
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .toList();
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (textRenderer.getWidth(candidate) > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    // ======================== SETTINGS TAB (DYNAMIC FROM JSON) ========================

    private void renderSettingsTab(DrawContext ctx, int mouseX, int mouseY, int y) {
        settingsSearchInput.setVisible(true);
        settingsSearchInput.setEditable(true);
        settingsValueInput.setVisible(editingKey != null && !"boolean".equals(editingType));
        settingsValueInput.setEditable(editingKey != null && !"boolean".equals(editingType));

        y = 32;
        String search = settingsSearchInput.getText().trim().toLowerCase();

        // Editor bar
        if (editingKey != null) {
            ctx.fill(10, y, this.width - 10, y + 20, BG_LIGHT);
            String typeLabel = switch (editingType) {
                case "number" -> "number";
                case "string" -> "string";
                case "array" -> "array [" + editingArrayIndex + "]";
                default -> editingType;
            };
            drawText(ctx, "Editing: " + formatKey(editingKey) + " (" + typeLabel + ")  [Enter=save, Esc=cancel]", 14, y + 5, WARNING);
            y += 22;
        }

        y += 18;
        if (settingsJson == null) return;

        // Group keys by category
        TreeMap<String, List<Map.Entry<String, JsonElement>>> grouped = new TreeMap<>();
        for (var entry : settingsJson.entrySet()) {
            if (entry.getKey().startsWith("_")) continue; // skip internal
            String cat = getCategoryForKey(entry.getKey());
            grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(entry);
        }

        int entryY = y;
        int contentBottom = this.height - 36;

        for (var catEntry : grouped.entrySet()) {
            String cat = catEntry.getKey();
            List<Map.Entry<String, JsonElement>> entries = catEntry.getValue();

            boolean anyMatch = search.isEmpty();
            if (!search.isEmpty()) {
                for (var e : entries) {
                    if (e.getKey().toLowerCase().contains(search) || cat.toLowerCase().contains(search)) {
                        anyMatch = true;
                        break;
                    }
                }
            }
            if (!anyMatch) continue;

            // Category header
            if (entryY + 16 > contentBottom) break;
            int adjustedY = entryY - settingsScrollOffset;
            if (adjustedY + 16 > y - 20 && adjustedY < contentBottom) {
                ctx.fill(10, adjustedY, this.width - 10, adjustedY + 15, BG_LIGHTER);
                drawText(ctx, cat, 14, adjustedY + 3, getCategoryColor(cat));
            }
            entryY += 17;

            for (var kv : entries) {
                String key = kv.getKey();
                JsonElement val = kv.getValue();

                if (!search.isEmpty() && !key.toLowerCase().contains(search) && !cat.toLowerCase().contains(search)) {
                    entryY += 15;
                    continue;
                }

                if (entryY + 15 > contentBottom + settingsScrollOffset) break;

                int drawY = entryY - settingsScrollOffset;
                if (drawY + 15 > y - 20 && drawY < contentBottom) {
                    boolean hover = mouseX >= 12 && mouseX < this.width - 12 && mouseY >= drawY && mouseY <= drawY + 14;
                    boolean isEditing = key.equals(editingKey);
                    ctx.fill(12, drawY, this.width - 12, drawY + 14, isEditing ? 0xFF1A2332 : hover ? HIGHLIGHT : BG_LIGHTER);

                    String label = formatKey(key) + ": ";

                    if (val.isJsonPrimitive()) {
                        JsonPrimitive prim = val.getAsJsonPrimitive();
                        if (prim.isBoolean()) {
                            drawText(ctx, label, 16, drawY + 3, TEXT);
                            drawText(ctx, prim.getAsBoolean() ? "ON" : "OFF", 16 + textRenderer.getWidth(label), drawY + 3, prim.getAsBoolean() ? SUCCESS : ERROR);
                        } else if (prim.isNumber()) {
                            drawText(ctx, label, 16, drawY + 3, TEXT);
                            drawText(ctx, String.valueOf(prim.getAsNumber()), 16 + textRenderer.getWidth(label), drawY + 3, ACCENT);
                        } else {
                            drawText(ctx, label, 16, drawY + 3, TEXT);
                            String sv = prim.getAsString();
                            if (sv.isEmpty()) sv = "(empty)";
                            else if (sv.length() > 50) sv = sv.substring(0, 47) + "...";
                            drawText(ctx, sv, 16 + textRenderer.getWidth(label), drawY + 3, SUCCESS);
                        }
                    } else if (val.isJsonArray()) {
                        JsonArray arr = val.getAsJsonArray();
                        drawText(ctx, label, 16, drawY + 3, TEXT);
                        drawText(ctx, "[" + arr.size() + " items]  click to edit", 16 + textRenderer.getWidth(label), drawY + 3, 0xFF7EE787);
                    } else if (val.isJsonObject()) {
                        drawText(ctx, label, 16, drawY + 3, TEXT);
                        drawText(ctx, "{object}", 16 + textRenderer.getWidth(label), drawY + 3, TEXT_DIM);
                    }
                }
                entryY += 15;
            }
            entryY += 3;
        }

        // Scrollbar
        int totalH = entryY - y;
        int visibleH = contentBottom - y;
        if (totalH > visibleH && visibleH > 0) {
            int barH = Math.max(20, (int)((float)visibleH / totalH * visibleH));
            int barY = (int)((float)settingsScrollOffset / Math.max(1, totalH - visibleH) * (visibleH - barH));
            ctx.fill(this.width - 6, y, this.width - 4, y + visibleH, BG_LIGHT);
            ctx.fill(this.width - 6, y + barY, this.width - 4, y + barY + barH, BORDER);
        }
    }

    // ======================== LOG TAB ========================

    private void renderLogTab(DrawContext ctx, int mouseX, int mouseY, int y) {
        drawText(ctx, "Event Log", 14, y + 4, TEXT_BRIGHT);
        y += 20;
        var entries = eventLog.getFilteredEntries();
        int maxVisible = (this.height - 70) / 12;
        int startIdx = Math.max(0, Math.min(logScrollOffset, entries.size() - maxVisible));
        startIdx = Math.max(0, startIdx);
        for (int i = startIdx; i < entries.size() && i - startIdx < maxVisible; i++) {
            var entry = entries.get(i);
            int color = switch (entry.level) {
                case INFO -> TEXT;
                case WARNING -> WARNING;
                case ERROR -> ERROR;
                case DEBUG -> TEXT_DIM;
                case TASK -> ACCENT;
            };
            long age = System.currentTimeMillis() - entry.timestamp;
            int alpha = (int) Math.max(120, 255 - age / 80);
            color = (color & 0x00FFFFFF) | (alpha << 24);
            drawText(ctx, String.format("[%tT] ", entry.timestamp) + entry.message, 10, y, color);
            y += 12;
        }
        if (entries.isEmpty()) drawText(ctx, "No log entries.", 14, y, TEXT_DIM);
    }

    // ======================== STATUS BAR ========================

    private void renderStatusBar(DrawContext ctx) {
        int barY = this.height - 30;
        ctx.fill(0, barY, this.width, this.height, BG_LIGHTER);
        ctx.fill(0, barY, this.width, barY + 1, BORDER);
        TaskRunner runner = mod.getTaskRunner();
        TaskChain active = runner != null ? runner.getCurrentTaskChain() : null;
        String status = active != null ? active.getName() : "Idle";
        MacroRunner mr = getMacroRunner();
        if (mr != null && mr.isRunning()) status += " | " + mr.getStatusString();
        drawText(ctx, status, 10, barY + 8, TEXT_DIM);
        drawText(ctx, "[+] ABORT  [ESC] Close", this.width - 185, barY + 8, 0xFF8B949E);
    }

    // ======================== INPUT HANDLING ========================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseY < 28) {
            int tabW = this.width / TAB_NAMES.length;
            int clicked = (int)(mouseX / tabW);
            if (clicked >= 0 && clicked < TAB_NAMES.length) { selectedTab = clicked; onTabSwitch(); }
            return true;
        }
        if (selectedTab == 3 && button == 0) return handleSettingsClick(mouseX, (int) mouseY);
        if (selectedTab == 2 && button == 0 && mouseY >= 75 && mouseY < this.height - 58) {
            for (CommandExampleHitbox hitbox : commandExampleHitboxes) {
                if (hitbox.contains(mouseX, mouseY)) {
                    long now = System.currentTimeMillis();
                    boolean doubleClick = hitbox.command.equals(lastClickedCommandExample)
                            && now - lastCommandExampleClickTime <= 450;
                    lastClickedCommandExample = hitbox.command;
                    lastCommandExampleClickTime = now;
                    commandInput.setText(hitbox.command);
                    if (doubleClick) {
                        mod.getCommandExecutor().executeWithPrefix(hitbox.command);
                        eventLog.info("Executed example: " + hitbox.command);
                        showStatus("Executed: " + hitbox.command);
                    } else {
                        showStatus("Click again to run: " + hitbox.command);
                    }
                    return true;
                }
            }
            List<adris.belfegor.commandsystem.Command> commands = getFilteredCommands();
            int listW = Math.max(220, this.width / 3);
            if (mouseX >= 10 && mouseX <= listW) {
                int clicked = commandScrollOffset + (int) ((mouseY - 75) / 16);
                if (clicked >= 0 && clicked < commands.size()) {
                    selectedCommandIndex = clicked;
                    return true;
                }
            }
        }
        if (selectedTab == 4 && button == 0 && mouseY >= 32 && mouseY <= 54
                && mouseX >= this.width - 94 && mouseX <= this.width - 14) {
            boolean current = settingsJson.has("autoShulkerMode")
                    && settingsJson.get("autoShulkerMode").getAsBoolean();
            settingsJson.addProperty("autoShulkerMode", !current);
            saveSettingsJson();
            showStatus("Auto shulker " + (!current ? "enabled" : "disabled"));
            return true;
        }
        if (selectedTab == 4 && button == 0 && mouseY >= 60 && mouseY <= 82
                && mouseX >= this.width - 132 && mouseX <= this.width - 14) {
            String current = settingsJson.has("autoShulkerSortMode")
                    ? settingsJson.get("autoShulkerSortMode").getAsString()
                    : "detection";
            String next = current.equalsIgnoreCase("timer") ? "detection" : "timer";
            settingsJson.addProperty("autoShulkerSortMode", next);
            saveSettingsJson();
            showStatus("Auto shulker mode: " + next);
            return true;
        }
        if (selectedTab == 5 && button == 0) {
            for (CommandExampleHitbox hitbox : schematicCommandHitboxes) {
                if (hitbox.contains(mouseX, mouseY)) {
                    commandInput.setText(hitbox.command);
                    selectedTab = 2;
                    onTabSwitch();
                    showStatus("Loaded command into Commands tab. Press Enter to run.");
                    return true;
                }
            }
        }
        if (selectedTab == 1) return handleMacrosClick(mouseX, mouseY, button);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleSettingsClick(double mouseX, int mouseY) {
        if (settingsJson == null) return false;
        String search = settingsSearchInput.getText().trim().toLowerCase();

        // Calculate positions matching renderSettingsTab
        int y = 32;
        if (editingKey != null) y += 22;
        y += 18;

        TreeMap<String, List<Map.Entry<String, JsonElement>>> grouped = new TreeMap<>();
        for (var entry : settingsJson.entrySet()) {
            if (entry.getKey().startsWith("_")) continue;
            String cat = getCategoryForKey(entry.getKey());
            grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(entry);
        }

        int entryY = y;
        int contentBottom = this.height - 36;

        for (var catEntry : grouped.entrySet()) {
            String cat = catEntry.getKey();
            var entries = catEntry.getValue();
            boolean anyMatch = search.isEmpty();
            if (!search.isEmpty()) {
                for (var e : entries) {
                    if (e.getKey().toLowerCase().contains(search) || cat.toLowerCase().contains(search)) { anyMatch = true; break; }
                }
            }
            if (!anyMatch) continue;
            entryY += 17; // category header

            for (var kv : entries) {
                String key = kv.getKey();
                JsonElement val = kv.getValue();
                if (!search.isEmpty() && !key.toLowerCase().contains(search) && !cat.toLowerCase().contains(search)) {
                    entryY += 15;
                    continue;
                }
                if (entryY + 15 > contentBottom + settingsScrollOffset) break;

                int drawY = entryY - settingsScrollOffset;
                if (drawY + 15 > y - 20 && drawY < contentBottom) {
                    if (mouseX >= 12 && mouseX < this.width - 12 && mouseY >= drawY && mouseY <= drawY + 14) {
                        if (val.isJsonPrimitive()) {
                            JsonPrimitive prim = val.getAsJsonPrimitive();
                            if (prim.isBoolean()) {
                                settingsJson.addProperty(key, !prim.getAsBoolean());
                                saveSettingsJson();
                                showStatus(formatKey(key) + " = " + !prim.getAsBoolean());
                                eventLog.info("Setting: " + key + " -> " + !prim.getAsBoolean());
                                return true;
                            } else {
                                // Start editing number/string
                                editingKey = key;
                                editingType = prim.isNumber() ? "number" : "string";
                                editingArrayIndex = -1;
                                settingsValueInput.setText(prim.getAsString());
                                settingsValueInput.setVisible(true);
                                settingsValueInput.setEditable(true);
                                settingsValueInput.setFocused(true);
                                return true;
                            }
                        } else if (val.isJsonArray()) {
                            // Show array items for editing
                            editingKey = key;
                            editingType = "array";
                            editingArrayIndex = 0;
                            JsonArray arr = val.getAsJsonArray();
                            if (arr.size() > 0) {
                                String first = arr.get(0).isJsonPrimitive() ? arr.get(0).getAsString() : arr.get(0).toString();
                                settingsValueInput.setText(String.join(", ", arrToList(arr)));
                            } else {
                                settingsValueInput.setText("");
                            }
                            settingsValueInput.setVisible(true);
                            settingsValueInput.setEditable(true);
                            settingsValueInput.setFocused(true);
                            showStatus("Edit array: comma-separated values");
                            return true;
                        }
                    }
                }
                entryY += 15;
            }
            entryY += 3;
        }
        return false;
    }

    private List<String> arrToList(JsonArray arr) {
        List<String> list = new ArrayList<>();
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive()) {
                list.add(e.getAsString());
            } else {
                list.add(e.toString());
            }
        }
        return list;
    }

    private boolean handleMacrosClick(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (UiButtonHitbox hitbox : macroButtonHitboxes) {
                if (hitbox.contains(mouseX, mouseY)) {
                    handleMacroAction(hitbox.action);
                    return true;
                }
            }
        }
        int listW = this.width * 2 / 5;
        var macros = MacroStorage.getMacros();
        int y = 32;
        if (mouseX < listW && mouseY > y + 42) {
            int clicked = Math.max(0, scrollOffset) + (int)((mouseY - y - 42) / 16);
            if (clicked >= 0 && clicked < macros.size()) { selectedMacroIndex = clicked; selectedMacroStepIndex = -1; updateMacroButtons(); }
            return true;
        }
        if (mouseX > listW + 5 && mouseY > y && selectedMacroIndex >= 0 && selectedMacroIndex < macros.size()) {
            var macro = macros.get(selectedMacroIndex);
            int ry = y + 118;
            for (int i = 0; i < macro.getSteps().size(); i++) {
                if (mouseY >= ry - 1 && mouseY <= ry + 11) { selectedMacroStepIndex = i; return true; }
                ry += 13;
            }
        }
        return false;
    }

    private void handleMacroAction(String action) {
        List<MacroChain> macros = MacroStorage.getMacros();
        MacroRunner runner = getMacroRunner();
        switch (action) {
            case "macro:new" -> {
                String base = "New Macro";
                String name = base;
                int n = 2;
                while (MacroStorage.getMacro(name) != null) name = base + " " + n++;
                MacroChain macro = new MacroChain(name, "Describe what this macro does.");
                macro.addStep(new MacroStep("status", "Example safe command"));
                MacroStorage.addMacro(macro);
                selectedMacroIndex = macros.size() - 1;
                selectedMacroStepIndex = -1;
                updateMacroButtons();
                showStatus("Created macro. Edit fields and Save.");
            }
            case "macro:save" -> {
                if (selectedMacroIndex >= 0 && selectedMacroIndex < macros.size()) {
                    MacroChain macro = macros.get(selectedMacroIndex);
                    String newName = macroNameInput.getText().trim();
                    if (!newName.isEmpty()) macro.setName(newName);
                    macro.setDescription(macroDescInput.getText().trim());
                    MacroStorage.save();
                    showStatus("Macro saved");
                } else {
                    MacroStorage.save();
                    showStatus("Macros saved");
                }
            }
            case "macro:reload" -> {
                MacroStorage.load();
                selectedMacroIndex = Math.min(selectedMacroIndex, Math.max(0, MacroStorage.getMacros().size() - 1));
                selectedMacroStepIndex = -1;
                updateMacroButtons();
                showStatus("Macros reloaded");
            }
            case "macro:run" -> {
                if (selectedMacroIndex >= 0 && selectedMacroIndex < macros.size() && runner != null) {
                    runner.startMacro(macros.get(selectedMacroIndex));
                    eventLog.task("Started macro: " + macros.get(selectedMacroIndex).getName());
                    showStatus("Macro started");
                }
            }
            case "macro:pause" -> {
                if (runner != null && runner.isRunning()) {
                    if (runner.isPaused()) runner.resume();
                    else runner.pause();
                    showStatus(runner.isPaused() ? "Macro paused" : "Macro resumed");
                }
            }
            case "macro:stop" -> {
                if (runner != null) {
                    runner.stop();
                    showStatus("Macro stopped");
                }
            }
            case "macro:duplicate" -> {
                if (selectedMacroIndex >= 0 && selectedMacroIndex < macros.size()) {
                    MacroChain copy = macros.get(selectedMacroIndex).duplicate(macros.get(selectedMacroIndex).getName() + " Copy");
                    MacroStorage.addMacro(copy);
                    selectedMacroIndex = macros.size() - 1;
                    selectedMacroStepIndex = -1;
                    updateMacroButtons();
                    showStatus("Macro duplicated");
                }
            }
            case "macro:delete" -> {
                if (selectedMacroIndex >= 0 && selectedMacroIndex < macros.size()) {
                    String name = macros.get(selectedMacroIndex).getName();
                    MacroStorage.removeMacro(name);
                    selectedMacroIndex = Math.min(selectedMacroIndex, macros.size() - 1);
                    selectedMacroStepIndex = -1;
                    updateMacroButtons();
                    showStatus("Deleted macro: " + name);
                }
            }
            case "macro:loop" -> {
                if (selectedMacroIndex >= 0 && selectedMacroIndex < macros.size()) {
                    MacroChain macro = macros.get(selectedMacroIndex);
                    macro.setLoop(!macro.isLoop());
                    MacroStorage.save();
                    showStatus("Loop " + (macro.isLoop() ? "enabled" : "disabled"));
                }
            }
            case "macro:add-step" -> addMacroStepFromInput();
            case "macro:remove-step" -> deleteSelectedMacroStep();
            case "macro:step-up" -> moveSelectedMacroStep(-1);
            case "macro:step-down" -> moveSelectedMacroStep(1);
        }
    }

    private void onTabSwitch() {
        commandInput.setVisible(selectedTab == 2);
        commandInput.setEditable(selectedTab == 2);
        commandSearchInput.setVisible(selectedTab == 2);
        commandSearchInput.setEditable(selectedTab == 2);
        macroNameInput.setVisible(selectedTab == 1);
        macroNameInput.setEditable(selectedTab == 1);
        macroStepInput.setVisible(selectedTab == 1);
        macroStepInput.setEditable(selectedTab == 1);
        macroDescInput.setVisible(selectedTab == 1);
        macroDescInput.setEditable(selectedTab == 1);
        logFilterInput.setVisible(selectedTab == 6);
        logFilterInput.setEditable(selectedTab == 6);
        settingsSearchInput.setVisible(selectedTab == 3);
        settingsSearchInput.setEditable(selectedTab == 3);

        if (selectedTab != 3) {
            editingKey = null;
            editingType = null;
            settingsValueInput.setVisible(false);
            settingsValueInput.setEditable(false);
        }
        if (selectedTab == 3) loadSettingsJson(); // refresh from disk
    }

    private void updateMacroButtons() {
        var macros = MacroStorage.getMacros();
        if (selectedMacroIndex >= 0 && selectedMacroIndex < macros.size()) {
            var m = macros.get(selectedMacroIndex);
            macroNameInput.setText(m.getName());
            macroDescInput.setText(m.getDescription() != null ? m.getDescription() : "");
        } else {
            macroNameInput.setText(""); macroDescInput.setText(""); macroStepInput.setText("");
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        if (selectedTab == 1) { scrollOffset = Math.max(0, scrollOffset - (int) v); return true; }
        if (selectedTab == 2) {
            commandScrollOffset = Math.max(0, commandScrollOffset - (int) v);
            selectedCommandIndex = Math.max(commandScrollOffset, selectedCommandIndex);
            return true;
        }
        if (selectedTab == 3) { settingsScrollOffset = Math.max(0, settingsScrollOffset - (int)(v * 16)); return true; }
        if (selectedTab == 4) { shulkerScrollOffset = Math.max(0, shulkerScrollOffset - (int) v); return true; }
        if (selectedTab == 5) { schematicScrollOffset = Math.max(0, schematicScrollOffset - (int) v); return true; }
        if (selectedTab == 6) { logScrollOffset = Math.max(0, logScrollOffset - (int) v); return true; }
        return super.mouseScrolled(mouseX, mouseY, h, v);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            if (editingKey != null) {
                editingKey = null; editingType = null; editingArrayIndex = -1;
                settingsValueInput.setVisible(false); settingsValueInput.setEditable(false);
                return true;
            }
            MinecraftClient.getInstance().setScreen(null);
            return true;
        }

        // Settings value: Enter to save
        if (selectedTab == 3 && settingsValueInput.isFocused() && keyCode == 257 && editingKey != null) {
            String newVal = settingsValueInput.getText().trim();
            if ("array".equals(editingType)) {
                // Parse comma-separated values into JSON array
                JsonArray newArr = new JsonArray();
                for (String s : newVal.split(",")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) newArr.add(trimmed);
                }
                settingsJson.add(editingKey, newArr);
            } else if ("number".equals(editingType)) {
                try {
                    if (newVal.contains(".")) settingsJson.addProperty(editingKey, Double.parseDouble(newVal));
                    else settingsJson.addProperty(editingKey, Integer.parseInt(newVal));
                } catch (NumberFormatException e) {
                    showStatus("Invalid number"); return true;
                }
            } else {
                settingsJson.addProperty(editingKey, newVal);
            }
            saveSettingsJson();
            showStatus(formatKey(editingKey) + " saved");
            eventLog.info("Setting: " + editingKey + " -> " + newVal);
            editingKey = null; editingType = null; editingArrayIndex = -1;
            settingsValueInput.setVisible(false); settingsValueInput.setEditable(false);
            return true;
        }

        // Command input
        if (selectedTab == 2 && commandInput.isFocused() && keyCode == 257) {
            String cmd = commandInput.getText().trim();
            if (!cmd.isEmpty()) {
                mod.getCommandExecutor().executeWithPrefix(cmd);
                eventLog.info("Executed: " + cmd);
                showStatus("Sent: " + cmd);
                commandInput.setText("");
            }
            return true;
        }

        // Macro step
        if (selectedTab == 1) {
            if (keyCode == 257 && macroStepInput.isFocused()) { addMacroStepFromInput(); return true; }
            if (keyCode == 259) { deleteSelectedMacroStep(); return true; }
        }

        // Log filter
        if (selectedTab == 6 && logFilterInput.isFocused()) eventLog.setFilter(logFilterInput.getText());

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void addMacroStepFromInput() {
        if (selectedMacroIndex < 0) {
            showStatus("Select a macro first");
            return;
        }
        var macros = MacroStorage.getMacros();
        if (selectedMacroIndex >= macros.size()) return;
        String cmd = macroStepInput.getText().trim();
        if (cmd.isEmpty()) {
            showStatus("Type a command in the step field first");
            return;
        }
        if (cmd.startsWith(mod.getModSettings().getCommandPrefix())) {
            cmd = cmd.substring(mod.getModSettings().getCommandPrefix().length());
        }
        var macro = macros.get(selectedMacroIndex);
        macro.addStep(new MacroStep(cmd, ""));
        macroStepInput.setText("");
        MacroStorage.save();
        eventLog.task("Added step to '" + macro.getName() + "': " + cmd);
        showStatus("Step added");
    }

    private void deleteSelectedMacroStep() {
        if (selectedMacroIndex < 0 || selectedMacroStepIndex < 0) return;
        var macros = MacroStorage.getMacros();
        if (selectedMacroIndex >= macros.size()) return;
        var macro = macros.get(selectedMacroIndex);
        if (selectedMacroStepIndex < macro.getSteps().size()) {
            macro.removeStep(selectedMacroStepIndex);
            selectedMacroStepIndex = Math.min(selectedMacroStepIndex, macro.getSteps().size() - 1);
            MacroStorage.save();
            showStatus("Step removed");
        }
    }

    private void moveSelectedMacroStep(int direction) {
        if (selectedMacroIndex < 0 || selectedMacroStepIndex < 0) return;
        var macros = MacroStorage.getMacros();
        if (selectedMacroIndex >= macros.size()) return;
        var macro = macros.get(selectedMacroIndex);
        int target = selectedMacroStepIndex + direction;
        if (target < 0 || target >= macro.getSteps().size()) return;
        macro.moveStep(selectedMacroStepIndex, target);
        selectedMacroStepIndex = target;
        MacroStorage.save();
        showStatus("Step moved");
    }

    private void renderButton(DrawContext ctx, int x1, int y1, int x2, int y2, String label,
                              String action, int mouseX, int mouseY, int color) {
        boolean hover = mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y2;
        ctx.fill(x1, y1, x2, y2, hover ? HIGHLIGHT : BG_LIGHT);
        ctx.fill(x1, y2 - 1, x2, y2, color);
        drawCenteredText(ctx, label, (x1 + x2) / 2, y1 + 4, hover ? TEXT_BRIGHT : color);
        if (action != null && action.startsWith("macro:")) {
            macroButtonHitboxes.add(new UiButtonHitbox(x1, y1, x2, y2, action));
        }
    }

    private MacroRunner getMacroRunner() { return mod.getMacroRunner(); }

    private void showStatus(String msg) { statusMessage = msg; statusTime = System.currentTimeMillis(); }
    private void drawText(DrawContext ctx, String text, int x, int y, int color) { ctx.drawTextWithShadow(textRenderer, text, x, y, color); }
    private void drawCenteredText(DrawContext ctx, String text, int x, int y, int color) { int w = textRenderer.getWidth(text); ctx.drawTextWithShadow(textRenderer, text, x - w / 2, y, color); }

    @Override
    public boolean shouldPause() { return false; }
    @Override
    public void close() { editingKey = null; editingType = null; super.close(); }

    private static final class CommandExampleHitbox {
        final int x1;
        final int y1;
        final int x2;
        final int y2;
        final String command;

        CommandExampleHitbox(int x1, int y1, int x2, int y2, String command) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.command = command;
        }

        boolean contains(double x, double y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }
    }

    private static final class UiButtonHitbox {
        final int x1;
        final int y1;
        final int x2;
        final int y2;
        final String action;

        UiButtonHitbox(int x1, int y1, int x2, int y2, String action) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.action = action;
        }

        boolean contains(double x, double y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }
    }
}
