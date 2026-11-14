package xbots.nexus.core.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InterpLUTNDTest {

    private static final double EPSILON = 1e-9;

    private static InterpLUT table1D(double[] xs, double[] ys) {
        InterpLUT.Builder builder = InterpLUT.builder(1);
        for (int i = 0; i < xs.length; i++) {
            builder.add(xs[i], ys[i]);
        }
        return builder.build();
    }

    private static InterpLUT table2D(double[] xs, double[] ys, double[][] outputs) {
        InterpLUT.Builder builder = InterpLUT.builder(2);
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                builder.add(xs[i], ys[j], outputs[i][j]);
            }
        }
        return builder.build();
    }

    private static InterpLUT table3D(double[] xs, double[] ys, double[] zs, double[][][] outputs) {
        InterpLUT.Builder builder = InterpLUT.builder(3);
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                for (int k = 0; k < zs.length; k++) {
                    builder.add(xs[i], ys[j], zs[k], outputs[i][j][k]);
                }
            }
        }
        return builder.build();
    }

    // Evenly spaced samples, including both ends.
    private static double[] range(double start, double end, int samples) {
        double[] values = new double[samples];
        for (int i = 0; i < samples; i++) {
            values[i] = start + (end - start) * i / (samples - 1);
        }
        return values;
    }

    private static double plane(double x, double y) {
        return 2 * x - 3 * y + 7;
    }

    private static double hyperplane(double x, double y, double z) {
        return 2 * x - 3 * y + 0.5 * z + 7;
    }

    private static double fourDimensionalPlane(double x, double y, double z, double w) {
        return 2 * x - 3 * y + 0.5 * z + 4 * w + 7;
    }

    // ---------------------------------------------------- exactness

    @Test
    void passesExactlyThroughEveryPointInTwoDimensions() {
        double[] xs = {0, 1, 2};
        double[] ys = {10, 20, 30};
        double[][] outputs = {
                {0, 1, 2},
                {10, 11, 12},
                {20, 21, 22}
        };
        InterpLUT table = table2D(xs, ys, outputs);

        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                assertEquals(outputs[i][j], table.get(xs[i], ys[j]), EPSILON,
                        "grid point " + i + ", " + j);
            }
        }
    }

    @Test
    void passesExactlyThroughEveryPointInThreeDimensions() {
        double[] xs = {0, 1, 2};
        double[] ys = {0, 1};
        double[] zs = {0, 1, 2};
        double[][][] outputs = new double[3][2][3];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                for (int k = 0; k < zs.length; k++) {
                    outputs[i][j][k] = i * 100 + j * 10 + k;
                }
            }
        }
        InterpLUT table = table3D(xs, ys, zs, outputs);

        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                for (int k = 0; k < zs.length; k++) {
                    assertEquals(outputs[i][j][k], table.get(xs[i], ys[j], zs[k]), EPSILON,
                            "grid point " + i + ", " + j + ", " + k);
                }
            }
        }
    }

    @Test
    void linearFunctionsAreReproducedExactly() {
        double[] xs = {0, 0.5, 2};
        double[] ys = {0, 1.5, 2};
        double[][] outputs2 = new double[3][3];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                outputs2[i][j] = plane(xs[i], ys[j]);
            }
        }
        InterpLUT table2 = table2D(xs, ys, outputs2);

        double[] zs = {0, 1, 2};
        double[][][] outputs3 = new double[3][3][3];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                for (int k = 0; k < zs.length; k++) {
                    outputs3[i][j][k] = hyperplane(xs[i], ys[j], zs[k]);
                }
            }
        }
        InterpLUT table3 = table3D(xs, ys, zs, outputs3);

        double[][] queries2 = {
                {0.25, 0.75},
                {1.5, 1.5},
                {0.1, 1.9},
                {1.95, 0.05}
        };
        for (double[] query : queries2) {
            assertEquals(plane(query[0], query[1]),
                    table2.get(query[0], query[1]), EPSILON);
        }

        double[][] queries3 = {
                {0.25, 0.75, 1.5},
                {1.5, 1.5, 1.5},
                {0.1, 1.9, 0.4},
                {1.95, 0.05, 1.25}
        };
        for (double[] query : queries3) {
            assertEquals(hyperplane(query[0], query[1], query[2]),
                    table3.get(query[0], query[1], query[2]), EPSILON);
        }
    }

    @Test
    void supportsFourDimensions() {
        double[] xs = {0, 1, 2};
        double[] ys = {0, 1, 2};
        double[] zs = {0, 1, 2};
        double[] ws = {0, 1, 2};
        InterpLUT.Builder builder = InterpLUT.builder(4);
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                for (int k = 0; k < zs.length; k++) {
                    for (int l = 0; l < ws.length; l++) {
                        builder.add(xs[i], ys[j], zs[k], ws[l],
                                fourDimensionalPlane(xs[i], ys[j], zs[k], ws[l]));
                    }
                }
            }
        }
        InterpLUT table = builder.build();

        assertEquals(4, table.inputDimensions());
        assertEquals(1, table.outputDimensions());
        assertEquals(81, table.size());

        double[][] queries = {
                {0.25, 0.75, 1.5, 0.5},
                {1.5, 1.5, 1.5, 1.5},
                {0.1, 1.9, 0.4, 2.0},
                {1.95, 0.05, 1.25, 0.75}
        };
        for (double[] query : queries) {
            assertEquals(fourDimensionalPlane(query[0], query[1], query[2], query[3]),
                    table.get(query[0], query[1], query[2], query[3]), EPSILON);
        }

        assertEquals(fourDimensionalPlane(100, 100, 100, 100),
                table.get(100, 100, 100, 100), EPSILON);
        assertTrue(Double.isNaN(table.get(Double.POSITIVE_INFINITY, 0, 0, 0)));
    }

    @Test
    void constantAlongOneAxisReducesToTheOtherAxis() {
        double[] xs = {0, 1, 2, 3};
        double[] ys = {10, 20, 30};
        double[] profileAlongX = {0, 5, 6, 20};

        double[][] outputsX = new double[4][3];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                outputsX[i][j] = profileAlongX[i];
            }
        }
        InterpLUT tableX = table2D(xs, ys, outputsX);
        InterpLUT oneDimensionalX = table1D(xs, profileAlongX);

        double[] yQueries = {10, 17.3, 25, 30};
        for (double x : range(0, 3, 50)) {
            for (double y : yQueries) {
                assertEquals(oneDimensionalX.get(x), tableX.get(x, y), 1e-12);
            }
        }

        double[] profileAlongY = {0, 3, 7};
        double[][] outputsY = new double[4][3];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                outputsY[i][j] = profileAlongY[j];
            }
        }
        InterpLUT tableY = table2D(xs, ys, outputsY);
        InterpLUT oneDimensionalY = table1D(ys, profileAlongY);

        double[] xQueries = {0, 1.2, 2.7, 3};
        for (double y : range(10, 30, 50)) {
            for (double x : xQueries) {
                assertEquals(oneDimensionalY.get(y), tableY.get(x, y), 1e-12);
            }
        }
    }

    // ---------------------------------------------------- shape

    @Test
    void neverOvershootsTheSmallestOrLargestOutput() {
        double[] xs = {0, 1, 2, 3};
        double[] ys = {0, 1, 2, 3};
        double[][] outputs = {
                {0, 10, 8, 0},
                {10, 0, 5, 2},
                {2, 5, 0, 10},
                {0, 3, 4, 0}
        };
        InterpLUT table = table2D(xs, ys, outputs);

        for (double x : range(0, 3, 80)) {
            for (double y : range(0, 3, 80)) {
                double value = table.get(x, y);
                assertTrue(value >= 0 - EPSILON, "below the lowest output: " + value);
                assertTrue(value <= 10 + EPSILON, "above the highest output: " + value);
            }
        }
    }

    @Test
    void additiveMonotoneDataStaysMonotoneAlongEachAxis() {
        double[] xs = {0, 1, 2, 3};
        double[] ys = {0, 1, 2};
        double[] left = {0, 5, 6, 20};
        double[] right = {0, 2, 3};
        double[][] outputs = new double[4][3];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                outputs[i][j] = left[i] + right[j];
            }
        }
        InterpLUT table = table2D(xs, ys, outputs);

        for (double y : new double[] {0, 0.4, 1.5, 2}) {
            double previous = table.get(0, y);
            for (double x : range(0, 3, 200)) {
                double value = table.get(x, y);
                assertTrue(value >= previous - EPSILON,
                        "went down in x at y = " + y + ": " + previous + " -> " + value);
                previous = value;
            }
        }

        for (double x : new double[] {0, 0.6, 1.5, 2.5, 3}) {
            double previous = table.get(x, 0);
            for (double y : range(0, 2, 200)) {
                double value = table.get(x, y);
                assertTrue(value >= previous - EPSILON,
                        "went down in y at x = " + x + ": " + previous + " -> " + value);
                previous = value;
            }
        }
    }

    @Test
    void aFlatGridCellStaysPerfectlyFlat() {
        double[] xs = {0, 1, 2, 3};
        double[] ys = {0, 1, 2, 3};
        double[][] outputs = new double[4][4];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                outputs[i][j] = i + j;
            }
        }
        outputs[1][1] = 5;
        outputs[1][2] = 5;
        outputs[2][1] = 5;
        outputs[2][2] = 5;

        InterpLUT table = table2D(xs, ys, outputs);
        for (double x : range(1, 2, 20)) {
            for (double y : range(1, 2, 20)) {
                assertEquals(5, table.get(x, y), 1e-12);
            }
        }
    }

    // ---------------------------------------------------- edges

    @Test
    void extrapolatesEachAxisIndependently() {
        double[] xs = {1, 2, 3};
        double[] ys = {10, 20, 30};
        double[][] outputs = {
                {1, 2, 3},
                {4, 5, 6},
                {7, 8, 9}
        };
        InterpLUT table = table2D(xs, ys, outputs);

        // The data is the linear function 3x + y/10 - 3.
        assertEquals(-1.5, table.get(0, 15), EPSILON);
        assertEquals(-313, table.get(-100, -100), EPSILON);
        assertEquals(3.5, table.get(2, 5), EPSILON);
        assertEquals(298, table.get(100, 10), EPSILON);
        assertEquals(307, table.get(100, 100), EPSILON);
        assertTrue(Double.isNaN(table.get(Double.NEGATIVE_INFINITY, 15)));
        assertTrue(Double.isNaN(table.get(2, Double.POSITIVE_INFINITY)));
    }

    @Test
    void supportsNegativeBreakpoints() {
        double[] xs = {-2, -1, 0};
        double[] ys = {-10, 0, 10};
        double[][] outputs = new double[3][3];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                outputs[i][j] = xs[i] + 2 * ys[j];
            }
        }
        InterpLUT table = table2D(xs, ys, outputs);

        assertEquals(-1.5 + 2 * -5.0, table.get(-1.5, -5.0), EPSILON);
        assertEquals(-100 + 2 * -100.0, table.get(-100, -100), EPSILON);
        assertEquals(100 + 2 * 100.0, table.get(100, 100), EPSILON);
        assertTrue(Double.isNaN(table.get(Double.NEGATIVE_INFINITY, -5.0)));
        assertTrue(Double.isNaN(table.get(0.0, Double.POSITIVE_INFINITY)));
    }

    @Test
    void fillsMissingGridPoints() {
        // Missing (1, 1). The x = 1 line has only y = 0, so the whole line
        // becomes constant.
        InterpLUT table = InterpLUT.builder(2)
                .add(0, 0, 0)
                .add(0, 1, 10)
                .add(1, 0, 20)
                .build();
        assertEquals(4, table.size());
        assertEquals(0, table.get(0, 0), EPSILON);
        assertEquals(10, table.get(0, 1), EPSILON);
        assertEquals(20, table.get(1, 0), EPSILON);
        assertEquals(20, table.get(1, 1), EPSILON);

        // Missing middle values are filled by linear interpolation along y.
        InterpLUT interpolated = InterpLUT.builder(2)
                .add(0, 0, 0)
                .add(0, 2, 20)
                .add(1, 0, 10)
                .add(1, 2, 30)
                .build();
        assertEquals(10, interpolated.get(0, 1), EPSILON);
        assertEquals(20, interpolated.get(1, 1), EPSILON);

        // The (x = 1, y = 1) line has no samples, so it copies the nearest
        // known point. The two nearest known points are tied; the earlier one
        // wins.
        InterpLUT fallback = InterpLUT.builder(3)
                .add(0, 0, 0, 1)
                .add(0, 1, 1, 2)
                .add(1, 0, 1, 3)
                .build();
        assertEquals(2, fallback.get(1, 1, 0), EPSILON);
        assertEquals(2, fallback.get(1, 1, 1), EPSILON);

        // Missing values outside the known range are extrapolated.
        InterpLUT extrapolated = InterpLUT.builder(2)
                .add(0, 1, 10)
                .add(0, 2, 20)
                .add(1, 1, 30)
                .add(1, 2, 40)
                .build();
        assertEquals(0, extrapolated.get(0, 0), EPSILON);
        assertEquals(20, extrapolated.get(1, 0), EPSILON);

        // Non-uniform breakpoints are interpolated by coordinate, not index.
        InterpLUT nonUniform = InterpLUT.builder(2)
                .add(0, 0, 0)
                .add(0, 3, 30)
                .add(1, 0, 0)
                .add(1, 3, 30)
                .build();
        assertEquals(10, nonUniform.get(0, 1), EPSILON);
        assertEquals(20, nonUniform.get(0, 2), EPSILON);
    }

    @Test
    void diagonalPointsFillEachLine() {
        InterpLUT table = InterpLUT.builder(2)
                .add(10, 30, 100)
                .add(5, 15, 50)
                .build();

        assertEquals(4, table.size());
        assertEquals(100, table.get(10, 30), EPSILON);
        assertEquals(50, table.get(5, 15), EPSILON);
        // The last input is y, so each x line is held constant.
        assertEquals(50, table.get(5, 30), EPSILON);
        assertEquals(100, table.get(10, 15), EPSILON);
    }

    @Test
    void oneValueAxisIsConstant() {
        InterpLUT table = InterpLUT.builder(2)
                .add(0, 0, 0)
                .add(0, 1, 10)
                .add(0, 2, 20)
                .build();
        assertEquals(2, table.inputDimensions());
        assertEquals(1, table.size(0));
        assertEquals(3, table.size());
        assertEquals(5, table.get(-100, 0.5), EPSILON);
        assertEquals(15, table.get(100, 1.5), EPSILON);
        assertTrue(Double.isNaN(table.get(Double.NaN, 1.0)));

        InterpLUT trailingConstant = InterpLUT.builder(2)
                .add(0, 0, 0)
                .add(1, 0, 10)
                .add(2, 0, 20)
                .build();
        assertEquals(0, trailingConstant.minInput(1), EPSILON);
        assertEquals(0, trailingConstant.maxInput(1), EPSILON);
        assertEquals(5, trailingConstant.get(0.5, -100), EPSILON);

        InterpLUT middleConstant = InterpLUT.builder(3)
                .add(0, 0, 0, 0)
                .add(0, 0, 1, 10)
                .add(0, 0, 2, 20)
                .add(1, 0, 0, 0)
                .add(1, 0, 1, 10)
                .add(1, 0, 2, 20)
                .build();
        assertEquals(5, middleConstant.get(0.5, 100, 0.5), EPSILON);

        InterpLUT singlePoint = InterpLUT.builder(2)
                .add(0, 0, 7)
                .build();
        assertEquals(1, singlePoint.size());
        assertEquals(7, singlePoint.get(100, -100), EPSILON);
        assertTrue(Double.isNaN(singlePoint.get(Double.POSITIVE_INFINITY, 0)));
    }

    @Test
    void anyNanCoordinateGivesNan() {
        InterpLUT table2 = table2D(new double[] {0, 1}, new double[] {0, 1},
                new double[][] {{0, 1}, {2, 3}});
        assertTrue(Double.isNaN(table2.get(Double.NaN, 0.5)));
        assertTrue(Double.isNaN(table2.get(0.5, Double.NaN)));

        double[][][] outputs3 = {{{0, 1}, {2, 3}}, {{4, 5}, {6, 7}}};
        InterpLUT table3 = table3D(new double[] {0, 1}, new double[] {0, 1},
                new double[] {0, 1}, outputs3);
        assertTrue(Double.isNaN(table3.get(0.5, Double.NaN, 0.5)));
        assertTrue(Double.isNaN(table3.get(0.5, 0.5, Double.NaN)));
    }

    // ---------------------------------------------------- building

    @Test
    void pointsMayBeAddedInAnyOrder() {
        double[] xs = {0, 1, 2};
        double[] ys = {10, 20, 30};
        double[][] outputs = {
                {0, 1, 2},
                {10, 11, 12},
                {20, 21, 22}
        };
        InterpLUT ordered = table2D(xs, ys, outputs);

        InterpLUT.Builder builder = InterpLUT.builder(2);
        for (int i = xs.length - 1; i >= 0; i--) {
            for (int j = ys.length - 1; j >= 0; j--) {
                builder.add(xs[i], ys[j], outputs[i][j]);
            }
        }
        InterpLUT shuffled = builder.build();

        for (double x : range(0, 2, 50)) {
            for (double y : range(10, 30, 50)) {
                assertEquals(ordered.get(x, y), shuffled.get(x, y), 0.0);
            }
        }
    }

    @Test
    void builderReuseDoesNotChangeAnEarlierTable() {
        InterpLUT.Builder builder = InterpLUT.builder(2)
                .add(0, 0, 0)
                .add(0, 1, 1)
                .add(1, 0, 10)
                .add(1, 1, 11);
        InterpLUT first = builder.build();

        builder.add(2, 0, 100).add(2, 1, 101);
        InterpLUT second = builder.build();

        assertEquals(4, first.size());
        assertEquals(2, first.size(0));
        assertEquals(6, second.size());
        assertEquals(3, second.size(0));
        assertEquals(20, first.get(2.0, 0.0), EPSILON); // extrapolated from x = 1
        assertEquals(100, second.get(2.0, 0.0), EPSILON);
    }

    @Test
    void reportsItsDimensionsAndRanges() {
        double[] xs = {1, 2, 3};
        double[] ys = {10, 20, 30, 40};
        double[][] outputs = new double[3][4];
        InterpLUT table = table2D(xs, ys, outputs);

        assertEquals(2, table.inputDimensions());
        assertEquals(1, table.outputDimensions());
        assertEquals(12, table.size());
        assertEquals(3, table.size(0));
        assertEquals(4, table.size(1));
        assertEquals(1, table.minInput(0), EPSILON);
        assertEquals(3, table.maxInput(0), EPSILON);
        assertEquals(10, table.minInput(1), EPSILON);
        assertEquals(40, table.maxInput(1), EPSILON);

        assertThrows(IllegalStateException.class, table::minInput);
        assertThrows(IllegalStateException.class, table::maxInput);
        assertThrows(IllegalArgumentException.class, () -> table.size(2));
        assertThrows(IllegalArgumentException.class, () -> table.minInput(-1));
    }

    @Test
    void rejectsInvalidGridsAndArity() {
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(0));
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(1).build());
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(2).build());
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(1).add());
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(1).add(1.0));
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(2).add(1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> InterpLUT.builder(1).add(0, 0).add(0, 0, 1));

        // Duplicate (1, 1).
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(2)
                .add(0, 0, 1)
                .add(0, 1, 2)
                .add(1, 0, 3)
                .add(1, 1, 4)
                .add(1, 1, 5)
                .build());

        assertThrows(IllegalArgumentException.class,
                () -> InterpLUT.builder(2).add(Double.NaN, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> InterpLUT.builder(2).add(0, 0, Double.POSITIVE_INFINITY));

        InterpLUT table = table2D(new double[] {0, 1}, new double[] {0, 1},
                new double[][] {{0, 1}, {2, 3}});
        assertThrows(IllegalArgumentException.class, () -> table.get(1.0));
        assertThrows(IllegalArgumentException.class, () -> table.get(1.0, 2.0, 3.0));
        assertThrows(NullPointerException.class, () -> table.get((double[]) null));
    }

    @Test
    void scratchBuffersAreReusedAcrossCalls() {
        double[] xs = {0, 1, 2};
        double[] ys = {0, 1, 2};
        double[][] outputs2 = new double[3][3];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                outputs2[i][j] = plane(xs[i], ys[j]);
            }
        }
        InterpLUT table2 = table2D(xs, ys, outputs2);

        double[] zs = {0, 1, 2};
        double[][][] outputs3 = new double[3][3][3];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                for (int k = 0; k < zs.length; k++) {
                    outputs3[i][j][k] = hyperplane(xs[i], ys[j], zs[k]) + zs[k];
                }
            }
        }
        InterpLUT table3 = table3D(xs, ys, zs, outputs3);

        double[] zQueries = {0, 0.5, 1.5, 2};
        for (double x : range(0, 2, 25)) {
            for (double y : range(0, 2, 25)) {
                for (double z : zQueries) {
                    assertEquals(plane(x, y), table2.get(x, y), EPSILON);
                    assertEquals(hyperplane(x, y, z) + z, table3.get(x, y, z), EPSILON);
                }
            }
        }
    }

    @Test
    void getAcceptsAReusedCoordinateArray() {
        InterpLUT table = table2D(new double[] {0, 1}, new double[] {0, 1},
                new double[][] {{0, 1}, {2, 3}});

        double[] query = new double[2];
        query[0] = 0.25;
        query[1] = 0.75;
        assertEquals(table.get(0.25, 0.75), table.get(query), 0.0);

        query[0] = 1.5;
        query[1] = 0.5;
        assertEquals(table.get(1.5, 0.5), table.get(query), 0.0);

        InterpLUT oneDimensional = table1D(new double[] {0, 1, 2}, new double[] {0, 1, 4});
        double[] oneCoordinate = {0.5};
        assertEquals(oneDimensional.get(0.5), oneDimensional.get(oneCoordinate), 0.0);
    }
}
