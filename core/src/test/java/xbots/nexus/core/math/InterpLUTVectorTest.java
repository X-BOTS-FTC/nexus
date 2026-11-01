package xbots.nexus.core.math;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InterpLUTVectorTest {

    private static final double EPSILON = 1e-9;

    private static InterpLUT scalar1D(double[] xs, double[] ys) {
        InterpLUT.Builder builder = InterpLUT.builder(1);
        for (int i = 0; i < xs.length; i++) {
            builder.add(xs[i], ys[i]);
        }
        return builder.build();
    }

    private static InterpLUT scalar2D(double[] xs, double[] ys, double[][] outputs) {
        InterpLUT.Builder builder = InterpLUT.builder(2);
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                builder.add(xs[i], ys[j], outputs[i][j]);
            }
        }
        return builder.build();
    }

    private static InterpLUT vector1D(double[] xs, double[][] outputs) {
        int outputsPerPoint = outputs[0].length;
        InterpLUT.Builder builder = InterpLUT.builder(1, outputsPerPoint);
        double[] point = new double[1 + outputsPerPoint];
        for (int i = 0; i < xs.length; i++) {
            point[0] = xs[i];
            System.arraycopy(outputs[i], 0, point, 1, outputsPerPoint);
            builder.add(point);
        }
        return builder.build();
    }

    private static InterpLUT vector2D(double[] xs, double[] ys, double[][][] outputs) {
        int outputsPerPoint = outputs[0][0].length;
        InterpLUT.Builder builder = InterpLUT.builder(2, outputsPerPoint);
        double[] point = new double[2 + outputsPerPoint];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                point[0] = xs[i];
                point[1] = ys[j];
                System.arraycopy(outputs[i][j], 0, point, 2, outputsPerPoint);
                builder.add(point);
            }
        }
        return builder.build();
    }

    private static double[] range(double start, double end, int samples) {
        double[] values = new double[samples];
        for (int i = 0; i < samples; i++) {
            values[i] = start + (end - start) * i / (samples - 1);
        }
        return values;
    }

    // ---------------------------------------------------- agreement with scalar tables

    @Test
    void oneInputMatchesSeparateScalarTables() {
        double[] xs = {0, 1, 2, 3};
        double[][] outputs = {
                {0, 10},
                {11, 20},
                {12, 22},
                {23, 24}
        };
        InterpLUT vector = vector1D(xs, outputs);
        InterpLUT scalar0 = scalar1D(xs, new double[] {0, 11, 12, 23});
        InterpLUT scalar1 = scalar1D(xs, new double[] {10, 20, 22, 24});

        for (double x : new double[] {0, 0.5, 1.5, 2.5, 3, 4}) {
            double[] all = vector.getOutputs(x);
            assertEquals(scalar0.get(x), all[0], 0.0);
            assertEquals(scalar1.get(x), all[1], 0.0);
            assertEquals(scalar0.get(x), vector.getOutput(0, x), 0.0);
            assertEquals(scalar1.get(x), vector.getOutput(1, x), 0.0);
        }
    }

    @Test
    void twoInputsMatchSeparateScalarTables() {
        double[] xs = {0, 0.5, 2};
        double[] ys = {0, 1.5, 2};
        double[][][] outputs = new double[3][3][2];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                outputs[i][j][0] = xs[i] * xs[i] - 2 * ys[j] + 3;
                outputs[i][j][1] = 5 * xs[i] + ys[j] * ys[j] - 1;
            }
        }
        InterpLUT vector = vector2D(xs, ys, outputs);

        double[][][] scalarValues = new double[2][3][3];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                scalarValues[0][i][j] = outputs[i][j][0];
                scalarValues[1][i][j] = outputs[i][j][1];
            }
        }
        InterpLUT scalar0 = scalar2D(xs, ys, scalarValues[0]);
        InterpLUT scalar1 = scalar2D(xs, ys, scalarValues[1]);

        for (double x : range(0, 2, 40)) {
            for (double y : range(0, 2, 40)) {
                double[] all = vector.getOutputs(x, y);
                assertEquals(scalar0.get(x, y), all[0], 0.0);
                assertEquals(scalar1.get(x, y), all[1], 0.0);
                assertEquals(scalar0.get(x, y), vector.getOutput(0, x, y), 0.0);
                assertEquals(scalar1.get(x, y), vector.getOutput(1, x, y), 0.0);
            }
        }
    }

    @Test
    void threeInputsMatchSeparateScalarTables() {
        double[] xs = {0, 1, 2};
        double[] ys = {0, 1, 2};
        double[] zs = {0, 1, 2};
        double[][][][] outputs = new double[3][3][3][2];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                for (int k = 0; k < zs.length; k++) {
                    outputs[i][j][k][0] = xs[i] + 2 * ys[j] - zs[k] + 1;
                    outputs[i][j][k][1] = -2 * xs[i] + ys[j] + 3 * zs[k] - 4;
                }
            }
        }

        InterpLUT.Builder builder = InterpLUT.builder(3, 2);
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                for (int k = 0; k < zs.length; k++) {
                    builder.add(xs[i], ys[j], zs[k],
                            outputs[i][j][k][0], outputs[i][j][k][1]);
                }
            }
        }
        InterpLUT vector = builder.build();

        double[][][] scalar0Values = new double[3][3][3];
        double[][][] scalar1Values = new double[3][3][3];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                for (int k = 0; k < zs.length; k++) {
                    scalar0Values[i][j][k] = outputs[i][j][k][0];
                    scalar1Values[i][j][k] = outputs[i][j][k][1];
                }
            }
        }

        // The scalar reference tables only need a few checks; comparing every
        // query against them would just repeat the 3D fold.
        InterpLUT scalar0 = scalar3D(xs, ys, zs, scalar0Values);
        InterpLUT scalar1 = scalar3D(xs, ys, zs, scalar1Values);

        for (double x : range(0, 2, 9)) {
            for (double y : range(0, 2, 9)) {
                for (double z : range(0, 2, 9)) {
                    double[] all = vector.getOutputs(x, y, z);
                    assertEquals(scalar0.get(x, y, z), all[0], 0.0);
                    assertEquals(scalar1.get(x, y, z), all[1], 0.0);
                    assertEquals(all[0], vector.getOutput(0, x, y, z), 0.0);
                    assertEquals(all[1], vector.getOutput(1, x, y, z), 0.0);

                    double[] filled = new double[2];
                    vector.getOutputs(new double[] {x, y, z}, filled);
                    assertArrayEquals(all, filled, 0.0);
                }
            }
        }
    }

    @Test
    void supportsFourDimensions() {
        double[] xs = {0, 1};
        double[] ys = {0, 1};
        double[] zs = {0, 1};
        double[] ws = {0, 1};
        InterpLUT.Builder builder = InterpLUT.builder(4, 2);
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                for (int k = 0; k < zs.length; k++) {
                    for (int l = 0; l < ws.length; l++) {
                        double x = xs[i];
                        double y = ys[j];
                        double z = zs[k];
                        double w = ws[l];
                        builder.add(x, y, z, w,
                                2 * x - 3 * y + 0.5 * z + 4 * w + 7,
                                -x + y - 2 * z + 3 * w - 1);
                    }
                }
            }
        }
        InterpLUT vector = builder.build();

        double[][] queries = {
                {0.1, 0.9, 0.25, 0.75},
                {0.5, 0.5, 0.5, 0.5},
                {0.9, 0.1, 0.75, 0.25}
        };
        for (double[] query : queries) {
            double[] all = vector.getOutputs(query[0], query[1], query[2], query[3]);
            assertEquals(2 * query[0] - 3 * query[1] + 0.5 * query[2] + 4 * query[3] + 7,
                    all[0], EPSILON);
            assertEquals(-query[0] + query[1] - 2 * query[2] + 3 * query[3] - 1,
                    all[1], EPSILON);
            assertEquals(all[0],
                    vector.getOutput(0, query[0], query[1], query[2], query[3]), 0.0);
            assertEquals(all[1],
                    vector.getOutput(1, query[0], query[1], query[2], query[3]), 0.0);
        }
    }

    private static InterpLUT scalar3D(double[] xs, double[] ys, double[] zs,
            double[][][] outputs) {
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

    // ---------------------------------------------------- exactness and shape

    @Test
    void passesExactlyThroughEveryPointForEveryOutput() {
        double[] xs = {0, 1, 2};
        double[] ys = {10, 20};
        double[][][] outputs = new double[3][2][3];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                for (int output = 0; output < 3; output++) {
                    outputs[i][j][output] = i * 100 + j * 10 + output;
                }
            }
        }
        InterpLUT vector = vector2D(xs, ys, outputs);

        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                double[] all = vector.getOutputs(xs[i], ys[j]);
                assertArrayEquals(outputs[i][j], all, 0.0);
            }
        }
    }

    @Test
    void linearFunctionsAreReproducedForEveryOutput() {
        double[] xs = {0, 0.5, 2};
        double[] ys = {0, 1.5, 2};
        double[][][] outputs = new double[3][3][2];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                outputs[i][j][0] = 2 * xs[i] - 3 * ys[j] + 7;
                outputs[i][j][1] = -xs[i] + 4 * ys[j] - 2;
            }
        }
        InterpLUT vector = vector2D(xs, ys, outputs);

        double[][] queries = {
                {0.25, 0.75},
                {1.5, 1.5},
                {0.1, 1.9},
                {1.95, 0.05}
        };
        for (double[] query : queries) {
            double[] all = vector.getOutputs(query[0], query[1]);
            assertEquals(2 * query[0] - 3 * query[1] + 7, all[0], EPSILON);
            assertEquals(-query[0] + 4 * query[1] - 2, all[1], EPSILON);
        }
    }

    @Test
    void neverOvershootsEachOutputsRange() {
        double[] xs = {0, 1, 2, 3};
        double[] ys = {0, 1, 2, 3};
        double[][] data0 = {
                {0, 10, 8, 0},
                {10, 0, 5, 2},
                {2, 5, 0, 10},
                {0, 3, 4, 0}
        };
        double[][] data1 = {
                {5, 1, 9, 2},
                {3, 8, 0, 6},
                {7, 2, 4, 1},
                {0, 9, 3, 5}
        };
        double[][][] outputs = new double[4][4][2];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                outputs[i][j][0] = data0[i][j];
                outputs[i][j][1] = data1[i][j];
            }
        }
        InterpLUT vector = vector2D(xs, ys, outputs);

        for (double x : range(0, 3, 80)) {
            for (double y : range(0, 3, 80)) {
                double[] all = vector.getOutputs(x, y);
                assertTrue(all[0] >= 0 - EPSILON, "first output below its range: " + all[0]);
                assertTrue(all[0] <= 10 + EPSILON, "first output above its range: " + all[0]);
                assertTrue(all[1] >= 0 - EPSILON, "second output below its range: " + all[1]);
                assertTrue(all[1] <= 9 + EPSILON, "second output above its range: " + all[1]);
            }
        }
    }

    // ---------------------------------------------------- edges

    @Test
    void clampsPerAxisAndNanFillsAllOutputs() {
        double[] xs = {1, 2, 3};
        double[] ys = {10, 20, 30};
        double[][][] outputs = new double[3][3][2];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                outputs[i][j][0] = i + j;
                outputs[i][j][1] = i * 10 - j;
            }
        }
        InterpLUT vector = vector2D(xs, ys, outputs);

        assertArrayEquals(vector.getOutputs(1, 10), vector.getOutputs(0, 0), 0.0);
        assertArrayEquals(vector.getOutputs(3, 30), vector.getOutputs(100, 100), 0.0);
        assertArrayEquals(new double[] {0, 0}, vector.getOutputs(0, 0), 0.0);
        assertArrayEquals(new double[] {1, -1}, vector.getOutputs(0, 20), 0.0);
        assertArrayEquals(new double[] {4, 18}, vector.getOutputs(100, 100), 0.0);

        double[] nan = vector.getOutputs(Double.NaN, 15);
        assertEquals(2, nan.length);
        assertTrue(Double.isNaN(nan[0]));
        assertTrue(Double.isNaN(nan[1]));
        assertTrue(Double.isNaN(vector.getOutput(0, Double.NaN, 15)));

        double[] filled = {123, 456};
        vector.getOutputs(new double[] {1.0, Double.NaN}, filled);
        assertTrue(Double.isNaN(filled[0]));
        assertTrue(Double.isNaN(filled[1]));
    }

    // ---------------------------------------------------- validation and metadata

    @Test
    void reportsDimensionsAndGridMetadata() {
        double[] xs = {1, 2, 3};
        double[] ys = {10, 20, 30, 40};
        double[][][] outputs = new double[3][4][3];
        InterpLUT vector = vector2D(xs, ys, outputs);

        assertEquals(2, vector.inputDimensions());
        assertEquals(3, vector.outputDimensions());
        assertEquals(12, vector.size());
        assertEquals(3, vector.size(0));
        assertEquals(4, vector.size(1));
        assertEquals(1, vector.minInput(0), EPSILON);
        assertEquals(3, vector.maxInput(0), EPSILON);
        assertEquals(10, vector.minInput(1), EPSILON);
        assertEquals(40, vector.maxInput(1), EPSILON);

        assertThrows(IllegalStateException.class, vector::minInput);
        assertThrows(IllegalStateException.class, vector::maxInput);

        InterpLUT oneInput = vector1D(new double[] {1, 3},
                new double[][] {{0, 1}, {2, 3}});
        assertEquals(1, oneInput.minInput(), EPSILON);
        assertEquals(3, oneInput.maxInput(), EPSILON);
    }

    @Test
    void rejectsInvalidOutputsAndArity() {
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(0, 1));
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> InterpLUT.builder(2, 2).add(0, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> InterpLUT.builder(1, 2).add(0, 1));

        // Missing (1, 1).
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(2, 2)
                .add(0, 0, 1, 2)
                .add(0, 1, 3, 4)
                .add(1, 0, 5, 6)
                .build());

        // Duplicate (1, 1).
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(2, 2)
                .add(0, 0, 1, 2)
                .add(0, 1, 3, 4)
                .add(1, 0, 5, 6)
                .add(1, 1, 7, 8)
                .add(1, 1, 9, 10)
                .build());

        // Only one value on input 0.
        assertThrows(IllegalArgumentException.class, () -> InterpLUT.builder(2, 2)
                .add(0, 0, 1, 2)
                .add(0, 1, 3, 4)
                .build());

        assertThrows(IllegalArgumentException.class,
                () -> InterpLUT.builder(2, 2).add(0, 0, Double.NaN, 2));
        assertThrows(IllegalArgumentException.class,
                () -> InterpLUT.builder(2, 2).add(0, 0, 1, Double.POSITIVE_INFINITY));

        InterpLUT vector = vector2D(new double[] {0, 1}, new double[] {0, 1},
                new double[][][] {{{0, 1}, {2, 3}}, {{4, 5}, {6, 7}}});

        assertThrows(IllegalArgumentException.class, () -> vector.getOutput(-1, 0.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> vector.getOutput(2, 0.5, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> vector.getOutputs(new double[] {0.5, 0.5}, new double[1]));
        assertThrows(IllegalArgumentException.class, () -> vector.getOutputs(0.5, 0.5, 0.5));
    }

    @Test
    void scalarGetRejectsMultiOutputTables() {
        InterpLUT oneInput = vector1D(new double[] {0, 1},
                new double[][] {{0, 10}, {1, 20}});
        assertThrows(IllegalArgumentException.class, () -> oneInput.get(0.5));
        assertThrows(IllegalArgumentException.class, () -> oneInput.get(new double[] {0.5}));

        InterpLUT twoInputs = vector2D(new double[] {0, 1}, new double[] {0, 1},
                new double[][][] {{{0, 1}, {2, 3}}, {{4, 5}, {6, 7}}});
        assertThrows(IllegalArgumentException.class, () -> twoInputs.get(0.5, 0.5));
    }

    // ---------------------------------------------------- building

    @Test
    void builderReuseDoesNotChangeAnEarlierTable() {
        InterpLUT.Builder builder = InterpLUT.builder(2, 2)
                .add(0, 0, 0, 10)
                .add(0, 1, 1, 11)
                .add(1, 0, 2, 12)
                .add(1, 1, 3, 13);
        InterpLUT first = builder.build();

        builder.add(2, 0, 4, 14).add(2, 1, 5, 15);
        InterpLUT second = builder.build();

        assertEquals(4, first.size());
        assertEquals(2, first.size(0));
        assertEquals(6, second.size());
        assertEquals(3, second.size(0));
        assertArrayEquals(new double[] {2, 12}, first.getOutputs(2.0, 0.0), EPSILON);
        assertArrayEquals(new double[] {4, 14}, second.getOutputs(2.0, 0.0), EPSILON);
    }

    @Test
    void pointsMayBeAddedInAnyOrder() {
        double[] xs = {0, 1, 2};
        double[] ys = {0, 1};
        double[][][] outputs = new double[3][2][2];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                outputs[i][j][0] = i * 10 + j;
                outputs[i][j][1] = i - j;
            }
        }
        InterpLUT ordered = vector2D(xs, ys, outputs);

        InterpLUT.Builder builder = InterpLUT.builder(2, 2);
        for (int i = xs.length - 1; i >= 0; i--) {
            for (int j = ys.length - 1; j >= 0; j--) {
                builder.add(xs[i], ys[j], outputs[i][j][0], outputs[i][j][1]);
            }
        }
        InterpLUT shuffled = builder.build();

        for (double x : range(0, 2, 50)) {
            for (double y : range(0, 1, 50)) {
                assertArrayEquals(ordered.getOutputs(x, y), shuffled.getOutputs(x, y), 0.0);
            }
        }
    }
}
