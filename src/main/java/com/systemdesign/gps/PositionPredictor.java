package com.systemdesign.gps;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class PositionPredictor {

    private static final double STABILITY_HALF_LIFE_SECONDS = 60.0;
    private static final double VARIANCE_SCALE = 100.0;
    private static final int MIN_POINTS_FOR_PREDICTION = 2;
    private static final double KNOTS_TO_MPS = 0.514444;
    private static final double METERS_PER_DEG_LAT = 111_320.0;

    private final int maxHistoryPoints;

    public PositionPredictor() {
        this(10);
    }

    public PositionPredictor(int maxHistoryPoints) {
        if (maxHistoryPoints < MIN_POINTS_FOR_PREDICTION) {
            throw new IllegalArgumentException("maxHistoryPoints must be >= " + MIN_POINTS_FOR_PREDICTION);
        }
        this.maxHistoryPoints = maxHistoryPoints;
    }

    public record PredictedPosition(
            double latitude,
            double longitude,
            double confidenceScore,
            Instant predictedAt
    ) {
    }

    public Optional<PredictedPosition> predict(List<GpsPoint> history, long horizonSeconds) {
        if (horizonSeconds <= 0) {
            throw new IllegalArgumentException("horizonSeconds must be > 0, got: " + horizonSeconds);
        }
        if (history == null || history.size() < MIN_POINTS_FOR_PREDICTION) {
            return Optional.empty();
        }

        List<GpsPoint> recent = history.size() > maxHistoryPoints
                ? history.subList(history.size() - maxHistoryPoints, history.size())
                : history;

        GpsPoint last = recent.getLast();
        GpsPoint prev = recent.get(recent.size() - 2);

        double elapsedSeconds = elapsedSeconds(prev.timestamp(), last.timestamp());
        if (elapsedSeconds <= 0) {
            return Optional.empty();
        }

        double dLatPerSec;
        double dLonPerSec;

        if (last.speedKnots() > 0 && last.courseDegreesTrue() >= 0) {
            double speedMps = last.speedKnots() * KNOTS_TO_MPS;
            double bearingRad = Math.toRadians(last.courseDegreesTrue());
            dLatPerSec = speedMps * Math.cos(bearingRad) / METERS_PER_DEG_LAT;
            double cosLat = Math.cos(Math.toRadians(last.latitude()));
            double metersPerDegLon = METERS_PER_DEG_LAT * Math.max(cosLat, 0.01);
            dLonPerSec = speedMps * Math.sin(bearingRad) / metersPerDegLon;
        } else {
            dLatPerSec = (last.latitude() - prev.latitude()) / elapsedSeconds;
            dLonPerSec = (last.longitude() - prev.longitude()) / elapsedSeconds;
        }

        double predictedLat = last.latitude() + dLatPerSec * horizonSeconds;
        double predictedLon = last.longitude() + dLonPerSec * horizonSeconds;

        predictedLat = Math.max(-90.0, Math.min(90.0, predictedLat));
        predictedLon = clampLongitude(predictedLon);

        double confidenceScore = computeConfidenceScore(last.timestamp(), horizonSeconds, recent);
        Instant predictedAt = last.timestamp().plusSeconds(horizonSeconds);

        return Optional.of(new PredictedPosition(predictedLat, predictedLon, confidenceScore, predictedAt));
    }

    public Optional<PredictedPosition> predictAt10s(List<GpsPoint> history) {
        return predict(history, 10);
    }

    public Optional<PredictedPosition> predictAt30s(List<GpsPoint> history) {
        return predict(history, 30);
    }

    public Optional<PredictedPosition> predictAt60s(List<GpsPoint> history) {
        return predict(history, 60);
    }

    private double computeConfidenceScore(Instant lastTimestamp, long horizonSeconds, List<GpsPoint> recent) {
        double halfLifeDecay = Math.exp(-Math.log(2) * horizonSeconds / STABILITY_HALF_LIFE_SECONDS);

        double variance = computeTrajectoryVariance(recent);
        double consistencyFactor = 1.0 / (1.0 + variance * VARIANCE_SCALE);

        return Math.max(0.0, Math.min(1.0, halfLifeDecay * consistencyFactor));
    }

    private double computeTrajectoryVariance(List<GpsPoint> points) {
        if (points.size() < 3) return 0.0;

        double sumDLat = 0, sumDLon = 0;
        double sumDLatSq = 0, sumDLonSq = 0;
        int segmentCount = 0;

        for (int i = 1; i < points.size(); i++) {
            GpsPoint curr = points.get(i);
            GpsPoint previous = points.get(i - 1);
            double elapsed = elapsedSeconds(previous.timestamp(), curr.timestamp());
            if (elapsed <= 0) continue;

            double dLat = (curr.latitude() - previous.latitude()) / elapsed;
            double dLon = (curr.longitude() - previous.longitude()) / elapsed;
            sumDLat += dLat;
            sumDLon += dLon;
            sumDLatSq += dLat * dLat;
            sumDLonSq += dLon * dLon;
            segmentCount++;
        }

        if (segmentCount < 2) return 0.0;

        double varLat = (sumDLatSq - sumDLat * sumDLat / segmentCount) / (segmentCount - 1);
        double varLon = (sumDLonSq - sumDLon * sumDLon / segmentCount) / (segmentCount - 1);
        return varLat + varLon;
    }

    private static double elapsedSeconds(Instant from, Instant to) {
        return (to.toEpochMilli() - from.toEpochMilli()) / 1000.0;
    }

    private static double clampLongitude(double lon) {
        return ((lon + 180) % 360 + 360) % 360 - 180;
    }
}
