package com.florentdubut.telecom.network;

public enum TelecomFrequency {
    // 2G
    G2_900("2G", "900 MHz", "-", 900, 1, 0.025f),
    G2_1800("2G", "1800 MHz", "-", 1800, 1, 0.05f),

    // 3G
    G3_900("3G", "900 MHz", "-", 900, 10, 0.025f),
    G3_2100("3G", "2100 MHz", "-", 2100, 20, 0.06f),

    // 4G
    G4_700("4G", "700 MHz", "Bande 28", 700, 75, 0.015f),
    G4_800("4G", "800 MHz", "Bande 20", 800, 75, 0.02f),
    G4_900("4G", "900 MHz", "Bande 8", 900, 50, 0.025f),
    G4_1800("4G", "1800 MHz", "Bande 3", 1800, 150, 0.05f),
    G4_2100("4G", "2100 MHz", "Bande 1", 2100, 150, 0.06f),
    G4_2600("4G", "2600 MHz", "Bande 7", 2600, 200, 0.075f),

    // 5G
    G5_700("5G", "700 MHz", "n28", 700, 150, 0.015f),
    G5_2100("5G", "2100 MHz", "n1", 2100, 300, 0.06f),
    G5_3500("5G", "3.5 GHz", "n78", 3500, 1000, 0.15f),
    G5_26000("5G", "26 GHz", "n258", 26000, 5000, 1.25f); // 1.25f drops rapidly

    private final String technology;
    private final String frequencyLabel;
    private final String bandName;
    private final int frequencyMhz;
    private final int maxSpeedMb; // Max potential speed in MB/s
    private final float baseAttenuation; // Base signal loss per block in air

    TelecomFrequency(String technology, String frequencyLabel, String bandName, int frequencyMhz, int maxSpeedMb, float baseAttenuation) {
        this.technology = technology;
        this.frequencyLabel = frequencyLabel;
        this.bandName = bandName;
        this.frequencyMhz = frequencyMhz;
        this.maxSpeedMb = maxSpeedMb;
        this.baseAttenuation = baseAttenuation;
    }

    public String getTechnology() {
        return technology;
    }
    
    public String getFrequencyLabel() {
        return frequencyLabel;
    }
    
    public String getBandName() {
        return bandName;
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

    // Helper to order frequencies by performance (generation then speed)
    public int getPerformanceScore() {
        int genScore = 0;
        if (technology.equals("5G")) genScore = 40000;
        else if (technology.equals("4G")) genScore = 30000;
        else if (technology.equals("3G")) genScore = 20000;
        else if (technology.equals("2G")) genScore = 10000;
        
        return genScore + maxSpeedMb;
    }
}
