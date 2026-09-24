package cc.nitea.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code Map.of} for Java 8: an unmodifiable map keeping the given order. */
public final class Maps {
    private Maps() {}

    public static <V> Map<String, V> of() {
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static <V> Map<String, V> of(Object... keysAndValues) {
        Map<String, V> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) map.put((String) keysAndValues[i], (V) keysAndValues[i + 1]);
        return Collections.unmodifiableMap(map);
    }
}
