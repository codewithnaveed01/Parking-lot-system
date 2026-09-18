package com.parking.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A floor composed of many spots (Composition). */
public class ParkingFloor {
    private final int level;
    private final List<ParkingSpot> spots = new ArrayList<>();

    public ParkingFloor(int level, int compact, int regular, int large, int oversize) {
        this.level = level;
        int n = 1;
        for (int i = 0; i < compact;  i++) spots.add(new ParkingSpot(level, n++, SpotType.COMPACT));
        for (int i = 0; i < regular;  i++) spots.add(new ParkingSpot(level, n++, SpotType.REGULAR));
        for (int i = 0; i < large;    i++) spots.add(new ParkingSpot(level, n++, SpotType.LARGE));
        for (int i = 0; i < oversize; i++) spots.add(new ParkingSpot(level, n++, SpotType.OVERSIZE));
    }

    public int getLevel() { return level; }
    public List<ParkingSpot> getSpots() { return Collections.unmodifiableList(spots); }

    public long freeCount() { return spots.stream().filter(ParkingSpot::isFree).count(); }
}
