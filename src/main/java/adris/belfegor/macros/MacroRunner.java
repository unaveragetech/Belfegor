package adris.belfegor.macros;

import adris.belfegor.Belfegor;

import java.util.HashMap;
import java.util.Map;

public class MacroRunner {
    private final Belfegor mod;
    private MacroChain currentMacro;
    private int currentStep;
    private boolean running;
    private boolean paused;
    private final Map<String, String> providedInputs;
    private final Map<String, String> capturedOutputs;
    private MacroChain afterFinish;
    private long stepStartTime;
    private static final long STEP_TIMEOUT_MS = 60000;

    public MacroRunner(Belfegor mod) {
        this.mod = mod;
        this.providedInputs = new HashMap<>();
        this.capturedOutputs = new HashMap<>();
        this.running = false;
        this.paused = false;
    }

    public boolean isRunning() { return running; }
    public boolean isPaused() { return paused; }
    public MacroChain getCurrentMacro() { return currentMacro; }
    public int getCurrentStep() { return currentStep; }
    public Map<String, String> getProvidedInputs() { return providedInputs; }
    public Map<String, String> getCapturedOutputs() { return capturedOutputs; }

    public void startMacro(MacroChain macro, Map<String, String> inputs) {
        stop();
        currentMacro = macro;
        currentStep = 0;
        running = true;
        paused = false;
        providedInputs.clear();
        capturedOutputs.clear();
        if (inputs != null) providedInputs.putAll(inputs);
        stepStartTime = System.currentTimeMillis();
    }

    public void startMacro(MacroChain macro) {
        startMacro(macro, new HashMap<>());
    }

    public void chainMacro(MacroChain next) {
        this.afterFinish = next;
    }

    public void stop() {
        running = false;
        paused = false;
        currentMacro = null;
        currentStep = 0;
        afterFinish = null;
        if (mod.getUserTaskChain() != null) {
            mod.getUserTaskChain().cancel(mod);
        }
    }

    public void pause() { paused = true; }
    public void resume() { paused = false; stepStartTime = System.currentTimeMillis(); }

    public void tick() {
        if (!running || paused || currentMacro == null) return;
        if (mod.getUserTaskChain() == null) return;

        if (mod.getUserTaskChain().isActive()) return;

        if (System.currentTimeMillis() - stepStartTime > STEP_TIMEOUT_MS) {
            mod.logWarning("Macro step timed out, advancing to next step.");
            advanceStep();
            return;
        }

        if (currentStep >= currentMacro.getSteps().size()) {
            if (currentMacro.isLoop()) {
                currentStep = 0;
                return;
            }
            finishMacro();
            return;
        }

        MacroStep step = currentMacro.getSteps().get(currentStep);
        if (!step.isEnabled()) {
            advanceStep();
            return;
        }

        String command = currentMacro.resolveCommand(step, providedInputs);
        stepStartTime = System.currentTimeMillis();
        mod.getCommandExecutor().executeWithPrefix(command);
        advanceStep();
    }

    private void advanceStep() {
        currentStep++;
        if (currentStep >= currentMacro.getSteps().size() && currentMacro.isLoop()) {
            currentStep = 0;
        }
    }

    private void finishMacro() {
        MacroChain finished = currentMacro;
        running = false;
        currentMacro = null;
        currentStep = 0;
        mod.log("Macro '" + finished.getName() + "' completed.");
        if (afterFinish != null) {
            startMacro(afterFinish);
            afterFinish = null;
        }
    }

    public String getStatusString() {
        if (!running || currentMacro == null) return "Not running";
        String step = currentStep < currentMacro.getSteps().size()
                ? currentMacro.getSteps().get(currentStep).toString()
                : "finished";
        return currentMacro.getName() + " [" + (currentStep + 1) + "/" + currentMacro.getSteps().size() + "]"
                + (paused ? " (PAUSED)" : "") + " - " + step;
    }
}
