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

package External;

import static utils.Print.printInfo;

import Features.*;
import com.google.common.collect.ImmutableMap;
import umich.ms.fileio.exceptions.FileParsingException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public class KoinaMethods {
    //these fields are shared regardless of which model is called
    public PinMzmlMatcher pmMatcher;
    public HashSet<String> peptideSet = new HashSet<>();
    public HashMap<String, LinkedList<Integer>> scanNums = new HashMap<>();
    public HashMap<String, LinkedList<String>> peptides = new HashMap<>();

    //decoys
    public HashSet<String> peptideSetDecoys = new HashSet<>();
    public HashMap<String, LinkedList<Integer>> scanNumsDecoys = new HashMap<>();
    public HashMap<String, LinkedList<String>> peptidesDecoys = new HashMap<>();

    private static final Map<String, String> generalModels = ImmutableMap.of(
            "Prosit_2020_intensity_HCD", "Prosit",
            "Prosit_2020_intensity_CID", "Prosit");
    private static final Map<String, String> modelConversion = ImmutableMap.of(
            "HCD.Prosit", "Prosit_2020_intensity_HCD",
            "CID.Prosit", "Prosit_2020_intensity_CID");

    public KoinaMethods(PinMzmlMatcher pmMatcher) {
        this.pmMatcher = pmMatcher;
    }

    public void getTopPeptides() throws IOException {
        //need to collect top 1000 peptides for calibration
        //approximate by doing a subset per pin
        int numTopPSMs = (int) Math.ceil((float) Constants.numPSMsToCalibrate /
                (float) pmMatcher.pinFiles.length);

        for (int j = 0; j < pmMatcher.pinFiles.length; j++) {
            File pinFile = pmMatcher.pinFiles[j];
            PinReader pinReader = new PinReader(pinFile.getAbsolutePath());
            LinkedList[] topPSMs = pinReader.getTopPSMs(numTopPSMs);
            peptideSet.addAll(topPSMs[0]);
            scanNums.put(pmMatcher.mzmlFiles[j].getName(), topPSMs[1]);
            peptides.put(pmMatcher.mzmlFiles[j].getName(), topPSMs[0]);
        }
    }

    public void getDecoyPeptides() throws IOException {
        //need to collect top 1000 peptides for calibration
        //approximate by doing a subset per pin
        int numTopPSMs = (int) Math.ceil((float) Constants.numPSMsToCalibrate /
                (float) pmMatcher.pinFiles.length);

        for (int j = 0; j < pmMatcher.pinFiles.length; j++) {
            File pinFile = pmMatcher.pinFiles[j];
            PinReader pinReader = new PinReader(pinFile.getAbsolutePath());
            LinkedList[] decoyPSMs = pinReader.getDecoyPSMs(numTopPSMs);
            peptideSetDecoys.addAll(decoyPSMs[0]);
            scanNumsDecoys.put(pmMatcher.mzmlFiles[j].getName(), decoyPSMs[1]);
            peptidesDecoys.put(pmMatcher.mzmlFiles[j].getName(), decoyPSMs[0]);
        }
    }

    public HashSet<String> writeFullPeptideFile(String filePath, String currentModel, HashSet<String> peptideSet) throws IOException {
        FileWriter myWriter = new FileWriter(filePath);
        HashSet<String> allHits = new HashSet<>();
        for (String peptide : peptideSet) {
            String[] peptFormats = peptide.split(",");

            String stripped = peptFormats[2];

            if ((currentModel.contains("Prosit") || currentModel.contains("ms2pip") || currentModel.contains("Deeplc"))
                    && stripped.contains("U")) { // no peptides with U
                continue;
            }
            if (currentModel.contains("ms2pip") && stripped.length() > 30) { //peptide has length limit
                continue;
            }
            if (currentModel.contains("Prosit") && currentModel.contains("TMT") && stripped.length() > 30) {
                continue;
            }

            String pep = peptFormats[1].replace("UniMod", "UNIMOD"); //need diann here
            if (pep.contains("[TMT]")) {
                pep = pep.replace("[TMT]", "[UNIMOD:737]");
            }

            if (pep.startsWith("[")) { //this is the case for all n term mods //TODO deal with c term mods
                int splitpoint = pep.indexOf("]");
                if (currentModel.contains("Prosit")) {
                    if (currentModel.contains("TMT") && pep.startsWith("[UNIMOD:737]")) {
                        pep = pep.substring(0, splitpoint + 1) + "-" + pep.substring(splitpoint + 1);
                    } else {
                        pep = pep.substring(splitpoint + 1);
                    }
                } else {
                    pep = pep.substring(0, splitpoint + 1) + "-" + pep.substring(splitpoint + 1);
                }
            }
            if (currentModel.contains("Prosit") && currentModel.contains("TMT")) {
                pep = pep.replace("S[UNIMOD:737]", "S");
                if (!pep.startsWith("[")) {
                    pep = "[UNIMOD:737]-" + pep;
                }
            }
            String[] baseCharge = peptFormats[0].split("\\|");
            allHits.add(pep + "," + baseCharge[1]);

            //need to generate full.tsv
            myWriter.write(baseCharge[0] + "\t" + baseCharge[1] + "\n");
        }
        myWriter.close();

        return allHits;
    }

    public PredictionEntryHashMap getKoinaPredictions(
            HashSet<String> allHits, String model, int NCE, String folder, String fulltsv) {
        ScheduledThreadPoolExecutor executorService = new ScheduledThreadPoolExecutor(1);

        HashSet<String> hits = new HashSet<>();
        for (String s : allHits) {
            hits.add(s + "," + NCE + "," + Constants.instrument + "," + Constants.FragmentationType);
        }

        JSONWriter jw = new JSONWriter(model, hits);

        String jsonFolder = "";
        try {
            jsonFolder = jw.write(true, folder, executorService);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        //send predictions to Koina
        KoinaLibReader klr = new KoinaLibReader();
        KoinaModelCaller kmc = new KoinaModelCaller();
        kmc.callModel(model, klr, jsonFolder, executorService, false, false);
        executorService.shutdown();
        try {
            kmc.assignMissingPeptidePredictions(klr, fulltsv);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return klr.allPreds;
    }

    public PeptideObj[] getPeptideObjects(PredictionEntryHashMap allPreds,
                                          HashMap<String, LinkedList<Integer>> scanNums,
                                          HashMap<String, LinkedList<String>> peptides)
            throws FileParsingException, ExecutionException, InterruptedException {
        allPreds.filterTopFragments(new ScheduledThreadPoolExecutor(Constants.numThreads));

        int arrayLength = 0;
        for (LinkedList<Integer> scanNum : scanNums.values()) {
            arrayLength += scanNum.size();
        }
        PeptideObj[] peptideObjs = new PeptideObj[arrayLength];

        int index = 0;
        for (int j = 0; j < pmMatcher.mzmlReaders.length; j++) {
            MzmlReader mzmlReader = pmMatcher.mzmlReaders[j];
            LinkedList<Integer> thisScanNums = scanNums.get(pmMatcher.mzmlFiles[j].getName());
            LinkedList<String> thisPeptides = peptides.get(pmMatcher.mzmlFiles[j].getName());

            for (int k = 0; k < thisScanNums.size(); k++) {
                int scanNum = thisScanNums.get(k);
                MzmlScanNumber msn = mzmlReader.getScanNumObject(scanNum);

                String peptide = thisPeptides.get(k).split(",")[0];
                String[] baseCharge = peptide.split("\\|");

                PeptideObj pobj = msn.setPeptideObject(
                        new PeptideFormatter(baseCharge[0], baseCharge[1], "base"),
                        1, 1, "0", allPreds, false);
                peptideObjs[index] = pobj;
                index++;
            }
        }
        return peptideObjs;
    }

    //use nested hashmap to make sure correct version of model is assigned for given fragmentation type
    //uses Constants information (FragmentationType and current spectraModel) to correct spectraModel
    //solution: use composite key fragmentationtype + "." + modelType, where modelType is a HashMap of
    //exact model name and more general type (i.e. Prosit models, APD models, etc)
    //if hashset contains exact model name, return that general type
    //returns true if model changed
    public static boolean switchModel() {
        //correct to CID model
        //TODO do in opposite direction. Or more general method to get right fragmentation model
        if (Constants.autoSwitchFragmentation && generalModels.containsKey(Constants.spectraModel)) {
            String genModel = generalModels.get(Constants.spectraModel);
            String newModel = modelConversion.get(Constants.FragmentationType + "." + genModel);
            if (!newModel.equals(Constants.spectraModel)) {
                printInfo("Switching from " + Constants.spectraModel + " to " + newModel);
                Constants.spectraRTPredModel =
                        Constants.spectraRTPredModel.replace(Constants.spectraModel, newModel);
                Constants.spectraModel = newModel;
                return true;
            }
        }
        return false;
    }
}
