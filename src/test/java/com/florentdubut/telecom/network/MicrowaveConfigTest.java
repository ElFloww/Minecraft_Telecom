package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MicrowaveConfigTest {
    @Test
    void strictConstructorAndProfiles() {
        int[] frequencies = {6, 11, 18, 38}, capacities = {300, 600, 1000, 2000};
        for (int i = 0; i < frequencies.length; i++) {
            assertEquals(capacities[i], new MicrowaveConfig(null, 16, frequencies[i], 359, -90, true).nominalCapacityMbps());
        }
        for (int channel : new int[]{0, 17}) assertThrows(IllegalArgumentException.class,
                () -> new MicrowaveConfig(null, channel, 11, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new MicrowaveConfig(null, 1, 7, 0, 0, false));
        for (int azimuth : new int[]{-1, 360}) assertThrows(IllegalArgumentException.class,
                () -> new MicrowaveConfig(null, 1, 11, azimuth, 0, false));
        for (int elevation : new int[]{-91, 91}) assertThrows(IllegalArgumentException.class,
                () -> new MicrowaveConfig(null, 1, 11, 0, elevation, false));
    }

    @Test
    void persistenceDefensivelyDefaultsAndClearsPeer() {
        var mutable = new BlockPos.MutableBlockPos(12, 80, -22);
        var config = new MicrowaveConfig(mutable, 3, 18, 90, 30, true);
        mutable.set(0, 0, 0);
        assertEquals(new BlockPos(12, 80, -22), config.peer());
        CompoundTag tag = new CompoundTag();
        assertEquals(MicrowaveConfig.DEFAULT, MicrowaveConfig.read(tag));
        config.writeTo(tag);
        assertEquals(config, MicrowaveConfig.read(tag));
        tag.putInt("MicrowaveChannel", 17);
        assertEquals(MicrowaveConfig.DEFAULT, MicrowaveConfig.read(tag));
        MicrowaveConfig.DEFAULT.writeTo(tag);
        assertEquals(MicrowaveConfig.DEFAULT, MicrowaveConfig.read(tag));
        ValueInput input = mock(ValueInput.class);
        when(input.getIntOr(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(input.getBooleanOr(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        assertEquals(MicrowaveConfig.DEFAULT, MicrowaveConfig.read(input));
        ValueOutput output = mock(ValueOutput.class);
        config.writeTo(output);
        verify(output).putInt("MicrowaveFrequency", 18);
        verify(output).putBoolean("MicrowaveEnabled", true);
    }

    @Test
    void valueStorageRoundTripAndIncompletePeerAreDefensive() {
        CompoundTag tag = new CompoundTag();
        ValueOutput output = mock(ValueOutput.class);
        doAnswer(i -> { tag.putInt(i.getArgument(0), i.getArgument(1)); return null; })
                .when(output).putInt(anyString(), anyInt());
        doAnswer(i -> { tag.putBoolean(i.getArgument(0), i.getArgument(1)); return null; })
                .when(output).putBoolean(anyString(), anyBoolean());
        ValueInput input = mock(ValueInput.class);
        when(input.getIntOr(anyString(), anyInt())).thenAnswer(i -> tag.getIntOr(i.getArgument(0), i.getArgument(1)));
        when(input.getBooleanOr(anyString(), anyBoolean())).thenAnswer(i -> tag.getBooleanOr(i.getArgument(0), i.getArgument(1)));
        when(input.getInt(anyString())).thenAnswer(i -> tag.getInt(i.getArgument(0)));
        var config = new MicrowaveConfig(new BlockPos(-100, 300, 80), 16, 38, 359, 90, true);
        config.writeTo(output);
        assertEquals(config, MicrowaveConfig.read(input));
        tag.remove("MicrowavePeerX");
        assertEquals(MicrowaveConfig.DEFAULT, MicrowaveConfig.read(input));
        assertEquals(MicrowaveConfig.DEFAULT, MicrowaveConfig.read(tag));
        MicrowaveConfig.DEFAULT.writeTo(output);
        assertEquals(MicrowaveConfig.DEFAULT, MicrowaveConfig.read(input));
    }

    @Test
    void invalidPersistedCoordinatesCannotBreakGuiSerialization() {
        CompoundTag tag = new CompoundTag();
        ValueInput input = mock(ValueInput.class);
        when(input.getIntOr(anyString(), anyInt())).thenAnswer(i -> tag.getIntOr(i.getArgument(0), i.getArgument(1)));
        when(input.getBooleanOr(anyString(), anyBoolean())).thenAnswer(i -> tag.getBooleanOr(i.getArgument(0), i.getArgument(1)));
        when(input.getInt(anyString())).thenAnswer(i -> tag.getInt(i.getArgument(0)));
        for (BlockPos peer : new BlockPos[]{new BlockPos(30_000_000, 64, 0), new BlockPos(-30_000_000, 64, 0),
                new BlockPos(0, 64, Integer.MIN_VALUE), new BlockPos(0, -2049, 0), new BlockPos(0, 2048, 0)}) {
            new MicrowaveConfig(peer, 1, 11, 0, 0, true).writeTo(tag);
            assertEquals(MicrowaveConfig.DEFAULT, MicrowaveConfig.read(tag));
            assertEquals(MicrowaveConfig.DEFAULT, MicrowaveConfig.read(input));
            assertTrue(com.florentdubut.telecom.network.packet.MicrowaveConfigPayload.validConfig(MicrowaveConfig.read(tag)));
        }
    }
}
