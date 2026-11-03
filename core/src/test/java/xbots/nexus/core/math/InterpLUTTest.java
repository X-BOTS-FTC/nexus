package xbots.nexus.core.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InterpLUTTest {

    private static final double EPSILON = 1e-9;
    private static final int SAMPLES = 1000;

    // Builds a table from alternating input and output values: x0, y0, x1, y1, ...
    private static InterpLUT lut(double... pairs) {
        InterpLUT.Builder builder = InterpLUT.builder(1);
        for (int i = 0; i < pairs.length; i += 2) {
            builder.add(pairs[i], pairs[i + 1]);
        }
        return builder.build();
    }

    // The input that is step number k of SAMPLES evenly spaced steps from start to end.
    private static double sample(double start, double end, int k) {
        return start + (end - start) * k / SAMPLES;
    }

    // ---------------------------------------------------- exactness and edges

    @Test
    void passesExactlyThroughEveryPoint() {
        InterpLUT table = lut(0, 0, 1, 11, 2, 12, 3, 23, 4.5, 20);
        assertEquals(0, table.get(0), EPSILON);
        assertEquals(11, table.get(1), EPSILON);
        assertEquals(12, table.get(2), EPSILON);
        assertEquals(23, table.get(3), EPSILON);
        assertEquals(20, table.get(4.5), EPSILON);
    }

    @Test
    void oneDimensionalResultsMatchTheOriginalImplementation() {
        InterpLUT table = lut(0, 0, 1, 11, 2, 12, 3, 23, 4.5, 20);
        assertEquals(6.609834957055044, table.get(0.5), EPSILON);
        assertEquals(11.5, table.get(1.5), EPSILON);
        assertEquals(17.765165042944957, table.get(2.5), EPSILON);
        assertEquals(21.875, table.get(3.75), EPSILON);
        assertEquals(20.569444444444443, table.get(4.25), EPSILON);

        InterpLUT rising = lut(0, 0, 1, 11, 2, 12, 3, 23);
        assertEquals(6.609834957055044, rising.get(0.5), EPSILON);
        assertEquals(11.5, rising.get(1.5), EPSILON);
        assertEquals(16.390165042944957, rising.get(2.5), EPSILON);

        InterpLUT falling = lut(0, 23, 1, 12, 2, 11, 3, 0);
        assertEquals(16.390165042944954, falling.get(0.5), EPSILON);
        assertEquals(11.5, falling.get(1.5), EPSILON);
        assertEquals(6.609834957055044, falling.get(2.5), EPSILON);
    }

    @Test
    void inputsOutsideTheTableExtrapolateLinearly() {
        InterpLUT table = lut(1, 10, 2, 20, 3, 15);
        assertEquals(5, table.get(0.5), EPSILON);
        assertEquals(-10000, table.get(-1000), EPSILON);
        assertEquals(-470, table.get(100), EPSILON);
    }

    @Test
    void linearDataExtrapolatesAlongTheSameLine() {
        InterpLUT table = lut(0, 0, 10, 100);
        assertEquals(-50, table.get(-5), EPSILON);
        assertEquals(150, table.get(15), EPSILON);
    }

    @Test
    void nanAndInfinityInputsGiveNan() {
        InterpLUT table = lut(0, 0, 1, 1);
        assertTrue(Double.isNaN(table.get(Double.NaN)));
        assertTrue(Double.isNaN(table.get(Double.NEGATIVE_INFINITY)));
        assertTrue(Double.isNaN(table.get(Double.POSITIVE_INFINITY)));
    }

    @Test
    void reportsItsSizeAndRange() {
        InterpLUT table = lut(5, 1, 2, 1, 9, 1);
        assertEquals(3, table.size());
        assertEquals(2, table.minInput(), EPSILON);
        assertEquals(9, table.maxInput(), EPSILON);
    }

    // ---------------------------------------------------- shape

    @Test
    void twoPointsGiveAStraightLine() {
        InterpLUT table = lut(0, 0, 10, 100);
        assertEquals(25, table.get(2.5), EPSILON);
        assertEquals(50, table.get(5), EPSILON);
        assertEquals(99, table.get(9.9), EPSILON);
    }

    @Test
    void pointsOnAStraightLineGiveThatLine() {
        InterpLUT table = lut(0, 0, 1, 1, 2, 2, 5, 5);
        assertEquals(0.3, table.get(0.3), EPSILON);
        assertEquals(1.7, table.get(1.7), EPSILON);
        assertEquals(3.9, table.get(3.9), EPSILON);
    }

    @Test
    void risingDataNeverGoesDown() {
        // Every step goes up, but the middle step is much smaller than its neighbors. This is the
        // case where an implementation with too loose a slope limit makes the curve dip.
        InterpLUT table = lut(0, 0, 1, 11, 2, 12, 3, 23);
        double previous = table.get(0);
        for (int k = 1; k <= SAMPLES; k++) {
            double value = table.get(sample(0, 3, k));
            assertTrue(value >= previous - EPSILON,
                    "went down at step " + k + ": " + previous + " -> " + value);
            previous = value;
        }
    }

    @Test
    void fallingDataNeverGoesUp() {
        InterpLUT table = lut(0, 23, 1, 12, 2, 11, 3, 0);
        double previous = table.get(0);
        for (int k = 1; k <= SAMPLES; k++) {
            double value = table.get(sample(0, 3, k));
            assertTrue(value <= previous + EPSILON,
                    "went up at step " + k + ": " + previous + " -> " + value);
            previous = value;
        }
    }

    @Test
    void neverOvershootsPastAPeakInTheData() {
        // The highest point is 10 and the lowest is 0. The curve must stay inside that range.
        InterpLUT table = lut(0, 0, 1, 10, 2, 8, 3, 0);
        for (int k = 0; k <= SAMPLES; k++) {
            double value = table.get(sample(0, 3, k));
            assertTrue(value <= 10 + EPSILON, "above the peak: " + value);
            assertTrue(value >= 0 - EPSILON, "below the lowest point: " + value);
        }
    }

    @Test
    void neverUndershootsBelowADipInTheData() {
        InterpLUT table = lut(0, 10, 1, 0, 2, 2, 3, 10);
        for (int k = 0; k <= SAMPLES; k++) {
            double value = table.get(sample(0, 3, k));
            assertTrue(value >= 0 - EPSILON, "below the dip: " + value);
            assertTrue(value <= 10 + EPSILON, "above the highest point: " + value);
        }
    }

    @Test
    void aFlatStretchStaysPerfectlyFlat() {
        InterpLUT table = lut(0, 0, 1, 5, 2, 5, 3, 9);
        for (int k = 0; k <= SAMPLES; k++) {
            assertEquals(5, table.get(sample(1, 2, k)), 1e-12);
        }
    }

    // ---------------------------------------------------- building

    @Test
    void pointsMayBeAddedInAnyOrder() {
        InterpLUT ordered = lut(0, 0, 1, 11, 2, 12, 3, 23);
        InterpLUT shuffled = lut(2, 12, 0, 0, 3, 23, 1, 11);
        for (int k = 0; k <= SAMPLES; k++) {
            double x = sample(0, 3, k);
            assertEquals(ordered.get(x), shuffled.get(x), 0.0);
        }
    }

    @Test
    void moreThanEightPointsAreKept() {
        // The builder's storage starts at 8 points and has to grow.
        InterpLUT.Builder builder = InterpLUT.builder(1);
        for (int i = 0; i < 20; i++) {
            builder.add(i, i * 2);
        }
        InterpLUT table = builder.build();
        assertEquals(20, table.size());
        assertEquals(38, table.get(19), EPSILON);
        assertEquals(21, table.get(10.5), EPSILON);
    }

    @Test
    void addingMorePointsAfterBuildingDoesNotChangeAnEarlierTable() {
        InterpLUT.Builder builder = InterpLUT.builder(1).add(0, 0).add(10, 10);
        InterpLUT first = builder.build();

        builder.add(20, 0);
        InterpLUT second = builder.build();

        assertEquals(2, first.size());
        assertEquals(3, second.size());
        assertEquals(100, first.get(100), EPSILON);
        assertEquals(-80, second.get(100), EPSILON);
    }

    @Test
    void rejectsTooFewPoints() {
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(1).build());
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(1).add(1, 1).build());
    }

    @Test
    void rejectsTwoPointsWithTheSameInput() {
        assertThrows(IllegalArgumentException.class,
                () -> InterpLUT.builder(1).add(1, 1).add(2, 5).add(1, 9).build());
    }

    @Test
    void rejectsValuesThatAreNotRealNumbers() {
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(1).add(Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(1).add(1, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> InterpLUT.builder(1).add(Double.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class,
                () -> InterpLUT.builder(1).add(1, Double.NEGATIVE_INFINITY));
    }
}
