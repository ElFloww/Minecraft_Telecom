package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CoveragePerformanceTest {
    @Test
    void aBlockByBlockGridReadsTerrainOnceAcrossTechnologies() {
        var frequencies = Arrays.asList(TelecomFrequency.values());
        BlockPos source = new BlockPos(0, 80, 0);
        AtomicLong separateReads = new AtomicLong();
        AtomicLong sharedReads = new AtomicLong();
        SignalPropagator.TerrainSampler separate = position -> {
            separateReads.incrementAndGet();
            return SignalPropagator.Material.AIR;
        };
        SignalPropagator.TerrainSampler shared = position -> {
            sharedReads.incrementAndGet();
            return SignalPropagator.Material.AIR;
        };
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                BlockPos receiver = new BlockPos(64 + x, 66, 64 + z);
                for (TelecomFrequency frequency : frequencies) SignalPropagator.calculateSignal(separate, source, receiver, frequency);
                var trace = new SignalPropagator.MultiTrace(source, receiver, frequencies);
                while (!trace.advance(shared, 64, Long.MAX_VALUE)) { }
            }
        }
        assertTrue(sharedReads.get() * 4 < separateReads.get(), "A fine grid must share terrain reads between bands");
        System.out.printf(java.util.Locale.ROOT,
                "16x16 block grid, %d bands: separate=%d probes, shared=%d probes, reduction=%.2fx%n",
                frequencies.size(), separateReads.get(), sharedReads.get(), (double) separateReads.get() / sharedReads.get());
    }
}
