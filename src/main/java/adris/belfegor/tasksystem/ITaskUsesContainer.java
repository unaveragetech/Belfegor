package adris.belfegor.tasksystem;

/**
 * Marker for tasks that intentionally own a handled container screen
 * (chest, furnace, smoker, blast furnace, shulker, etc.).
 *
 * Global cursor/screen repair chains must not close or "recover" these screens
 * while a marked task is active, otherwise long transfers and smelting jobs can
 * be interrupted mid-click and leave the cursor or handler in a bad state.
 */
public interface ITaskUsesContainer {
}
