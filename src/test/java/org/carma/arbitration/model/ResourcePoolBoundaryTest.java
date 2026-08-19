package org.carma.arbitration.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePoolBoundaryTest {

    @Test
    void negativeAllocateRejected() {
        ResourcePool pool = ResourcePool.ofSingle(ResourceType.COMPUTE, 100);
        assertThrows(IllegalArgumentException.class,
            () -> pool.allocate(ResourceType.COMPUTE, -10));
        assertEquals(100, pool.getAvailable(ResourceType.COMPUTE));
    }

    @Test
    void negativeReleaseRejected() {
        ResourcePool pool = ResourcePool.ofSingle(ResourceType.COMPUTE, 100);
        pool.allocate(ResourceType.COMPUTE, 40);
        assertThrows(IllegalArgumentException.class,
            () -> pool.release(ResourceType.COMPUTE, -5));
        assertEquals(60, pool.getAvailable(ResourceType.COMPUTE));
    }

    @Test
    void validAllocateAndRelease() {
        ResourcePool pool = ResourcePool.ofSingle(ResourceType.COMPUTE, 100);
        pool.allocate(ResourceType.COMPUTE, 30);
        assertEquals(70, pool.getAvailable(ResourceType.COMPUTE));
        pool.release(ResourceType.COMPUTE, 10);
        assertEquals(80, pool.getAvailable(ResourceType.COMPUTE));
    }
}
