package com.systemdesign.gps;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PositionPredictorTest {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

    private static GpsPoint point(double lat, double lon, Instant timestamp) {
        return GpsPoint.of(lat, lon, 1.0, 0.0, 0.0, timestamp);
    }

    @Test
    void constructorRejectsMaxHistoryPointsLessThanTwo() {
        assertThrows(IllegalArgumentException.class, () -> new PositionPredictor(1));
        assertThrows(IllegalArgumentException.class, () -> new PositionPredictor(0));
        assertThrows(IllegalArgumentException.class, () -> new PositionPredictor(-5));
    }

    @Test
    void constructorAcceptsMaxHistoryPointsOfTwo() {
        assertDoesNotThrow(() -> {
            new PositionPredictor(2);
        });
    }

    @Test
    void defaultConstructorSucceeds() {
        assertDoesNotThrow(() -> {
            new PositionPredictor();
        });
    }

    @Test
    void returnsEmptyForNullHistory() {
        PositionPredictor predictor = new PositionPredictor();
        Optional<PositionPredictor.PredictedPosition> result = predictor.predict(null, 30);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForSinglePointHistory() {
        PositionPredictor predictor = new PositionPredictor();
        List<GpsPoint> history = List.of(point(60.0, 25.0, T0));
        assertTrue(predictor.predict(history, 30).isEmpty());
    }

    @Test
    void returnsEmptyForEmptyHistory() {
        PositionPredictor predictor = new PositionPredictor();
        assertTrue(predictor.predict(List.of(), 30).isEmpty());
    }

    @Test
    void predictsTwoPointsMovingNorthIncreasesLatitude() {
        PositionPredictor predictor = new PositionPredictor();
        GpsPoint p1 = point(60.000, 25.0, T0);
        GpsPoint p2 = point(60.001, 25.0, T0.plusSeconds(10));
        List<GpsPoint> history = List.of(p1, p2);

        PositionPredictor.PredictedPosition result = predictor.predict(history, 10).orElseThrow();

        assertTrue(result.latitude() > p2.latitude());
        assertEquals(25.0, result.longitude(), 1e-9);
    }

    @Test
    void predictsTwoPointsMovingEastIncreasesLongitude() {
        PositionPredictor predictor = new PositionPredictor();
        GpsPoint p1 = point(60.0, 25.000, T0);
        GpsPoint p2 = point(60.0, 25.001, T0.plusSeconds(10));
        List<GpsPoint> history = List.of(p1, p2);

        PositionPredictor.PredictedPosition result = predictor.predict(history, 10).orElseThrow();

        assertTrue(result.longitude() > p2.longitude());
        assertEquals(60.0, result.latitude(), 1e-9);
    }

    @Test
    void confidenceScoreDecreasesWithLongerHorizon() {
        PositionPredictor predictor = new PositionPredictor();
        GpsPoint p1 = point(60.000, 25.0, T0);
        GpsPoint p2 = point(60.001, 25.0, T0.plusSeconds(10));
        List<GpsPoint> history = List.of(p1, p2);

        double score10 = predictor.predict(history, 10).orElseThrow().confidenceScore();
        double score60 = predictor.predict(history, 60).orElseThrow().confidenceScore();
        double score300 = predictor.predict(history, 300).orElseThrow().confidenceScore();

        assertTrue(score10 > score60);
        assertTrue(score60 > score300);
    }

    @Test
    void predictRejectsZeroHorizon() {
        PositionPredictor predictor = new PositionPredictor();
        GpsPoint p1 = point(60.000, 25.0, T0);
        GpsPoint p2 = point(60.001, 25.0, T0.plusSeconds(10));
        List<GpsPoint> history = List.of(p1, p2);

        assertThrows(IllegalArgumentException.class, () -> predictor.predict(history, 0));
    }

    @Test
    void predictRejectsNegativeHorizon() {
        PositionPredictor predictor = new PositionPredictor();
        GpsPoint p1 = point(60.000, 25.0, T0);
        GpsPoint p2 = point(60.001, 25.0, T0.plusSeconds(10));
        List<GpsPoint> history = List.of(p1, p2);

        assertThrows(IllegalArgumentException.class, () -> predictor.predict(history, -5));
    }

    @Test
    void latitudeIsClampedToNinetyWhenExtrapolatingNorth() {
        PositionPredictor predictor = new PositionPredictor();
        GpsPoint p1 = point(89.0, 0.0, T0);
        GpsPoint p2 = point(89.9, 0.0, T0.plusSeconds(10));
        List<GpsPoint> history = List.of(p1, p2);

        PositionPredictor.PredictedPosition result = predictor.predict(history, 60).orElseThrow();

        assertEquals(90.0, result.latitude(), 1e-9);
    }

    @Test
    void latitudeIsClampedToNegativeNinetyWhenExtrapolatingSouth() {
        PositionPredictor predictor = new PositionPredictor();
        GpsPoint p1 = point(-89.0, 0.0, T0);
        GpsPoint p2 = point(-89.9, 0.0, T0.plusSeconds(10));
        List<GpsPoint> history = List.of(p1, p2);

        PositionPredictor.PredictedPosition result = predictor.predict(history, 60).orElseThrow();

        assertEquals(-90.0, result.latitude(), 1e-9);
    }

    @Test
    void longitudeWrapsWhenExceedingOneEighty() {
        PositionPredictor predictor = new PositionPredictor();
        GpsPoint p1 = point(0.0, 179.0, T0);
        GpsPoint p2 = point(0.0, 179.5, T0.plusSeconds(10));
        List<GpsPoint> history = List.of(p1, p2);

        PositionPredictor.PredictedPosition result = predictor.predict(history, 10).orElseThrow();

        assertTrue(result.longitude() >= -180.0 && result.longitude() <= 180.0);
    }

    @Test
    void longitudeWrapsWhenBelowNegativeOneEighty() {
        PositionPredictor predictor = new PositionPredictor();
        GpsPoint p1 = point(0.0, -179.0, T0);
        GpsPoint p2 = point(0.0, -179.5, T0.plusSeconds(10));
        List<GpsPoint> history = List.of(p1, p2);

        PositionPredictor.PredictedPosition result = predictor.predict(history, 10).orElseThrow();

        assertTrue(result.longitude() >= -180.0 && result.longitude() <= 180.0);
    }

    @Test
    void predictAt10sUsesHorizonOfTenSeconds() {
        PositionPredictor predictor = new PositionPredictor();
        GpsPoint p1 = point(60.000, 25.0, T0);
        GpsPoint p2 = point(60.001, 25.0, T0.plusSeconds(10));
        List<GpsPoint> history = List.of(p1, p2);

        Optional<PositionPredictor.PredictedPosition> result = predictor.predictAt10s(history);

        assertTrue(result.isPresent());
        assertEquals(T0.plusSeconds(20), result.get().predictedAt());
    }

    @Test
    void predictAt30sUsesHorizonOfThirtySeconds() {
        PositionPredictor predictor = new PositionPredictor();
        GpsPoint p1 = point(60.000, 25.0, T0);
        GpsPoint p2 = point(60.001, 25.0, T0.plusSeconds(10));
        List<GpsPoint> history = List.of(p1, p2);

        Optional<PositionPredictor.PredictedPosition> result = predictor.predictAt30s(history);

        assertTrue(result.isPresent());
        assertEquals(T0.plusSeconds(40), result.get().predictedAt());
    }

    @Test
    void predictAt60sUsesHorizonOfSixtySeconds() {
        PositionPredictor predictor = new PositionPredictor();
        GpsPoint p1 = point(60.000, 25.0, T0);
        GpsPoint p2 = point(60.001, 25.0, T0.plusSeconds(10));
        List<GpsPoint> history = List.of(p1, p2);

        Optional<PositionPredictor.PredictedPosition> result = predictor.predictAt60s(history);

        assertTrue(result.isPresent());
        assertEquals(T0.plusSeconds(70), result.get().predictedAt());
    }
}
