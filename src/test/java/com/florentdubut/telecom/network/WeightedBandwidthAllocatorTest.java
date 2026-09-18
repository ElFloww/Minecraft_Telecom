package com.florentdubut.telecom.network;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WeightedBandwidthAllocatorTest {
    private static BandwidthAllocator.Request request(Object id, int demand, Map<Object, Double> weights) {
        return new BandwidthAllocator.Request(id, demand, weights.keySet(), weights);
    }

    @Test
    void parallelCarriersAreAdditiveAndHandsetsRemainMaxMinFair() {
        var aggregate = request("aggregate", 1000, Map.of("a", .25, "b", .75));
        var allocator = new BandwidthAllocator();
        assertArrayEquals(new int[]{400}, allocator.allocate(List.of(aggregate), Map.of("a", 100, "b", 300)));
        var single = request("single", 1000, Map.of("b", 1.0));
        assertArrayEquals(new int[]{172, 171}, allocator.allocate(List.of(aggregate, single), Map.of("a", 100, "b", 300)));
    }

    @Test
    void uploadAndDownloadShareNormalizedAirtimeWithoutGivingUploadExtraFairnessWeight() {
        assertArrayEquals(new int[]{20, 20}, new BandwidthAllocator().allocate(List.of(
                request("down", 100, Map.of("band", 1.0)),
                request("up", 25, Map.of("band", 4.0))), Map.of("band", 100)));
    }

    @Test
    void wiredBottleneckReleasesWeightedRadioBudgetForAnotherHandset() {
        var first = new BandwidthAllocator.Request("a", 1000, Set.of("wire", "band"), Map.of("band", .5));
        var second = request("b", 1000, Map.of("band", .5));
        assertArrayEquals(new int[]{10, 190}, new BandwidthAllocator().allocate(List.of(first, second),
                Map.of("wire", 10, "band", 100)));
    }

    @Test
    void fractionalWeightedRoundingRotatesWithoutExceedingCapacity() {
        var requests = List.of(request("a", 10, Map.of("band", .4)), request("b", 10, Map.of("band", .4)),
                request("c", 10, Map.of("band", .4)));
        var allocator = new BandwidthAllocator();
        int[] totals = new int[3];
        for (int tick = 0; tick < 300; tick++) {
            int[] grants = allocator.allocate(requests, Map.of("band", 1));
            assertEquals(2, java.util.Arrays.stream(grants).sum());
            for (int i = 0; i < grants.length; i++) totals[i] += grants[i];
        }
        assertArrayEquals(new int[]{200, 200, 200}, totals);
    }

    @Test
    void weightedOverlappingFractionalFlowsDoNotStarve() {
        var requests = List.of(request("a", 1, Map.of("x", .6, "y", .6)),
                request("b", 1, Map.of("x", .6)), request("c", 1, Map.of("x", .6)),
                request("d", 1, Map.of("y", .6)));
        var allocator = new BandwidthAllocator();
        int[] last = new int[4];
        for (int tick = 0; tick < 3000; tick++) {
            int[] grants = allocator.allocate(requests, Map.of("x", 1, "y", 1));
            assertTrue(.6 * (grants[0] + grants[1] + grants[2]) <= 1);
            assertTrue(.6 * (grants[0] + grants[3]) <= 1);
            for (int i = 0; i < grants.length; i++) {
                if (grants[i] > 0) last[i] = tick;
                assertTrue(tick - last[i] < 20, "starved " + i);
            }
        }
    }

    @Test
    void earnedWeightedEntitlementPreemptsFloorsWithoutWaitingForUnrelatedFlows() {
        var contenders = List.of(request("a", 1, Map.of("radio900", 5.0, "radio2100", 10.0 / 3, "wire", 1.0)),
                request("b", 4, Map.of("radio900", 2.5, "radio2100", 5.0)),
                request("c", 1, Map.of("wire", 1.0)));
        Map<Object, Integer> capacities = new LinkedHashMap<>(Map.of("radio900", 10, "radio2100", 20, "wire", 1));
        List<BandwidthAllocator.Request> expanded = new ArrayList<>(contenders);
        for (int i = 0; i < 253; i++) {
            expanded.add(request(i, 1, Map.of(i, 1.0)));
            capacities.put(i, 1);
        }
        var alone = new BandwidthAllocator();
        var withUnrelated = new BandwidthAllocator();
        int[] totals = new int[3];
        int lastA = -1;
        for (int tick = 0; tick < 300; tick++) {
            int[] grants = alone.allocate(contenders, capacities);
            java.util.Collections.rotate(expanded, 1);
            int[] expandedGrants = withUnrelated.allocate(expanded, capacities);
            for (int i = 0; i < expanded.size(); i++) {
                int contender = contenders.indexOf(expanded.get(i));
                assertEquals(contender < 0 ? 1 : grants[contender], expandedGrants[i]);
            }
            assertTrue(5 * grants[0] + 2.5 * grants[1] <= 10);
            assertTrue((10.0 / 3) * grants[0] + 5 * grants[1] <= 20);
            assertEquals(1, grants[0] + grants[2]);
            if (grants[0] > 0) lastA = tick;
            assertTrue(tick - lastA <= 1, "earned half-Mbps entitlement was blocked by an integral floor");
            for (int i = 0; i < grants.length; i++) totals[i] += grants[i];
        }
        assertArrayEquals(new int[]{150, 899, 150}, totals);
    }

    @Test
    void randomizedWeightsRespectEveryBudgetAndDemandAcrossTicks() {
        Random random = new Random(828);
        for (int run = 0; run < 100; run++) {
            Map<Object, Integer> capacities = new LinkedHashMap<>();
            for (int r = 0; r < 8; r++) capacities.put(r, random.nextInt(500));
            List<BandwidthAllocator.Request> requests = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                Map<Object, Double> weights = new LinkedHashMap<>();
                for (int r = 0; r < 8; r++) if (random.nextBoolean()) weights.put(r, (1 + random.nextInt(100)) / 17.0);
                requests.add(request(i, random.nextInt(1000), weights));
            }
            var allocator = new BandwidthAllocator();
            for (int tick = 0; tick < 10; tick++) {
                int[] grants = allocator.allocate(requests, capacities);
                for (int i = 0; i < grants.length; i++) assertTrue(grants[i] >= 0 && grants[i] <= requests.get(i).demand());
                for (Object resource : capacities.keySet()) {
                    double used = 0;
                    for (int i = 0; i < grants.length; i++) used += grants[i] * requests.get(i).resourceWeights().getOrDefault(resource, 0.0);
                    assertTrue(used <= capacities.get(resource) + 1e-7, "capacity exceeded: " + used);
                }
            }
        }
    }

    @Test
    void all256HandsetsCanAggregateMultipleCarriersWithoutExtraRequests() {
        List<BandwidthAllocator.Request> requests = new ArrayList<>();
        for (int i = 0; i < 256; i++) requests.add(request(i, 1_000_000, Map.of("a", .25, "b", .75)));
        assertTimeout(Duration.ofSeconds(5), () -> {
            var allocator = new BandwidthAllocator();
            int[] grants = allocator.allocate(requests, Map.of("a", 250_000, "b", 750_000));
            assertEquals(1_000_000, java.util.Arrays.stream(grants).sum());
            for (int grant : grants) assertTrue(grant == 3906 || grant == 3907);
            requests.add(request(256, 1, Map.of("a", 1.0)));
            assertThrows(IllegalArgumentException.class, () -> allocator.allocate(requests, Map.of("a", 250_000, "b", 750_000)));
        });
    }

    @Test
    void invalidWeightsAreRejectedAndOmittedWeightsStayUnitCost() {
        for (double weight : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> request("a", 1, Map.of("band", weight)));
        }
        assertThrows(IllegalArgumentException.class, () -> new BandwidthAllocator.Request("a", 1, Set.of(), Map.of("band", 1.0)));
        assertEquals(1, new BandwidthAllocator.Request("a", 1, Set.of("wire"), null).weight("wire"));
    }
}
