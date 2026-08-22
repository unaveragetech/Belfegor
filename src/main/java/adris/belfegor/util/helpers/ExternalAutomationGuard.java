package adris.belfegor.util.helpers;

import adris.belfegor.debug.DebugLogger;

import java.lang.reflect.Method;

/**
 * Temporarily suspends optional third-party automation that can compete with
 * Belfegor for the same Minecraft interaction inputs.
 *
 * <p>The integration is reflection-only so Meteor is not a runtime or compile
 * dependency. Leases are reference counted because a full-base task may own a
 * lease while a nested region builder owns another one.</p>
 */
public final class ExternalAutomationGuard {

    private static final String METEOR_MODULES = "meteordevelopment.meteorclient.systems.modules.Modules";
    private static final String LITEMATICA_PRINTER = "litematica-printer";

    private static int _printerLeaseDepth;
    private static boolean _restorePrinter;

    private ExternalAutomationGuard() {
    }

    public static synchronized Lease suspendLitematicaPrinter(String owner) {
        String normalizedOwner = owner == null || owner.isBlank() ? "belfegor-build" : owner;
        if (_printerLeaseDepth++ == 0) {
            _restorePrinter = setMeteorModuleActive(LITEMATICA_PRINTER, false, normalizedOwner);
        }
        DebugLogger.getInstance().log("EXTERNAL-AUTOMATION",
                "lease-acquire owner=" + normalizedOwner
                        + " depth=" + _printerLeaseDepth
                        + " restore=" + _restorePrinter);
        return new Lease(normalizedOwner);
    }

    private static synchronized void release(String owner) {
        if (_printerLeaseDepth <= 0) return;
        _printerLeaseDepth--;
        DebugLogger.getInstance().log("EXTERNAL-AUTOMATION",
                "lease-release owner=" + owner
                        + " depth=" + _printerLeaseDepth
                        + " restore=" + _restorePrinter);
        if (_printerLeaseDepth == 0 && _restorePrinter) {
            setMeteorModuleActive(LITEMATICA_PRINTER, true, owner);
            _restorePrinter = false;
        }
    }

    /**
     * @return true only when the guard changed an active module to inactive.
     */
    private static boolean setMeteorModuleActive(String moduleName, boolean active, String owner) {
        try {
            Class<?> modulesClass = Class.forName(METEOR_MODULES);
            Method getSystems = modulesClass.getMethod("get");
            Object modules = getSystems.invoke(null);
            if (modules == null) return false;

            Method getModule = modulesClass.getMethod("get", String.class);
            Object module = getModule.invoke(modules, moduleName);
            if (module == null) {
                DebugLogger.getInstance().log("EXTERNAL-AUTOMATION",
                        "module-not-found name=" + moduleName + " owner=" + owner);
                return false;
            }

            Method isActive = module.getClass().getMethod("isActive");
            boolean wasActive = Boolean.TRUE.equals(isActive.invoke(module));
            if (wasActive != active) {
                Method toggle = module.getClass().getMethod("toggle");
                toggle.invoke(module);
            }
            boolean nowActive = Boolean.TRUE.equals(isActive.invoke(module));
            DebugLogger.getInstance().logImmediate("EXTERNAL-AUTOMATION",
                    "module=" + moduleName
                            + " owner=" + owner
                            + " requestedActive=" + active
                            + " before=" + wasActive
                            + " after=" + nowActive);
            return wasActive && !active && !nowActive;
        } catch (ClassNotFoundException ignored) {
            // Meteor is optional. Absence is the normal case for clean installs.
            return false;
        } catch (ReflectiveOperationException | LinkageError error) {
            DebugLogger.getInstance().logImmediate("EXTERNAL-AUTOMATION",
                    "guard-failed module=" + moduleName
                            + " owner=" + owner
                            + " error=" + error.getClass().getSimpleName()
                            + ":" + error.getMessage());
            return false;
        }
    }

    public static final class Lease implements AutoCloseable {
        private final String _owner;
        private boolean _closed;

        private Lease(String owner) {
            _owner = owner;
        }

        @Override
        public void close() {
            synchronized (ExternalAutomationGuard.class) {
                if (_closed) return;
                _closed = true;
            }
            ExternalAutomationGuard.release(_owner);
        }
    }
}
