package com.systemdesign.gps;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GpsValidatorTest {

    private static final GpsValidator validator = new GpsValidator(50.0, 10.0);

    private static GpsPoint point(double lat, double lon, double hdop, Instant timestamp) {
        return GpsPoint.of(lat, lon, hdop, 0.0, 0.0, timestamp);
    }

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

    @Test
    void validFirstPointIsAccepted() {
        GpsPoint p = point(60.0, 25.0, 1.2, T0);
        GpsValidator.ValidationResult result = validator.validateFirst(p);
        assertTrue(result.isValid());
        assertEquals(p, result.validPoint().orElseThrow());
    }

    @Test
    void highHdopIsRejected() {
        GpsPoint p = point(60.0, 25.0, 15.0, T0);
        GpsValidator.ValidationResult result = validator.validateFirst(p);
        assertFalse(result.isValid());
        assertInstanceOf(GpsValidator.ValidationResult.Invalid.class, result);
        String reason = ((GpsValidator.ValidationResult.Invalid) result).reason();
        assertTrue(reason.contains("HDOP"), "Expected HDOP in reason: " + reason);
    }

    @Test
    void hdopAtThresholdIsAccepted() {
        GpsPoint p = point(60.0, 25.0, 10.0, T0);
        assertTrue(validator.validateFirst(p).isValid());
    }

    @Test
    void outOfOrderTimestampIsRejected() {
        GpsPoint prev = point(60.0, 25.0, 1.0, T0);
        GpsPoint curr = point(60.001, 25.001, 1.0, T0.minusSeconds(10));
        GpsValidator.ValidationResult result = validator.validate(curr, prev);
        assertFalse(result.isValid());
        String reason = ((GpsValidator.ValidationResult.Invalid) result).reason();
        assertTrue(reason.toLowerCase().contains("timestamp"), "Expected timestamp in reason: " + reason);
    }

    @Test
    void sameTimestampIsRejected() {
        GpsPoint prev = point(60.0, 25.0, 1.0, T0);
        GpsPoint curr = point(60.001, 25.001, 1.0, T0);
        assertFalse(validator.validate(curr, prev).isValid());
    }

    @Test
    void physicallyImpossibleSpeedIsRejected() {
        GpsPoint prev = point(0.0, 0.0, 1.0, T0);
        GpsPoint curr = point(10.0, 10.0, 1.0, T0.plusSeconds(1));
        GpsValidator.ValidationResult result = validator.validate(curr, prev);
        assertFalse(result.isValid());
        String reason = ((GpsValidator.ValidationResult.Invalid) result).reason();
        assertTrue(reason.toLowerCase().contains("speed"), "Expected speed in reason: " + reason);
    }

    @Test
    void realisticSpeedIsAccepted() {
        GpsPoint prev = point(60.000, 25.000, 1.0, T0);
        GpsPoint curr = point(60.001, 25.001, 1.0, T0.plusSeconds(60));
        assertTrue(validator.validate(curr, prev).isValid());
    }

    @Test
    void speedExactlyAtThresholdIsAccepted() {
        double maxKnots = 50.0;
        double maxKmh = maxKnots * 1.852;
        double distanceKm = maxKmh * (1.0 / 3600.0);
        double latDelta = distanceKm / 111.0;

        GpsPoint prev = point(60.0, 25.0, 1.0, T0);
        GpsPoint curr = point(60.0 + latDelta * 0.99, 25.0, 1.0, T0.plusSeconds(1));
        assertTrue(validator.validate(curr, prev).isValid());
    }

    @Test
    void enricherIsCalledOnValidPoint() {
        AtomicInteger count = new AtomicInteger(0);
        GpsValidator enrichingValidator = new GpsValidator(50.0, 10.0, List.of(p
                -> count.incrementAndGet()));

        GpsPoint p = point(60.0, 25.0, 1.0, T0);
        enrichingValidator.validateFirst(p);
        assertEquals(1, count.get());
    }

    @Test
    void enricherIsNotCalledOnInvalidPoint() {
        AtomicInteger count = new AtomicInteger(0);
        GpsValidator enrichingValidator = new GpsValidator(50.0, 10.0, List.of(p
                -> count.incrementAndGet()));

        GpsPoint p = point(60.0, 25.0, 15.0, T0);
        enrichingValidator.validateFirst(p);
        assertEquals(0, count.get());
    }

    @Test
    void filterAndValidateFiltersOutInvalidPoints() {
        GpsPoint p1 = point(60.000, 25.000, 1.0, T0);
        GpsPoint p2 = point(60.001, 25.001, 15.0, T0.plusSeconds(60));
        GpsPoint p3 = point(60.002, 25.002, 1.0, T0.plusSeconds(120));

        GpsValidator.BatchValidationResult result = validator.filterAndValidate(List.of(p1, p2, p3));
        assertEquals(2, result.accepted().size());
        assertEquals(p1, result.accepted().get(0));
        assertEquals(p3, result.accepted().get(1));
    }

    @Test
    void filterAndValidateRejectsSpeedViolationsAndContinues() {
        GpsPoint p1 = point(0.0, 0.0, 1.0, T0);
        GpsPoint badSpeed = point(10.0, 10.0, 1.0, T0.plusSeconds(1));
        GpsPoint p3 = point(0.001, 0.001, 1.0, T0.plusSeconds(120));

        GpsValidator.BatchValidationResult result = validator.filterAndValidate(List.of(p1, badSpeed, p3));
        assertTrue(result.accepted().contains(p1));
        assertFalse(result.accepted().contains(badSpeed));
        assertTrue(result.accepted().contains(p3));
        assertFalse(result.rejected().isEmpty());
    }

    @Test
    void filterAndValidateEmptyListReturnsEmpty() {
        GpsValidator.BatchValidationResult result = validator.filterAndValidate(List.of());
        assertTrue(result.accepted().isEmpty());
    }

    @Test
    void filterAndValidateRejectedContainsRejectionReasons() {
        GpsPoint p1 = point(0.0, 0.0, 1.0, T0);
        GpsPoint badSpeed = point(10.0, 10.0, 1.0, T0.plusSeconds(1));
        GpsPoint badHdop = point(0.002, 0.002, 15.0, T0.plusSeconds(120));

        GpsValidator.BatchValidationResult result = validator.filterAndValidate(List.of(p1, badSpeed, badHdop));
        assertEquals(2, result.rejected().size());
        assertTrue(result.rejected().stream().anyMatch(r -> r.reason().toLowerCase().contains("speed")));
        assertTrue(result.rejected().stream().anyMatch(r -> r.reason().contains("HDOP")));
    }

    @Test
    void multipleEnrichersAreAllInvoked() {
        AtomicInteger counter = new AtomicInteger(0);
        GpsValidator multi = new GpsValidator(50.0, 10.0, List.of(
                p -> counter.addAndGet(1),
                p -> counter.addAndGet(10)
        ));

        GpsPoint p = point(60.0, 25.0, 1.0, T0);
        multi.validateFirst(p);
        assertEquals(11, counter.get());
    }

    @Test
    void haversineKmBetweenSamePointIsZero() {
        assertEquals(0.0, GpsValidator.haversineKm(60.0, 25.0, 60.0, 25.0), 1e-9);
    }

    @Test
    void haversineKmIsPositiveForDifferentPoints() {
        double dist = GpsValidator.haversineKm(0.0, 0.0, 1.0, 1.0);
        assertTrue(dist > 0);
    }
}
