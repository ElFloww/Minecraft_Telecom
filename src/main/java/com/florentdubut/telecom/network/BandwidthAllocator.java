package com.florentdubut.telecom.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Max-min filling of shared resources, followed by capacity-safe integer rounding. */
final class BandwidthAllocator {
    private static final double EPSILON = 1e-7;
    private static final double MAX_DEFICIT = 256;
    private final Map<Object, Credit> credits = new LinkedHashMap<>();
    private long serviceOrder;

    private static class Credit {
        double deficit;
        long lastServed = -1;
    }

    record Request(Object identity, int demand, Set<Object> resources, Map<Object, Double> resourceWeights) {
        Request(Object identity, int demand, Set<Object> resources) {
            this(identity, demand, resources, Map.of());
        }

        Request {
            resources = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(resources));
            resourceWeights = resourceWeights == null ? Map.of() : Map.copyOf(resourceWeights);
            for (var entry : resourceWeights.entrySet()) {
                if (!resources.contains(entry.getKey()) || !Double.isFinite(entry.getValue()) || entry.getValue() <= 0) {
                    throw new IllegalArgumentException("invalid resource weight");
                }
            }
        }

        double weight(Object resource) { return resourceWeights.getOrDefault(resource, 1.0); }
    }

    void forget(Object identity) {
        credits.remove(identity);
    }

    int[] allocate(List<Request> requests, Map<Object, Integer> capacities) {
        int count = requests.size();
        if (count > 256) throw new IllegalArgumentException("too many bandwidth requests");
        Set<Object> identities = new java.util.HashSet<>();
        for (Request request : requests) {
            if (!identities.add(request.identity)) throw new IllegalArgumentException("duplicate bandwidth session");
        }
        credits.keySet().retainAll(identities);
        int[] result = new int[count];
        if (count == 0) return result;

        Map<Object, Integer> indices = new LinkedHashMap<>();
        List<int[]> resources = new ArrayList<>();
        List<double[]> weights = new ArrayList<>();
        for (Request request : requests) {
            if (request.demand < 0 || request.demand > 1_000_000) {
                throw new IllegalArgumentException("invalid bandwidth demand");
            }
            resources.add(request.resources.stream()
                    .mapToInt(key -> indices.computeIfAbsent(key, ignored -> indices.size())).toArray());
            weights.add(request.resources.stream().mapToDouble(request::weight).toArray());
        }
        double[] remaining = new double[indices.size()];
        double[] integerRemaining = new double[indices.size()];
        indices.forEach((key, index) -> {
            int capacity = capacities.get(key);
            if (capacity < 0 || capacity > 1_000_000) throw new IllegalArgumentException("invalid resource capacity");
            remaining[index] = capacity;
            integerRemaining[index] = capacity;
        });
        double[] users = new double[indices.size()];
        boolean[] active = new boolean[count];
        double[] shares = new double[count];
        int activeCount = 0;
        for (int i = 0; i < count; i++) {
            if (requests.get(i).demand > 0) {
                active[i] = true;
                activeCount++;
            }
        }

        // Each round freezes at least one flow, independent of the Mbps magnitudes.
        while (activeCount > 0) {
            // Recompute rather than subtract weights: cancellation must not leave phantom users.
            Arrays.fill(users, 0);
            for (int i = 0; i < count; i++) {
                if (!active[i]) continue;
                for (int j = 0; j < resources.get(i).length; j++) {
                    int resource = resources.get(i)[j];
                    users[resource] += weights.get(i)[j];
                    if (!Double.isFinite(users[resource])) throw new IllegalArgumentException("resource weight overflow");
                }
            }
            double step = Double.POSITIVE_INFINITY;
            for (int i = 0; i < count; i++) {
                if (active[i]) step = Math.min(step, requests.get(i).demand - shares[i]);
            }
            for (int r = 0; r < remaining.length; r++) {
                if (users[r] > 0) step = Math.min(step, Math.max(0, remaining[r]) / users[r]);
            }
            for (int i = 0; i < count; i++) {
                if (active[i]) shares[i] += step;
            }
            for (int r = 0; r < remaining.length; r++) remaining[r] -= step * users[r];
            for (int i = 0; i < count; i++) {
                if (!active[i]) continue;
                boolean frozen = requests.get(i).demand - shares[i] <= EPSILON;
                for (int resource : resources.get(i)) frozen |= remaining[resource] <= EPSILON;
                if (frozen) {
                    active[i] = false;
                    activeCount--;
                }
            }
        }

        Integer[] order = new Integer[count];
        Credit[] balances = new Credit[count];
        int[] floors = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
            floors[i] = Math.min(requests.get(i).demand, (int) Math.floor(shares[i] + EPSILON));
            Credit credit = credits.computeIfAbsent(requests.get(i).identity, ignored -> new Credit());
            balances[i] = credit;
            credit.deficit = Math.clamp(credit.deficit + shares[i] - floors[i], -MAX_DEFICIT, MAX_DEFICIT);
        }
        java.util.Comparator<Integer> priority = (a, b) -> {
            int comparison = Long.compare(Math.round(balances[b].deficit / EPSILON),
                    Math.round(balances[a].deficit / EPSILON));
            // Only residual service changes priority; unrelated integral flows cannot skew a tie.
            return comparison != 0 ? comparison : Long.compare(balances[a].lastServed, balances[b].lastServed);
        };
        Arrays.sort(order, priority);
        // Reserve genuinely earned rounding entitlement BEFORE integral floors can block it.
        // Also repay displaced floors when demand permits: e.g. (.5, 3, .5) alternates
        // (1, 2, 0) and (0, 4, 1), independently of any unrelated flows in the request list.
        for (int i : order) {
            if (balances[i].deficit < 1 - EPSILON || floors[i] >= requests.get(i).demand || shares[i] <= EPSILON) continue;
            int reserved = floors[i] + 1;
            boolean fits = true;
            for (int j = 0; j < resources.get(i).length; j++) {
                fits &= integerRemaining[resources.get(i)[j]] >= reserved * weights.get(i)[j];
            }
            if (!fits) continue;
            result[i] = reserved;
            balances[i].lastServed = serviceOrder++;
            for (int j = 0; j < resources.get(i).length; j++) integerRemaining[resources.get(i)[j]] -= reserved * weights.get(i)[j];
        }
        for (int i = 0; i < count; i++) {
            int floor = Math.max(0, floors[i] - result[i]);
            for (int j = 0; j < resources.get(i).length; j++) {
                floor = Math.min(floor, Math.max(0, (int) Math.floor(
                        integerRemaining[resources.get(i)[j]] / weights.get(i)[j])));
            }
            result[i] += floor;
            for (int j = 0; j < resources.get(i).length; j++) integerRemaining[resources.get(i)[j]] -= floor * weights.get(i)[j];
            // Retain debt for displaced floors, but bound debt from integer-infeasible fluid vectors.
            balances[i].deficit = Math.clamp(balances[i].deficit + floors[i] - result[i], -MAX_DEFICIT, MAX_DEFICIT);
        }
        Arrays.sort(order, priority);
        for (int i : order) {
            if (result[i] >= requests.get(i).demand
                    || (shares[i] - result[i] <= EPSILON && balances[i].deficit < 1 - EPSILON)) continue;
            boolean fits = true;
            for (int j = 0; j < resources.get(i).length; j++) {
                fits &= integerRemaining[resources.get(i)[j]] >= weights.get(i)[j];
            }
            if (fits) {
                result[i]++;
                balances[i].deficit -= 1;
                balances[i].lastServed = serviceOrder++;
                for (int j = 0; j < resources.get(i).length; j++) integerRemaining[resources.get(i)[j]] -= weights.get(i)[j];
            }
        }
        return result;
    }
}
