package Features;

import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import umich.ms.fileio.exceptions.FileParsingException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.stream.IntStream;

import static Features.floatUtils.floatToDouble;
//TODO: also square root intensities? Squaring intensities may help for single cell data
public class spectrumComparison {
    float[] predMZs;
    float[] predIntensities;
    float[] matchedIntensities;
    float[] unitNormMatchedIntensities;
    float[] unitNormPredIntensities;
    float[] sum1MatchedIntensities;
    float[] sum1PredIntensities;
    LinkedHashSet<Integer> matchedIdx = new LinkedHashSet<Integer>();
    private static ArrayList<Float> tmpMZs = new ArrayList<Float>();
    private static ArrayList<Float> tmpInts = new ArrayList<Float>();
    private static PearsonsCorrelation pc = new PearsonsCorrelation();

    public spectrumComparison(float[] eMZs, float[] eIntensities,
                              float[] pMZs, float[] pIntensities,
                              boolean filterTop, boolean filterBase) {
        predMZs = pMZs;
        predIntensities = pIntensities;

        if (filterBase) {
            this.filterIntensitiesByPercentage(Constants.percentBasePeak);
        }
        if (filterTop) {
            this.filterTopFragments();
        }

        int[] sortedIndices = IntStream.range(0, predMZs.length)
                .boxed().sorted((k, j) -> Float.compare(predMZs[k], predMZs[j]))
                .mapToInt(ele -> ele).toArray();
        pMZs = predMZs;
        pIntensities = predIntensities;
        predMZs = new float[predMZs.length];
        predIntensities = new float[predIntensities.length];
        for (int i = 0; i < sortedIndices.length; i++) {
            predMZs[i] = pMZs[sortedIndices[i]];
            predIntensities[i] = pIntensities[sortedIndices[i]];
        }

        matchedIntensities = this.getMatchedIntensities(eMZs, eIntensities);
    }

    public spectrumComparison(float[] eMZs, float[] eIntensities,
                              float[] pMZs, float[] pIntensities,
                              boolean filterTop, int topFragments, boolean filterBase) {
        predMZs = pMZs;
        predIntensities = pIntensities;

        if (filterBase) {
            this.filterIntensitiesByPercentage(Constants.percentBasePeak);
        }
        if (filterTop) {
            this.filterTopFragments(topFragments);
        }

        int[] sortedIndices = IntStream.range(0, predMZs.length)
                .boxed().sorted((k, j) -> Float.compare(predMZs[k], predMZs[j]))
                .mapToInt(ele -> ele).toArray();
        pMZs = predMZs;
        pIntensities = predIntensities;
        predMZs = new float[predMZs.length];
        predIntensities = new float[predIntensities.length];
        for (int i = 0; i < sortedIndices.length; i++) {
            predMZs[i] = pMZs[sortedIndices[i]];
            predIntensities[i] = pIntensities[sortedIndices[i]];
        }

        matchedIntensities = this.getMatchedIntensities(eMZs, eIntensities);
    }

    private void filterTopFragments() {
        //stick with arraylist because finding minimum will be faster than linkedlist due to indexing
        //skip if shorter
        if (predMZs.length > Constants.topFragments) {
            tmpInts.clear();
            tmpMZs.clear();

            for (float i : predIntensities) {
                tmpInts.add(i);
            }

            for (float i : predMZs) {
                tmpMZs.add(i);
            }

            predIntensities = new float[Constants.topFragments];
            predMZs = new float[Constants.topFragments];

            for (int i = 0; i < Constants.topFragments; i++) {
                int index = tmpInts.indexOf(Collections.max(tmpInts));
                predIntensities[i] = tmpInts.get(index);
                predMZs[i] = tmpMZs.get(index);
                tmpInts.set(index, -1f);
            }
        }
    }

    private void filterTopFragments(int topFragments) {
        //stick with arraylist because finding minimum will be faster than linkedlist due to indexing
        //skip if shorter
        if (predMZs.length > topFragments) {
            tmpInts.clear();
            tmpMZs.clear();

            for (float i : predIntensities) {
                tmpInts.add(i);
            }

            for (float i : predMZs) {
                tmpMZs.add(i);
            }

            predIntensities = new float[topFragments];
            predMZs = new float[topFragments];

            for (int i = 0; i < topFragments; i++) {
                int index = tmpInts.indexOf(Collections.max(tmpInts));
                predIntensities[i] = tmpInts.get(index);
                predMZs[i] = tmpMZs.get(index);
                tmpInts.set(index, -1f);
            }
        }
    }

    public void filterIntensitiesByValue(float min) {
        tmpMZs.clear();
        tmpInts.clear();

        for (int i = 0; i < predIntensities.length; i++) {
            float intensity = predIntensities[i];

            if (intensity >= min) {
                tmpInts.add(intensity);
                tmpMZs.add(predMZs[i]);
            }
        }

        predMZs = new float[tmpMZs.size()];
        predIntensities = new float[tmpInts.size()];

        for (int i = 0; i < tmpInts.size(); i++) {
            predIntensities[i] = tmpInts.get(i);
            predMZs[i] = tmpMZs.get(i);
        }
    }

    public void filterIntensitiesByPercentage(float percentage) { //should be < 100
        if (percentage > 100) {
            System.out.println("percentBasePeak must be <= 100 but is set to " + Constants.percentBasePeak);
            System.exit(-1);
        } else {
            //get max intensity
            float maxIntensity = 0f;
            for (float f : predIntensities) {
                if (f > maxIntensity) {
                    maxIntensity = f;
                }
            }

            //make cutoff by percentage
            float cutoff = maxIntensity * percentage / 100f;
            filterIntensitiesByValue(cutoff);
        }
    }

    private float[] getMatchedIntensities(float[] expMZs, float[] expIntensities) {
        if (predIntensities.length == 1) {
            return predIntensities;
        }
        int startPos = 0;
        int matchedNum = 0;
        float[] matchedInts = new float[predMZs.length];
        if (! Constants.matchWithDaltons) {
            double ppm = Constants.ppmTolerance / 1000000;

        /* Get best peaks from experimental spectrum that match to predicted peaks.
           Same experimental peak may match to the multiple predicted peaks,
              if they're close enough and experimental peak is strong.
           Unmatched peaks assigned 0
         */
            for (double mz : predMZs) {
                //see if any experimental peaks in vicinity
                //double fragmentError = ppm * mz;
                double fragmentMin = mz * (1 - ppm);
                double fragmentMax = mz * (1 + ppm);

                float predInt = 0;
                int pastStart = 0;

                while (startPos + pastStart < expMZs.length) {
                    double startMass = expMZs[startPos + pastStart];

                    if (startMass < fragmentMin) { //yet to reach peak within fragment tolerance
                        startPos += 1;
                    } else if (startMass <= fragmentMax) { //peak within fragment tolerance
                        //only for use when removing peaks from lower ranks
                        if (Constants.removeRankPeaks) {
                            matchedIdx.add(startPos + pastStart);
                        }

                        float potentialInt = expIntensities[startPos + pastStart];

                        if (potentialInt > predInt) { //new maximum intensity
                            predInt = potentialInt;
                        }
                        pastStart += 1;
                    } else { //outside of fragment tolerance range again
                        break;
                    }
                }

                matchedInts[matchedNum] = predInt;
                matchedNum += 1;
            }
        } else {
            for (double mz : predMZs) { //TODO: because fragments besides unknown have correct m/z, is this unnecessary?
                //see if any experimental peaks in vicinity
                //double fragmentError = ppm * mz;
                double fragmentMin = mz - Constants.DaTolerance;
                double fragmentMax = mz + Constants.DaTolerance;

                float predInt = 0;
                int pastStart = 0;

                while (startPos + pastStart < expMZs.length) {
                    double startMass = expMZs[startPos + pastStart];

                    if (startMass < fragmentMin) { //yet to reach peak within fragment tolerance
                        startPos += 1;
                    } else if (startMass <= fragmentMax) { //peak within fragment tolerance
                        //only for use when removing peaks from lower ranks
                        if (Constants.removeRankPeaks) {
                            matchedIdx.add(startPos + pastStart);
                        }

                        float potentialInt = expIntensities[startPos + pastStart];

                        if (potentialInt > predInt) { //new maximum intensity
                            predInt = potentialInt;
                        }
                        pastStart += 1;
                    } else { //outside of fragment tolerance range again
                        break;
                    }
                }

                matchedInts[matchedNum] = predInt;
                matchedNum += 1;
            }
        }
        return matchedInts;
    }

    public double[] getWeights(double[] freqs) {
        //will need to use predMZs
        int maxIndex = freqs.length;

        double[] weights = new double[predMZs.length];
        for (int i = 0; i < predMZs.length; i++) {
            int binIndex = (int) Math.floor(predMZs[i] / Constants.binwidth);
            if (binIndex < maxIndex) {
                weights[i] = freqs[binIndex];
            } else { //detected too big a fragment
                weights[i] = 0; //arbitrary, just ignore
            }
        }

        return weights;
    }

    private static float[] unitNormalize(float[] vector) {
        //if size 1
        if (vector.length == 1) {
            return vector;
        }

        //if we wish to normalize to unit vector
        double magnitude = 0;
        for (double i : vector) {
            magnitude += i * i;
        }
        magnitude =  Math.sqrt(magnitude);

        float[] newVector = new float[vector.length];

        if (magnitude != 0) { //fixes Bray curtis
            float mag = (float) magnitude;
            for (int i = 0; i < newVector.length; i++) {
                newVector[i] = vector[i] / mag;
            }
        }

        return newVector;
    }

    public void unitNormalizeIntensities() { //only use after filtering
        unitNormPredIntensities = unitNormalize(predIntensities);
        unitNormMatchedIntensities = unitNormalize(matchedIntensities);
    }

    private static float[] oneNormalize(float[] vector) {
        //if size 1
        if (vector.length == 1) {
            return vector;
        }

        //if we wish to normalize to unit vector
        double total = 0;
        for (double i : vector) {
            total += i;
        }

        float[] newVector = new float[vector.length];

        if (total != 0) { //fixes Bray curtis
            float t = (float) total;
            for (int i = 0; i < newVector.length; i++) {
                newVector[i] = vector[i] / t;
            }
        }

        return newVector;
    }

    public void oneNormalizeIntensities() {
        sum1MatchedIntensities = oneNormalize(matchedIntensities);
        sum1PredIntensities = oneNormalize(predIntensities);
    }

    public double cosineSimilarity() {

        //numerator
        double num = 0;
        for (int i = 0; i < predMZs.length; i++) {
            num += predIntensities[i] * matchedIntensities[i];
        }

        //denominator
        double a = 0;
        double b = 0;
        for (int i = 0; i < predMZs.length; i++) {
            a += predIntensities[i] * predIntensities[i];
            b += matchedIntensities[i] * matchedIntensities[i];
        }
        double den = Math.sqrt(a * b);

        if (den == 0) { //fixes no matched peaks
            return 0;
        } else {
            return num / den;
        }
    }

    //https://stats.stackexchange.com/questions/384419/weighted-cosine-similarity
    public double weightedCosineSimilarity(double[] weights) {

        //numerator
        double num = 0;
        for (int i = 0; i < predMZs.length; i++) {
            num += predIntensities[i] * matchedIntensities[i] * weights[i];
        }

        //denominator
        double a = 0;
        double b = 0;
        for (int i = 0; i < predMZs.length; i++) {
            a += predIntensities[i] * predIntensities[i] * weights[i];
            b += matchedIntensities[i] * matchedIntensities[i] * weights[i];
        }
        double den = Math.sqrt(a * b);

        if (den == 0) {
            return 0;
        } else {
            return num / den;
        }
    }

    public double spectralContrastAngle() {
        double cosSim = this.cosineSimilarity();
        return 1 - (2 * Math.acos(cosSim) / Math.PI);
    }

    public double weightedSpectralContrastAngle(double[] weights) {
        double cosSim = this.weightedCosineSimilarity(weights);
        return 1 - (2 * Math.acos(cosSim) / Math.PI);
    }

    public double euclideanDistance() {
        if (unitNormPredIntensities == null) {
            this.unitNormalizeIntensities();
        }

        //max distance between two points in the positive quadrant with unit vectors is sqrt(2)
        float floatSum = 0.0f;
        for (float f : unitNormMatchedIntensities){
            floatSum += f;
        }
        if (floatSum == 0.0f) {
            return 1 - Math.sqrt(2);
        } else {
            double numSum = 0;
            for (int i = 0; i < predMZs.length; i++) {
                double diff = unitNormPredIntensities[i] - unitNormMatchedIntensities[i];
                double square = diff * diff;
                numSum += square;
            }
            return 1 - Math.sqrt(numSum);
        }
    }

    public double weightedEuclideanDistance(double[] weights) {
        if (unitNormPredIntensities == null) {
            this.unitNormalizeIntensities();
        }

        float floatSum = 0.0f;
        for (float f : unitNormMatchedIntensities){
            floatSum += f;
        }
        if (floatSum == 0.0f) {
            return 1 - Math.sqrt(2);
        } else {
            //now we need to normalize again by weights
            float[] newNormPred = new float[unitNormPredIntensities.length];
            float[] newNormMatched = new float[unitNormMatchedIntensities.length];
            for (int i = 0; i < unitNormPredIntensities.length; i++) {
                newNormPred[i] = (float) weights[i] * unitNormPredIntensities[i];
                newNormMatched[i] = (float) weights[i] * unitNormMatchedIntensities[i];
            }

            newNormPred = unitNormalize(newNormPred);
            newNormMatched = unitNormalize(newNormMatched);

            //now just do euclidean distance
            double numSum = 0;
            for (int i = 0; i < predMZs.length; i++) {
                double diff = newNormPred[i] - newNormMatched[i];
                double square = diff * diff;
                numSum += square;
            }
            return 1 - Math.sqrt(numSum);
        }
    }

    public double brayCurtis() {
        if (predMZs.length < 2) {
            return 0;
        }
        if (unitNormPredIntensities == null) {
            this.unitNormalizeIntensities();
        }

        //check if no matched peaks
        float floatSum = 0.0f;
        for (float f : unitNormMatchedIntensities){
            floatSum += f;
        }
        if (floatSum == 0.0f) {
            return 0;
        } else {
            double num = 0;
            double den = 0;
            for (int i = 0; i < predMZs.length; i++) {
                double exp = unitNormMatchedIntensities[i];
                double pred = unitNormPredIntensities[i];

                num += Math.abs(exp - pred);
                den += (exp + pred);
            }
            return 1 - (num / den);
        }
    }

    public double weightedBrayCurtis(double[] weights) {
        if (unitNormPredIntensities == null) {
            this.unitNormalizeIntensities();
        }

        //check if no matched peaks
        float floatSum = 0.0f;
        for (float f : unitNormMatchedIntensities){
            floatSum += f;
        }
        if (floatSum == 0.0f) {
            return 0;
        } else {
            double num = 0;
            double den = 0;
            for (int i = 0; i < predMZs.length; i++) {
                double exp = unitNormMatchedIntensities[i];
                double pred = unitNormPredIntensities[i];

                num += weights[i] * Math.abs(exp - pred);
                den += weights[i] * (exp + pred);
            }
            return 1 - (num / den);
        }
    }

    public double pearsonCorr() {
        if (Arrays.stream(floatToDouble(matchedIntensities)).sum() == 0 || matchedIntensities.length == 1) {
            return -1; //minimum pearson correlation
        } else {
            //uses Apache
            return pc.correlation(floatToDouble(matchedIntensities), floatToDouble(predIntensities));
        }
    }

    public double weightedPearsonCorr(double[] weights) {
        if (Arrays.stream(floatToDouble(matchedIntensities)).sum() == 0) {
            return -1; //minimum pearson correlation
        } else {

            double[] newPred = new double[predIntensities.length];
            double[] newMatched = new double[matchedIntensities.length];
            for (int i = 0; i < predIntensities.length; i++) {
                newPred[i] = weights[i] * predIntensities[i];
                newMatched[i] = weights[i] * matchedIntensities[i];
            }
            return pc.correlation(newMatched, newPred);
        }
    }

    public double dotProduct() {
        if (unitNormPredIntensities == null) {
            this.unitNormalizeIntensities();
        }

        boolean nonzero = false;
        for (float f : matchedIntensities){
            if (f > 0) {
                nonzero = true;
                break;
            }
        }
        if (nonzero) {
            double num = 0;
            for (int i = 0; i < predMZs.length; i++) {
                num += unitNormPredIntensities[i] * unitNormMatchedIntensities[i];
            }

            return num;
        } else {
            return 0;
        }
    }

    public double weightedDotProduct(double[] weights) {
        float floatSum = 0.0f;
        for (float f : matchedIntensities){
            floatSum += f;
        }
        if (floatSum == 0.0f) {
            return 0;
        } else {
            double[] newPred = new double[predIntensities.length];
            double[] newMatched = new double[matchedIntensities.length];
            for (int i = 0; i < predIntensities.length; i++) {
                newPred[i] = weights[i] * predIntensities[i];
                newMatched[i] = weights[i] * matchedIntensities[i];
            }

            double predMax = 1 / Arrays.stream(newPred).max().getAsDouble();
            double matchedMax = 1 / Arrays.stream(newMatched).max().getAsDouble();
            double multiplier = predMax * matchedMax;

            double num = 0;
            for (int i = 0; i < predMZs.length; i++) {
                num += newPred[i] * newMatched[i] * multiplier;
            }

            return num;
        }
    }

    private double spectralEntropy(float[] vector) {
        double entropy = 0;
        for (float f : vector) {
            if (f != 0) { //log(0) problematic
                entropy += (f * Math.log(f));
            }
        }
        return -1 * entropy;
    }

    public double unweightedSpectralEntropy() { //from https://www.nature.com/articles/s41592-021-01331-z
        if (predMZs.length < 2) {
            return 0;
        }
        if (sum1PredIntensities == null) {
            oneNormalizeIntensities();
        }

        float[] SabVector = new float[sum1PredIntensities.length];
        for (int i = 0; i < SabVector.length; i++) {
            SabVector[i] = (sum1PredIntensities[i] + sum1MatchedIntensities[i]) / 2;
        }

        return 1 - ( (2 * spectralEntropy(SabVector) - spectralEntropy(sum1MatchedIntensities) - spectralEntropy(sum1PredIntensities)) / Math.log(4));
    }

    public static void main(String[] args) throws FileParsingException, IOException {
    }
}
