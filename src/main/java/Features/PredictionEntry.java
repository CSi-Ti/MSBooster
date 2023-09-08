/*
 * This file is part of MSBooster.
 *
 * MSBooster is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * MSBooster is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with MSBooster. If not, see <https://www.gnu.org/licenses/>.
 */

package Features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.stream.IntStream;

public class PredictionEntry {
    float[] mzs;
    float[] intensities;
    int[] fragNums;
    int[] flags;
    int[] charges;
    String[] fragmentIonTypes;
    float RT;
    float IM;
    float detectability;
    int counter;
    HashMap<String, Float[]> scores = new HashMap<>();
    ArrayList<Integer> times = new ArrayList<>();
    double precursorMz = 0d;

    public PredictionEntry() {}

    public PredictionEntry(float[] mzs, float[] intensities, boolean needToSort) {
        if (needToSort) {
            this.mzs = new float[mzs.length];
            this.intensities = new float[intensities.length];

            int[] sortedIndices = IntStream.range(0, mzs.length)
                    .boxed().sorted((k, j) -> Float.compare(mzs[k], mzs[j]))
                    .mapToInt(ele -> ele).toArray();

            for (int i = 0; i < sortedIndices.length; i++) {
                this.mzs[i] = mzs[sortedIndices[i]];
                this.intensities[i] = intensities[sortedIndices[i]];
            }
        } else {
            setMzs(mzs);
            setIntensities(intensities);
        }
    }

    public void setMzs(float[] mzs) {this.mzs = mzs;}

    public void setIntensities(float[] intensities) {this.intensities = intensities;}

    public void setFragNums(int[] fragNums) {this.fragNums = fragNums;}

    public void setFlags(int[] flags) { //save time with this
        this.flags = flags;
        this.fragmentIonTypes = new String[flags.length];
        for (int i = 0; i < flags.length; i++) {
            this.fragmentIonTypes[i] = Constants.flagTOion.get(flags[i]);
        }
    }

    public void setFlags() { //save time with this
        this.flags = new int[fragmentIonTypes.length];
        for (int i = 0; i < fragmentIonTypes.length; i++) {
            this.flags[i] = Constants.ionTOflag.get(fragmentIonTypes[i]);
        }
    }

    public void setCharges(int[] charges) {this.charges = charges;}

    public void setRT(float RT) {this.RT = RT;}

    public void setIM(float IM) {this.IM = IM;}

    public void setDetectability(float detectability) {this.detectability = detectability;}

    public void setCounter(int counts) {this.counter = counts;}

    public void setFragmentIonTypes(String[] ions) { this.fragmentIonTypes = ions; }

    public void setFragmentIonTypes() {
        this.fragmentIonTypes = new String[flags.length];
        for (int i = 0; i < flags.length; i++) {
            this.fragmentIonTypes[i] = Constants.flagTOion.get(flags[i]);
        }
    }
}
