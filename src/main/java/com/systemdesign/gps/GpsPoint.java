package com.systemdesign.gps;

import java.time.Instant;

public record GpsPoint(
        double latitude,
        double longitude,
        double hdop,
        double speedKnots,
        double courseDegreesTrue,
        Instant timestamp
) {

    public GpsPoint {
        requireFinite(latitude, "latitude");
        requireFinite(longitude, "longitude");
        requireFinite(hdop, "hdop");
        requireFinite(speedKnots, "speedKnots");
        requireFinite(courseDegreesTrue, "courseDegreesTrue");

        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude must be in [-90, 90], got: " + latitude);
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude must be in [-180, 180], got: " + longitude);
        }
        if (hdop < 0) {
            throw new IllegalArgumentException("hdop must be >= 0, got: " + hdop);
        }
        if (speedKnots < 0) {
            throw new IllegalArgumentException("speedKnots must be >= 0, got: " + speedKnots);
        }
        if (courseDegreesTrue < 0 || courseDegreesTrue >= 360) {
            throw new IllegalArgumentException("courseDegreesTrue must be in [0, 360), got: " + courseDegreesTrue);
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
    }

    private static void requireFinite(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got: " + value);
        }
    }

    public static GpsPoint of(double lat, double lon, double hdop, double speedKnots, double course, Instant timestamp) {
        return new GpsPoint(lat, lon, hdop, speedKnots, course, timestamp);
    }
}
