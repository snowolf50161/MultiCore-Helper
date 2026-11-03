package com.example.forcedmulticore;

import com.google.gson.JsonArray;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClientUpdateCheckerTest {
    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = ClientUpdateChecker.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    @Test public void comparesReleaseVersionsNumericallyRatherThanLexically() throws Exception {
        Class<?>[] types = {String.class, String.class};
        assertEquals(1, call("compareVersions", types, "1.0.10", "1.0.9"));
        assertEquals(-1, call("compareVersions", types, "1.0.0", "1.0.1"));
        assertEquals(0, call("compareVersions", types, "1.19.4-1.0.0", "1.0.0"));
    }

    @Test public void loaderMatchingDoesNotAdvertiseFabricReleasesToForge() throws Exception {
        JsonArray values = new JsonArray();
        values.add("Fabric"); values.add("1.19.4");
        Class<?>[] types = {JsonArray.class, String.class};
        assertEquals(false, call("containsLoader", types, values, "Forge"));
        values.add("Forge");
        assertEquals(true, call("containsLoader", types, values, "forge"));
        assertEquals(false, call("containsLoader", types, null, "Forge"));
    }
}
