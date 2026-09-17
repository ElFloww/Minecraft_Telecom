package com.florentdubut.telecom.network;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BandwidthAllocatorTest {
    @Test
    void redistributesAfterDemandOrAnotherResourceSaturates() {
        assertArrayEquals(new int[]{10, 90}, new BandwidthAllocator().allocate(List.of(
                request(10, "trunk"), request(90, "trunk")), Map.of("trunk", 100)));
        assertArrayEquals(new int[]{10, 90}, new BandwidthAllocator().allocate(List.of(
                request(100, "branch", "trunk"), request(100, "trunk")), Map.of("branch", 10, "trunk", 100)));
        assertArrayEquals(new int[]{10, 45, 45}, new BandwidthAllocator().allocate(List.of(
                request(100, "branch", "trunk"), request(100, "trunk"), request(100, "trunk")),
                Map.of("branch", 10, "trunk", 100)));
    }

    @Test
    void oneMegabitIsUsedAndTiesRotateAtEachTick() {
        BandwidthAllocator allocator = new BandwidthAllocator();
        assertArrayEquals(new int[]{1}, allocator.allocate(List.of(request(1, "link")), Map.of("link", 1)));
        List<BandwidthAllocator.Request> pair = List.of(request(1, "link"), request(1, "link"));
        for (int tick = 0; tick < 100; tick++) {
            assertArrayEquals(tick % 2 == 0 ? new int[]{1, 0} : new int[]{0, 1},
                    allocator.allocate(pair, Map.of("link", 1)));
        }
    }

    @Test
    void maximumDemandsAndSessionsDoNotRequirePerMegabitIteration() {
        List<BandwidthAllocator.Request> requests = new ArrayList<>();
        for (int i = 0; i < 256; i++) requests.add(request(1_000_000, "trunk"));
        assertTimeout(Duration.ofSeconds(5), () -> {
            int[] grants = new BandwidthAllocator().allocate(requests, Map.of("trunk", 1_000_000));
            assertEquals(1_000_000, java.util.Arrays.stream(grants).sum());
            for (int grant : grants) assertTrue(grant == 3906 || grant == 3907);
        });
    }

    @Test
    void randomizedOverlappingResourcesAlwaysRespectAllBudgetsAndDemands() {
        Random random = new Random(45201);
        for (int run = 0; run < 200; run++) {
            Map<Object, Integer> capacities = new java.util.LinkedHashMap<>();
            for (int r = 0; r < 12; r++) capacities.put(r, random.nextInt(1_000_001));
            List<BandwidthAllocator.Request> requests = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                Set<Object> resources = new LinkedHashSet<>();
                for (int r = 0; r < 12; r++) if (random.nextBoolean()) resources.add(r);
                requests.add(new BandwidthAllocator.Request(i, random.nextInt(1_000_001), resources));
            }
            int[] actual = new BandwidthAllocator().allocate(requests, capacities);
            assertArrayEquals(actual, new BandwidthAllocator().allocate(requests, capacities));
            for (int i = 0; i < actual.length; i++) {
                assertTrue(actual[i] >= 0 && actual[i] <= requests.get(i).demand());
            }
            for (Object resource : capacities.keySet()) {
                int used = 0;
                for (int i = 0; i < actual.length; i++) {
                    if (requests.get(i).resources().contains(resource)) used += actual[i];
                }
                assertTrue(used <= capacities.get(resource));
            }
        }
    }

    private static BandwidthAllocator.Request request(int demand, Object... resources) {
        return new BandwidthAllocator.Request(new Object(), demand, new LinkedHashSet<>(List.of(resources)));
    }

    @Test
    void differentFractionsAcrossOverlappingResourcesDoNotStarveMultiResourceSession() {
        BandwidthAllocator allocator = new BandwidthAllocator();
        List<BandwidthAllocator.Request> requests = List.of(request(1, "x", "y"), request(1, "x"),
                request(1, "x"), request(1, "y"));
        int[] totals = new int[4];
        int[] lastService = new int[4];
        for (int tick = 0; tick < 3000; tick++) {
            int[] grants = allocator.allocate(requests, Map.of("x", 1, "y", 1));
            assertTrue(grants[0] + grants[1] + grants[2] <= 1);
            assertTrue(grants[0] + grants[3] <= 1);
            for (int i = 0; i < 4; i++) {
                totals[i] += grants[i];
                if (grants[i] > 0) lastService[i] = tick;
                assertTrue(tick - lastService[i] < 10, "session " + i + " starved");
            }
        }
        assertArrayEquals(new int[]{1000, 1000, 1000, 2000}, totals);
    }

    @Test
    void integralUnrelatedFlowsDoNotSkewContenderRotationEvenIfRequestOrderChanges() {
        BandwidthAllocator allocator = new BandwidthAllocator();
        List<BandwidthAllocator.Request> requests = new ArrayList<>();
        requests.add(request(1, "shared"));
        requests.add(request(1, "shared"));
        Object first = requests.get(0).identity();
        Object second = requests.get(1).identity();
        Map<Object, Integer> capacities = new java.util.HashMap<>();
        capacities.put("shared", 1);
        for (int i = 0; i < 254; i++) {
            requests.add(request(1, i));
            capacities.put(i, 1);
        }
        Map<Object, Integer> totals = new java.util.HashMap<>();
        for (int tick = 0; tick < 256; tick++) {
            java.util.Collections.rotate(requests, 1);
            int[] grants = allocator.allocate(requests, capacities);
            assertEquals(255, java.util.Arrays.stream(grants).sum());
            for (int i = 0; i < requests.size(); i++) totals.merge(requests.get(i).identity(), grants[i], Integer::sum);
            assertTrue(Math.abs(totals.getOrDefault(first, 0) - totals.getOrDefault(second, 0)) <= 1);
        }
        assertEquals(128, totals.get(first));
        assertEquals(128, totals.get(second));
    }

    @Test
    void infeasibleFractionalTriangleHasBoundedDebtFairServiceAndSessionCleanup() throws ReflectiveOperationException {
        BandwidthAllocator allocator = new BandwidthAllocator();
        List<BandwidthAllocator.Request> requests = List.of(request(1, "x", "y"), request(1, "x", "z"), request(1, "y", "z"));
        int[] totals = new int[3];
        var creditsField = BandwidthAllocator.class.getDeclaredField("credits");
        creditsField.setAccessible(true);
        Map<?, ?> credits = (Map<?, ?>) creditsField.get(allocator);
        for (int tick = 0; tick < 3000; tick++) {
            int[] grants = allocator.allocate(requests, Map.of("x", 1, "y", 1, "z", 1));
            assertEquals(1, java.util.Arrays.stream(grants).sum());
            for (int i = 0; i < 3; i++) totals[i] += grants[i];
            for (Object credit : credits.values()) {
                var deficitField = credit.getClass().getDeclaredField("deficit");
                deficitField.setAccessible(true);
                assertTrue(Math.abs(deficitField.getDouble(credit)) <= 256);
            }
        }
        assertArrayEquals(new int[]{1000, 1000, 1000}, totals);
        allocator.forget(requests.getFirst().identity());
        assertEquals(2, credits.size());
        allocator.allocate(List.of(), Map.of());
        assertTrue(credits.isEmpty());
        for (int i = 0; i < 3000; i++) {
            allocator.allocate(List.of(request(1, "x")), Map.of("x", 1));
            assertEquals(1, credits.size());
        }
    }
}
