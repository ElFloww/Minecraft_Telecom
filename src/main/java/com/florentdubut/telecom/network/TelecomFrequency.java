package com.florentdubut.telecom.network;

public enum TelecomFrequency {
    G3_900("3G", 900, 10, 0.05f),
    G4_800("4G", 800, 50, 0.04f),
    G4_2600("4G", 2600, 150, 0.15f),
    G5_3500("5G", 3500, 1000, 0.30f);

    private final String technology;
    private final int frequencyMhz;
    private final int maxSpeedMb; // Max potential speed in MB/s
    private final float baseAttenuation; // Base signal loss per block in air

    TelecomFrequency(String technology, int frequencyMhz, int maxSpeedMb, float baseAttenuation) {
        this.technology = technology;
        this.frequencyMhz = frequencyMhz;
        this.maxSpeedMb = maxSpeedMb;
        this.baseAttenuation = baseAttenuation;
    }

    public String getTechnology() {
        return technology;
    }

    public int getFrequencyMhz() {
        return frequencyMhz;
    }

    public int getMaxSpeedMb() {
        return maxSpeedMb;
    }

    public float getBaseAttenuation() {
        return baseAttenuation;
    }
}
