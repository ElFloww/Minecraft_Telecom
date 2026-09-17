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

    record Request(Object identity, int demand, Set<Object> resources) {}

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
        for (Request request : requests) {
            if (request.demand < 0 || request.demand > 1_000_000) {
                throw new IllegalArgumentException("invalid bandwidth demand");
            }
            resources.add(request.resources.stream()
                    .mapToInt(key -> indices.computeIfAbsent(key, ignored -> indices.size())).toArray());
        }
        double[] remaining = new double[indices.size()];
        int[] integerRemaining = new int[indices.size()];
        indices.forEach((key, index) -> {
            int capacity = capacities.get(key);
            if (capacity < 0 || capacity > 1_000_000) throw new IllegalArgumentException("invalid resource capacity");
            remaining[index] = capacity;
            integerRemaining[index] = capacity;
        });
        int[] users = new int[indices.size()];
        boolean[] active = new boolean[count];
        double[] shares = new double[count];
        int activeCount = 0;
        for (int i = 0; i < count; i++) {
            if (requests.get(i).demand > 0) {
                active[i] = true;
                activeCount++;
                for (int resource : resources.get(i)) users[resource]++;
            }
        }

        // Each round freezes at least one flow, independent of the Mbps magnitudes.
        while (activeCount > 0) {
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
                    for (int resource : resources.get(i)) users[resource]--;
                }
            }
        }

        for (int i = 0; i < count; i++) {
            int floor = Math.min(requests.get(i).demand, (int) Math.floor(shares[i] + EPSILON));
            for (int resource : resources.get(i)) floor = Math.min(floor, integerRemaining[resource]);
            result[i] = floor;
            for (int resource : resources.get(i)) integerRemaining[resource] -= floor;
        }
        Integer[] order = new Integer[count];
        Credit[] balances = new Credit[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
            Credit credit = credits.computeIfAbsent(requests.get(i).identity, ignored -> new Credit());
            balances[i] = credit;
            double fraction = shares[i] - result[i];
            // Infeasible fractional vectors (e.g. a unit-capacity triangle) must not accumulate unbounded debt.
            credit.deficit = fraction <= EPSILON ? 0 : Math.clamp(credit.deficit + fraction, -MAX_DEFICIT, MAX_DEFICIT);
        }
        Arrays.sort(order, (a, b) -> {
            int comparison = Long.compare(Math.round(balances[b].deficit / EPSILON),
                    Math.round(balances[a].deficit / EPSILON));
            // Only residual service changes priority; unrelated integral flows cannot skew a tie.
            return comparison != 0 ? comparison : Long.compare(balances[a].lastServed, balances[b].lastServed);
        });
        for (int i : order) {
            if (result[i] >= requests.get(i).demand || shares[i] - result[i] <= EPSILON) continue;
            boolean fits = true;
            for (int resource : resources.get(i)) fits &= integerRemaining[resource] > 0;
            if (fits) {
                result[i]++;
                balances[i].deficit -= 1;
                balances[i].lastServed = serviceOrder++;
                for (int resource : resources.get(i)) integerRemaining[resource]--;
            }
        }
        return result;
    }
}
