package com.florentdubut.telecom.network;

public record SpeedtestServerOption(String id, String name, int estimatedPingMs, boolean available,
                                   int bandwidthMbps, String reason) {}
