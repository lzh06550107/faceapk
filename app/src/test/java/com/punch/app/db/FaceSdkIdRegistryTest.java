package com.punch.app.db;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class FaceSdkIdRegistryTest {
    @Test
    public void collidingEmployeeIdsReceiveDifferentSdkIds() {
        MemoryStore store = new MemoryStore();
        FaceSdkIdRegistry registry = new FaceSdkIdRegistry(store);

        int numeric = registry.getOrCreate("1");
        int numericWithZero = registry.getOrCreate("01");
        int hashCollisionA = registry.getOrCreate("Aa");
        int hashCollisionB = registry.getOrCreate("BB");

        assertNotEquals(numeric, numericWithZero);
        assertNotEquals(hashCollisionA, hashCollisionB);
    }

    @Test
    public void mappingRemainsStableAcrossRegistryInstances() {
        MemoryStore store = new MemoryStore();
        int first = new FaceSdkIdRegistry(store).getOrCreate("employee-7");
        int afterRestart = new FaceSdkIdRegistry(store).getOrCreate("employee-7");

        assertEquals(first, afterRestart);
    }

    private static final class MemoryStore implements FaceSdkIdRegistry.Store {
        private final Map<String, Integer> values = new HashMap<>();

        @Override
        public Integer find(String employeeId) {
            return values.get(employeeId);
        }

        @Override
        public int maxId() {
            int max = 0;
            for (int value : values.values()) {
                max = Math.max(max, value);
            }
            return max;
        }

        @Override
        public boolean insert(String employeeId, int sdkId) {
            if (values.containsKey(employeeId) || values.containsValue(sdkId)) {
                return false;
            }
            values.put(employeeId, sdkId);
            return true;
        }
    }
}
