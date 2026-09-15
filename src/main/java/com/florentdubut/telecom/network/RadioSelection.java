package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;

/** One deterministic serving-cell order for the handset and the predicted map. */
public final class RadioSelection {
    private RadioSelection() { }

    public static int compare(TelecomFrequency frequency, float power, BlockPos position,
                              TelecomFrequency otherFrequency, float otherPower, BlockPos otherPosition) {
        int technology = frequency.getTechnology().compareTo(otherFrequency.getTechnology());
        if (technology != 0) return technology;
        int signal = Float.compare(power, otherPower);
        if (signal != 0) return signal;
        int antenna = Long.compare(otherPosition.asLong(), position.asLong());
        return antenna != 0 ? antenna : Integer.compare(otherFrequency.ordinal(), frequency.ordinal());
    }
}
