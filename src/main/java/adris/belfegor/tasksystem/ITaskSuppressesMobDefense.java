package adris.belfegor.tasksystem;

/**
 * Marker for controlled developer/test harnesses that must not be interrupted
 * by combat avoidance. These tasks intentionally mutate inventory/screens/world
 * fixtures and should fail from their own audit timeout/logging, not from a
 * survival chain stealing the task lane.
 */
public interface ITaskSuppressesMobDefense {
}
