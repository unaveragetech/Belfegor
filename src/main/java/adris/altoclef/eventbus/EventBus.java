package adris.altoclef.eventbus;

import net.minecraft.util.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * A static class to solve dependency issues. Lets us send and receive events globally, decoupling our codebase.
 * <p>
 * Technically `ConfigHelper` does something like this, but here is a more general case.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class EventBus {

    private static final HashMap<Class, List<Subscription>> _topics = new HashMap<>();
    private static final List<Pair<Class, Subscription>> _toAdd = new ArrayList<>();
    private static final List<Subscription> _toDelete = new ArrayList<>();
    private static boolean _lock;

    public static <T> void publish(T event) {
        Class type = event.getClass();

        if (!_toAdd.isEmpty()) {
            for (int i = 0, size = _toAdd.size(); i < size; i++) {
                Pair<Class, Subscription> pair = _toAdd.get(i);
                subscribeInternal(pair.getLeft(), pair.getRight());
            }
            _toAdd.clear();
        }

        List<Subscription> subscribers = _topics.get(type);
        if (subscribers == null) return;

        _lock = true;
        for (int i = 0, size = subscribers.size(); i < size; i++) {
            Subscription<T> sub;
            try {
                sub = (Subscription<T>) subscribers.get(i);
                if (sub.shouldDelete()) {
                    _toDelete.add(sub);
                } else {
                    sub.accept(event);
                }
            } catch (ClassCastException e) {
                System.err.println("TRIED PUBLISHING MISMAPPED EVENT: " + event);
                e.printStackTrace();
            }
        }
        if (!_toDelete.isEmpty()) {
            subscribers.removeAll(_toDelete);
            _toDelete.clear();
        }
        _lock = false;
    }

    private static <T> void subscribeInternal(Class<T> type, Subscription<T> sub) {
        if (!_topics.containsKey(type)) {
            _topics.put(type, new ArrayList<>());
        }
        _topics.get(type).add(sub);
    }

    public static <T> Subscription<T> subscribe(Class<T> type, Consumer<T> consumeEvent) {
        Subscription<T> sub = new Subscription<>(consumeEvent);
        if (_lock) {
            _toAdd.add(new Pair<>(type, sub));
        } else {
            subscribeInternal(type, sub);
        }
        return sub;
    }

    public static <T> void unsubscribe(Subscription<T> subscription) {
        if (subscription != null)
            subscription.delete();
    }
}
