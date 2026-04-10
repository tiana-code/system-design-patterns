package com.systemdesign.gps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class GpsValidator {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double KNOTS_TO_KMH = 1.852;
    private static final double DEFAULT_MAX_SPEED_KNOTS = 50.0;
    private static final double DEFAULT_MAX_HDOP = 10.0;
    private static final double DEFAULT_SPEED_CONSISTENCY_RATIO = 3.0;

    private final double maxSpeedKnots;
    private final double maxHdop;
    private final double speedConsistencyRatio;
    private final List<Consumer<GpsPoint>> postValidationListeners;

    public GpsValidator() {
        this(DEFAULT_MAX_SPEED_KNOTS, DEFAULT_MAX_HDOP, List.of());
    }

    public GpsValidator(double maxSpeedKnots, double maxHdop) {
        this(maxSpeedKnots, maxHdop, List.of());
    }

    public GpsValidator(double maxSpeedKnots, double maxHdop, List<Consumer<GpsPoint>> postValidationListeners) {
        this(maxSpeedKnots, maxHdop, DEFAULT_SPEED_CONSISTENCY_RATIO, postValidationListeners);
    }

    public GpsValidator(double maxSpeedKnots, double maxHdop, double speedConsistencyRatio, List<Consumer<GpsPoint>> postValidationListeners) {
        this.maxSpeedKnots = maxSpeedKnots;
        this.maxHdop = maxHdop;
        this.speedConsistencyRatio = speedConsistencyRatio;
        this.postValidationListeners = List.copyOf(postValidationListeners);
    }

    public sealed interface ValidationResult permits ValidationResult.Valid, ValidationResult.Invalid {

        record Valid(GpsPoint point) implements ValidationResult {
        }

        record Invalid(GpsPoint point, String reason) implements ValidationResult {
        }

        default boolean isValid() {
            return this instanceof Valid;
        }

        default Optional<GpsPoint> validPoint() {
            return switch (this) {
                case Valid v -> Optional.of(v.point());
                case Invalid i -> Optional.empty();
            };
        }
    }

    public record BatchValidationResult(
            List<GpsPoint> accepted,
            List<ValidationResult.Invalid> rejected
    ) {
        public BatchValidationResult {
            accepted = List.copyOf(accepted);
            rejected = List.copyOf(rejected);
        }
    }

    public ValidationResult validate(GpsPoint point, GpsPoint previous) {
        ValidationResult accuracyResult = checkSignalQuality(point);
        if (!accuracyResult.isValid()) return accuracyResult;

        ValidationResult orderResult = checkTimestampOrder(point, previous);
        if (!orderResult.isValid()) return orderResult;

        ValidationResult speedResult = checkSpeed(point, previous);
        if (!speedResult.isValid()) return speedResult;

        notifyListeners(point);

        return new ValidationResult.Valid(point);
    }

    public ValidationResult validateFirst(GpsPoint point) {
        ValidationResult accuracyResult = checkSignalQuality(point);
        if (!accuracyResult.isValid()) return accuracyResult;

        notifyListeners(point);
        return new ValidationResult.Valid(point);
    }

    public BatchValidationResult filterAndValidate(List<GpsPoint> points) {
        List<GpsPoint> accepted = new ArrayList<>();
        List<ValidationResult.Invalid> rejected = new ArrayList<>();
        GpsPoint previous = null;

        for (GpsPoint point : points) {
            ValidationResult result = (previous == null)
                    ? validateFirst(point)
                    : validate(point, previous);

            if (result instanceof ValidationResult.Valid(GpsPoint point1)) {
                accepted.add(point1);
                previous = point;
            } else if (result instanceof ValidationResult.Invalid invalid) {
                rejected.add(invalid);
            }
        }

        return new BatchValidationResult(accepted, rejected);
    }

    private void notifyListeners(GpsPoint point) {
        postValidationListeners.forEach(listener -> listener.accept(point));
    }

    private ValidationResult checkSignalQuality(GpsPoint point) {
        if (point.hdop() > maxHdop) {
            return new ValidationResult.Invalid(point,
                    "HDOP %.2f exceeds threshold %.2f".formatted(point.hdop(), maxHdop));
        }
        return new ValidationResult.Valid(point);
    }

    private ValidationResult checkTimestampOrder(GpsPoint point, GpsPoint previous) {
        if (previous != null && !point.timestamp().isAfter(previous.timestamp())) {
            return new ValidationResult.Invalid(point,
                    "Timestamp %s is not after previous %s".formatted(point.timestamp(), previous.timestamp()));
        }
        return new ValidationResult.Valid(point);
    }

    private ValidationResult checkSpeed(GpsPoint point, GpsPoint previous) {
        if (previous == null) return new ValidationResult.Valid(point);

        double distanceKm = haversineKm(
                previous.latitude(), previous.longitude(),
                point.latitude(), point.longitude()
        );
        double elapsedHours = elapsedHours(previous.timestamp(), point.timestamp());

        if (elapsedHours <= 0) return new ValidationResult.Valid(point);

        double speedKmh = distanceKm / elapsedHours;
        double speedKnots = speedKmh / KNOTS_TO_KMH;

        if (speedKnots > maxSpeedKnots) {
            return new ValidationResult.Invalid(point,
                    "Computed speed %.2f kn exceeds max %.2f kn (distance=%.3f km, elapsed=%.4f h)"
                            .formatted(speedKnots, maxSpeedKnots, distanceKm, elapsedHours));
        }

        if (point.speedKnots() > 0 && speedKnots > 0) {
            double ratio = Math.max(point.speedKnots(), speedKnots) / Math.min(point.speedKnots(), speedKnots);
            if (ratio > speedConsistencyRatio) {
                return new ValidationResult.Invalid(point,
                        "Speed inconsistency: reported %.2f kn vs derived %.2f kn (ratio %.2f exceeds %.2f)"
                                .formatted(point.speedKnots(), speedKnots, ratio, speedConsistencyRatio));
            }
        }

        return new ValidationResult.Valid(point);
    }

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    private static double elapsedHours(Instant from, Instant to) {
        long ms = to.toEpochMilli() - from.toEpochMilli();
        return ms / 3_600_000.0;
    }
}
