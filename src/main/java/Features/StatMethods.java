package Features;

import com.github.sanity.pav.PairAdjacentViolators;
import com.github.sanity.pav.Point;
import kotlin.jvm.functions.Function1;
import org.apache.commons.math3.analysis.interpolation.LoessInterpolator;
import smile.stat.distribution.KernelDensity;

import java.util.*;
import java.util.stream.IntStream;

public class StatMethods {
    public static float mean(double[] vector) {
        float meanX = 0;
        for (double x : vector) {
            meanX += x;
        }
        return meanX / (float) vector.length;
    }

    public static float mean(float[] vector) {
        float meanX = 0;
        for (float x : vector) {
            meanX += x;
        }
        return meanX / (float) vector.length;
    }

    public static float mean(ArrayList<Float> vector) {
        float meanX = 0;
        for (float x : vector) {
            meanX += x;
        }
        return meanX / vector.size();
    }

    public static float variance(double[] vector) {
        int vecLength = vector.length;
        float mean = mean(vector);
        float var = 0;

        for (double v : vector) {
            var += Math.pow(v - mean, 2);
        }

        return var / (vecLength - 1);
    }

    public static float variance(double[] vector, float mean) {
        int vecLength = vector.length;
        float var = 0;

        for (double v : vector) {
            var += Math.pow(v - mean, 2);
        }

        return var / (vecLength - 1);
    }

    public static float variance(ArrayList<Float> vector, float mean) {
        int vecLength = vector.size();
        float var = 0;

        for (float v : vector) {
            var += Math.pow(v - mean, 2);
        }

        return var / (vecLength - 1);
    }

    //covariance
    public static float variance(double[] vectorX, double[] vectorY) {
        int vecLength = vectorX.length;
        float meanX = mean(vectorX);
        float meanY = mean(vectorY);

        float var = 0;

        for (int i = 0; i < vecLength; i++) {
            var += (vectorX[i] - meanX) * (vectorY[i] - meanY);
        }

        return var / (vecLength - 1);
    }

    public static float variance(double[] vectorX, float meanX, double[] vectorY, float meanY) {
        int vecLength = vectorX.length;

        float var = 0;

        for (int i = 0; i < vecLength; i++) {
            var += (vectorX[i] - meanX) * (vectorY[i] - meanY);
        }

        return var / (vecLength - 1);
    }

    public static float[] linearRegression(double[] x, double[] y) {
        assert x.length == y.length : "vectors must be of same length";

        //returns beta 0 and beta 1
        float meanX = mean(x);
        float meanY = mean(y);

        float varX = variance(x, meanX);

        float covar = variance(x, meanX, y, meanY);

        float beta1 = covar / varX;
        float beta0 = meanY - (beta1 * meanX);

        return new float[] {beta0, beta1};
    }

    public static float zscore(float x, float mean, float sd) {
        return ((x - mean) / sd);
    }

    //TODO: automatic bandwidth selection
    public static Function1<Double, Double> LOESS(double[][] bins, double bandwidth, int robustIters) {
        if (bandwidth <= 0) {
            System.out.println("bandwidth is set to " + bandwidth + " but it must be greater than 0. Setting it to 0.05");
            bandwidth = 0.05;
        }
        if (bandwidth > 1) {
            System.out.println("bandwidth is set to " + bandwidth + " but maximum allowed is 1. Setting it to 1");
            bandwidth = 1;
        }

        //need to sort arrays (DIA-U mzml not in order)
        int[] sortedIndices = IntStream.range(0, bins[0].length)
                .boxed().sorted(Comparator.comparingDouble(k -> bins[0][k])).mapToInt(ele -> ele).toArray();
        double[] newX = new double[sortedIndices.length];
        double[] newY = new double[sortedIndices.length];
        for (int i = 0; i < sortedIndices.length; i++) {
            newX[i] = bins[0][sortedIndices[i]];
        }
        for (int i = 0; i < sortedIndices.length; i++) {
            newY[i] = bins[1][sortedIndices[i]];
        }

        //solve monotonicity issue
        double compare = -1;
        for (int i = 0; i < newX.length; i++) {
            double d = newX[i];
            if (d == compare) {
                newX[i] = newX[i - 1] + 0.00000001; //arbitrary increment to handle smooth method
            } else {
                compare = d;
            }
        }

        //fit loess
        //may not need if sanity version uses spline
        LoessInterpolator loessInterpolator = null;
        double[] y = null;

        while (loessInterpolator == null) {
            try {
                if (bandwidth == 1d) {
                    loessInterpolator = new LoessInterpolator(bandwidth, robustIters);
                    y = loessInterpolator.smooth(newX, newY);

                    ArrayList<Integer> nanIdx = new ArrayList<>();
                    int i = 0;
                    for (double yval : y) {
                        if (Double.isNaN(yval)) {
                            nanIdx.add(i);
                        }
                        i += 1;
                    }

                    if (nanIdx.size() > 0) {
                        ArrayList<Double> newnewX = new ArrayList<>();
                        ArrayList<Double> newnewY = new ArrayList<>();
                        for (int j = 0; j < newX.length; j++) {
                            if (! nanIdx.contains(j)) {
                                newnewX.add(newX[j]);
                                newnewY.add(newY[j]);
                            }
                        }
                        newX = new double[newnewX.size()];
                        y = new double[newnewX.size()];
                        for (int j = 0; j < newnewX.size(); j++) {
                            newX[j] = newnewX.get(j);
                            y[j] = newnewY.get(j);
                        }
                    }
                } else {
                    loessInterpolator = new LoessInterpolator(bandwidth, robustIters);
                    y = loessInterpolator.smooth(newX, newY);
                    for (double yval : y) {
                        if (Double.isNaN(yval)) {
                            throw new Exception(bandwidth + " bandwidth is too small");
                        }
                    }
                }
            } catch (Exception e) {
                loessInterpolator = null;
                bandwidth = Math.min(bandwidth * 2d, 1);
            }
        }

        //isotonic regression
        List<Point> points = new LinkedList<>();
        for (int i = 0; i < y.length; i++) {
            points.add(new Point(newX[i], y[i]));
        }
        PairAdjacentViolators pav = new PairAdjacentViolators(points);

        return pav.interpolator(); //extrapolationStrategy can use flat or tangent (default tangent)
    }

    public static double residualSumOfSquares(double[] x, double[] y) {
        double sum = 0;
        for (int i = 0; i < x.length; i++) {
            sum += Math.pow(x[i] - y[i], 2);
        }
        return sum;
    }

    public static float[] movingAverage(float[] array, int windowSize) {
        int bl = array.length;
        float[] newStats = new float[bl];

        for (int i = 0; i < bl; i++) {
            float[] bin = Arrays.copyOfRange(array, Math.max(i - windowSize, 0), i + windowSize + 1);
            newStats[i] = mean(bin);
        }
        return newStats;
    }

    //get means and standard devs and interquartile ranges
    public static float[][] characterizebins(ArrayList<Float>[] bins, float IQR) {
        float[][] binStats = new float[bins.length][3]; //index by expRT, then mean or standard deviation
        for (int i = 0; i < bins.length; i++) {
            if (bins[i].size() == 0) {
                continue;
            } else if (bins[i].size() == 1) {
                binStats[i][0] = bins[i].get(0);
                binStats[i][1] = Float.MAX_VALUE;
                binStats[i][2] = Float.MAX_VALUE;
                continue;
            }

            //mean
            float m = StatMethods.mean(bins[i]);

            //standard dev
            float sd = (float) Math.sqrt(StatMethods.variance(bins[i], m));
            if (sd == 0f) {
                sd = Float.MAX_VALUE;
            }

            //interquartile range
            float plusMinus = IQR / 200f;
            float percentileIncrement = 1f / (float) (bins[i].size() - 1);
            int startIdx = Math.round(plusMinus / percentileIncrement);
            int endIdx = Math.round((plusMinus + .50f) / percentileIncrement);
            Collections.sort(bins[i]);
            float iqr = bins[i].get(endIdx) - bins[i].get(startIdx);
            if (iqr == 0f) {
                iqr = Float.MAX_VALUE;
            }

            binStats[i][0] = m;
            binStats[i][1] = sd;
            binStats[i][2] = iqr;
        }
        return binStats;
    }

    public static double probability(float exp, float pred, KernelDensity[] bins) {
        //get right bin to search
        KernelDensity kd = bins[Math.round(exp)];

        //check probability at point
        try {
            return kd.p(pred);
        } catch (Exception e) { //nothing in bin
            return 0;
        }
    }

    public static float probabilityWithUniformPrior(int unifPriorSize, float unifProb,
                                                      int binSize, float empiricalProb) {
        float w1 = (float) unifPriorSize / (float) (unifPriorSize + binSize);
        float w2 = (float) binSize / (float) (unifPriorSize + binSize);

        return w1 * unifProb + w2 * empiricalProb;
    }

    public static float median(ArrayList<Float> alist) {
        Collections.sort(alist);
        float median;
        if (alist.size() % 2 == 0)
            median = (alist.get(alist.size() / 2 - 1) + alist.get(alist.size() / 2)) / 2;
        else
            median = alist.get(alist.size() / 2);
        return median;
    }

    public static int consecutiveMatches(float[] array) {
        int maxConsecutive = 0;
        int currentScore = 0;

        for (float f : array) {
            if (f > 0f) {
                currentScore += 1;
            } else {
                if (currentScore > maxConsecutive) {
                    maxConsecutive = currentScore;
                }
                currentScore = 0;
            }
        }
        if (currentScore > maxConsecutive) {
            maxConsecutive = currentScore;
        }

        return maxConsecutive;
    }

    public static void main(String[] args) {
        System.out.println(variance(new double[] {1.0, 1.0}));
        System.out.println( 1f / Float.POSITIVE_INFINITY);
    }
}
