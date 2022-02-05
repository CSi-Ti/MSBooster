package Features;

import com.univocity.parsers.tsv.TsvWriter;
import com.univocity.parsers.tsv.TsvWriterSettings;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;
import umich.ms.fileio.exceptions.FileParsingException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static Features.Constants.camelToUnderscore;

public class percolatorFormatter {

    //set mgf or detectFile as null if not applicable
    //baseNames is the part before mzml or pin extensions
    public static void editPin(String pinDirectory, String mzmlDirectory, String mgf, String detectFile,
                               String[] features, String outfile)
            throws IOException, InterruptedException, ExecutionException, FileParsingException {

        PinMzmlMatcher pmMatcher = new PinMzmlMatcher(mzmlDirectory, pinDirectory);

        editPin(pmMatcher, mgf, detectFile, features, outfile);
    }

    public static void editPin(PinMzmlMatcher pmMatcher, String mgf, String detectFile,
                               String[] features, String outfile) throws IOException, InterruptedException, ExecutionException, FileParsingException {
        ExecutorService executorService = Executors.newFixedThreadPool(Constants.numThreads);

        //defining num threads, in case using this outside of jar file
        Runtime run  = Runtime.getRuntime();
        if (Constants.numThreads <= 0) {
            Constants.numThreads = run.availableProcessors();
        }

        ArrayList<String> featuresList = new ArrayList<>(Arrays.asList(features));
        //remove features from array for multiple protein formatting
        if (featuresList.contains("detectability")) {
            int idx = ArrayUtils.indexOf(features, "detectability");
            features = ArrayUtils.remove(features, idx);
        }

        //booleans for future determination of what to do
        boolean needsMGF = false;

        File[] pinFiles = pmMatcher.pinFiles;
        File[] mzmlFiles = pmMatcher.mzmlFiles;

        //load predicted spectra
        SpectralPredictionMapper predictedSpectra = null;
        SpectralPredictionMapper predictedSpectra2 = null; //first is prosit/diann, second predfull

        //Special preparations dependent on features we require
        //only time this isn't needed is detect
        //could consider an mgf constant
        if (mgf != null) {
            needsMGF = true;
            String[] mgfSplit = mgf.split(",");

            if (mgfSplit.length == 1) {
                System.out.println("Loading predicted spectra");
                predictedSpectra = SpectralPredictionMapper.createSpectralPredictionMapper(mgf, executorService);
            } else if (mgfSplit.length == 2){ //if fragment from predfull is not y/b, add
                System.out.println("Loading predicted spectra 1");
                predictedSpectra = SpectralPredictionMapper.createSpectralPredictionMapper(mgfSplit[0], executorService);
                System.out.println("Loading predicted spectra 2");
                predictedSpectra2 = SpectralPredictionMapper.createSpectralPredictionMapper(mgfSplit[1], executorService);
                System.out.println("Merging spectral libraries");

                for (Map.Entry<String, PredictionEntry> entry : predictedSpectra2.getPreds().entrySet()) {
                    if (entry.getValue().fragmentIonTypes == null) { //something with base modifications that is never actually queried
                        predictedSpectra.getPreds().remove(entry.getKey());
                        continue;
                    }
                    PredictionEntry pe = predictedSpectra.getPreds().get(entry.getKey());
                    if (pe == null) { //missing in prosit/diann
                        predictedSpectra.getPreds().put(entry.getKey(), entry.getValue());
                    } else { //add non-y/b ions
                        ArrayList<Float> mzs = new ArrayList<>();
                        ArrayList<Float> intensities = new ArrayList<>();

                        //add original peaks
                        for (float mz : pe.mzs) {
                            mzs.add(mz);
                        }
                        for (float intensity : pe.intensities) {
                            intensities.add(intensity);
                        }

                        //add new peaks
                        PredictionEntry pe2 = entry.getValue();
                        for (int i = 0; i < pe2.fragmentIonTypes.length; i++) {
                            if (!pe2.fragmentIonTypes[i].equals("y") &&
                                    !pe2.fragmentIonTypes[i].equals("b")) {
                                mzs.add(pe2.mzs[i]);
                                intensities.add(pe2.intensities[i] / 1000); //TODO: make into model max
                            }
                        }

                        //convert back to array
                        float[] mzArray = new float[mzs.size()];
                        float[] intArray = new float[intensities.size()];
                        for (int i = 0; i < mzArray.length; i++) {
                            mzArray[i] = mzs.get(i);
                            intArray[i] = intensities.get(i);
                        }

                        pe.setMzs(mzArray);
                        pe.setIntensities(intArray);
                        predictedSpectra.getPreds().put(entry.getKey(), pe);
                    }
                }
                predictedSpectra2 = null; //free up memory
            }
        }
        //create detectMap to store detectabilities for base sequence peptides
        //store peptide detectabilities in PredictionEntry
        detectMap dm = null;
        ArrayList<String> dFeatures = new ArrayList<String>(Constants.detectFeatures);
        dFeatures.retainAll(featuresList);
        //long startTime = System.nanoTime();
        if (dFeatures.size() > 0) {
            dm = new detectMap(detectFile);
            HashMap<String, PredictionEntry> allPreds = predictedSpectra.getPreds();
            for (Map.Entry<String, PredictionEntry> e : allPreds.entrySet()) {
                e.getValue().setDetectability(dm.getDetectability(
                        new PeptideFormatter(e.getKey().split("\\|")[0], e.getKey().split("\\|")[1], "pin").stripped));
            }
        }

        FastaReader fasta = null;

        if (featuresList.contains("detectFractionGreater") || featuresList.contains("detectSubtractMissing")
                || featuresList.contains("detectProtSpearmanDiff")) {

            HashMap<String, Integer> pepCounter = new HashMap<>();

            //get all peptides present in pin
            for (File pinFile : pmMatcher.pinFiles) {
                pinReader pin = new pinReader(pinFile.getCanonicalPath());

                //add to counter
                while (pin.next()) {
                    String pep = pin.getPep().base;
                    if (pepCounter.containsKey(pep)) {
                        pepCounter.put(pep, pepCounter.get(pep) + 1);
                    } else {
                        pepCounter.put(pep, 1);
                    }
                }
                pin.close();
            }

            //load fasta
            if (Constants.getFastaReader() == null) {
                System.out.println("Creating fasta object");
                fasta = new FastaReader(Constants.fasta);
            } else {
                System.out.println("Loading fasta");
                fasta = Constants.getFastaReader();
            }

            System.out.println("Loading detectabilities for unique peptides from each protein");
            for (Map.Entry<String, ProteinEntry> e : fasta.protToPep.entrySet()) {
                ArrayList<String> pepList = e.getValue().peptides;
                float[] protDetects = new float[pepList.size()]; //for storing initial detect order

                //store detect unsorted
                for (int pep = 0; pep < pepList.size(); pep++) {
                    protDetects[pep] = dm.getDetectability(pepList.get(pep));
                }

                //dual pivot quicksort
                //sorted indices
                int[] sortedIndices = IntStream.range(0, protDetects.length)
                        .boxed().sorted((k, j) -> Float.compare(protDetects[k], protDetects[j]))
                        .mapToInt(ele -> ele).toArray();

                float[] sortedDetect = new float[protDetects.length];
                for (int j = 0; j < protDetects.length; j++) {
                    sortedDetect[j] = protDetects[sortedIndices[j]];
                }
                e.getValue().detects = sortedDetect;

                //check which peptides present, and get spectral counts
                float[] protPresence = new float[protDetects.length];
                float[] pepCounts = new float[protDetects.length];
                float numPresent = 1f;
                for (int j = protDetects.length - 1; j > -1; j--) {
                    String currentPep = pepList.get(sortedIndices[j]);

                    if (pepCounter.containsKey(currentPep)) {
                        protPresence[j] = numPresent;
                        numPresent += 1f;
                        pepCounts[j] = pepCounter.get(currentPep);
                    }
                }
                fasta.protToPep.get(e.getKey()).presence = protPresence;
                fasta.protToPep.get(e.getKey()).spectralCounts = pepCounts;
            }

            HashMap<String, PredictionEntry> allPreds = predictedSpectra.getPreds();
            for (Map.Entry<String, PredictionEntry> e : allPreds.entrySet()) {
                try {
                    e.getValue().setCounter(pepCounter.get(e.getKey().split("\\|")[0]));
                } catch (Exception ee) { //peptide was in a pin file from another run
                }
            }
            dm.clear();
        }
        //long endTime = System.nanoTime();
        //long duration = (endTime - startTime);
        //System.out.println("Detectability map and formatting loading took " + duration / 1000000 +" milliseconds");

        try {
            //////////////////////////////iterate through pin and mzml files//////////////////////////////////////////
            for (int i = 0; i < pinFiles.length; i++) {
                //startTime = System.nanoTime();
                String newOutfile = pinFiles[i].getAbsolutePath().replaceAll("\\.pin$", "_" + outfile + ".pin");

                TsvWriterSettings tws = new TsvWriterSettings();
                tws.setMaxCharsPerColumn(-1);
                tws.setMaxColumns(50000); //who knows if it needs to be longer?
                TsvWriter writer = new TsvWriter(new File(newOutfile), tws);
                //load mzml file
                System.out.println("Processing " + mzmlFiles[i].getName());

                mzMLReader mzml;
                if (mzmlFiles[i].getName().substring( mzmlFiles[i].getName().length() - 3).toLowerCase().equals("mgf")) {
                    //mzml = new mzMLReader(new mgfFileReader(mzmlFiles[i].getCanonicalPath()));
                    mzml = new mzMLReader(new mgfFileReader(mzmlFiles[i].getCanonicalPath(), true, executorService));
                    //endTime = System.nanoTime();
                    //duration = (endTime - startTime);
                    //System.out.println("mgf loading took " + duration / 1000000 +" milliseconds");
                } else {
                    mzml = new mzMLReader(mzmlFiles[i].getCanonicalPath());
                    //endTime = System.nanoTime();
                    //duration = (endTime - startTime);
                    //System.out.println("mzML loading took " + duration / 1000000 +" milliseconds");
                }

                //load pin file, which already includes all ranks
                pinReader pin = new pinReader(pinFiles[i].getCanonicalPath());

                //add header to written tsv
                ArrayList<String> newHeader = new ArrayList<>();
                newHeader.addAll(Arrays.asList(pin.header));
                    //replace column names
                ArrayList<String> newNames = new ArrayList<>(featuresList.size());
                for (String s : featuresList) {
                    String newName = s;
                    if (camelToUnderscore.containsKey(s)) {
                        newName = camelToUnderscore.get(s);
                    }
                    newNames.add(newName);
                }
                newHeader.addAll(pin.pepIdx, newNames); //add features before Peptide
                newHeader.remove("detectability");
                writer.writeHeaders(newHeader);

                //Special preparations dependent on features we require
                if (needsMGF) {
                    mzml.setPinEntries(pin, predictedSpectra);
                }
                if (featuresList.contains("deltaRTLOESS") || featuresList.contains("deltaRTLOESSnormalized")) {
                    mzml.setLOESS(Constants.RTregressionSize, Constants.bandwidth, Constants.robustIters, "RT");
                    mzml.predictRTLOESS(executorService); //potentially only invoke once if normalized included

                    //generate calibration figure, need mzml and loess
                    if (! Constants.noRTscores) {
                        RTCalibrationFigure rtfc = new RTCalibrationFigure(mzml, pinFiles[i].getCanonicalPath(), 0.2f);
                    }
                }
                if (featuresList.contains("deltaRTlinear")) {
                    //System.out.println("Calculating delta RT linear");
                    if (mzml.expAndPredRTs != null) {
                        mzml.setBetas();
                    } else { mzml.setBetas(predictedSpectra, Constants.RTregressionSize);
                    }
                    mzml.normalizeRTs(executorService);
                }
                if (featuresList.contains("deltaRTbins") || featuresList.contains("RTzscore") ||
                        featuresList.contains("RTprobability") || featuresList.contains("RTprobabilityUnifPrior") ||
                        featuresList.contains("deltaRTLOESSnormalized")) {
                    //System.out.println("Generating RT bins");
                    mzml.setRTbins();
                    mzml.calculateBinStats("RT");
                }
                if (featuresList.contains("deltaRTbins") || featuresList.contains("RTzscore")) {
                    mzml.calculateDeltaRTbinAndRTzscore(executorService);
                }
                if (featuresList.contains("deltaRTLOESSnormalized")) {
                    mzml.calculateDeltaRTLOESSnormalized(executorService);
                }
                int unifPriorSize = 0; //only used if using uniform prior
                float unifProb = 0;
                if (featuresList.contains("RTprobabilityUnifPrior")) {
                    mzml.setRTBinSizes(executorService);

                    //decide how many uniform points to add
                    int[] binSizes = new int[mzml.RTbins.length];
                    for (int bin = 0; bin < mzml.RTbins.length; bin++) {
                        binSizes[bin] = mzml.RTbins[bin].size();
                    }
                    Arrays.sort(binSizes);
                    int cutoff = (int) Math.floor(((double) mzml.RTbins.length / 100.0) * Constants.uniformPriorPercentile);
                    unifPriorSize = binSizes[cutoff];

                    //also need uniform probability with right bound of max predicted RT
                    unifProb = 1.0f / predictedSpectra.getMaxPredRT();
                }
                if (featuresList.contains("RTprobability") || featuresList.contains("RTprobabilityUnifPrior")) {
                    //System.out.println("Generating RT empirical densities");
                    mzml.setKernelDensities(executorService, "RT");
                }
                if (featuresList.contains("deltaIMLOESS") || featuresList.contains("deltaIMLOESSnormalized")) {
                    //System.out.println("Generating IM LOESS regression");
                    mzml.setLOESS(Constants.IMregressionSize, Constants.bandwidth, Constants.robustIters, "IM");
                    mzml.predictIMLOESS(executorService);
                }
                if (featuresList.contains("deltaIMLOESSnormalized") || featuresList.contains("IMprobabilityUnifPrior")) {
                    //System.out.println("Generating IM bins");
                    mzml.setIMbins();
                    mzml.calculateBinStats("IM");
                }
                if (featuresList.contains("deltaIMLOESSnormalized")) {
                    mzml.calculateDeltaIMLOESSnormalized(executorService);
                }
                float[] unifProbIM = new float[IMFunctions.numCharges];
                int[] unifPriorSizeIM = new int[IMFunctions.numCharges];;
                if (featuresList.contains("IMprobabilityUnifPrior")) {
                    mzml.setIMBinSizes(executorService);

                    //decide how many uniform points to add
                    //System.out.println("Generating IM empirical densities");
                    for (int charge = 0; charge < IMFunctions.numCharges; charge++) {
                        int[] binSizes = new int[mzml.IMbins[charge].length];
                        for (int bin = 0; bin < mzml.IMbins[charge].length; bin++) {
                            binSizes[bin] = mzml.IMbins[charge][bin].size();
                        }
                        Arrays.sort(binSizes);
                        int cutoff = (int) Math.floor(((double) mzml.IMbins[charge].length / 100.0) * Constants.uniformPriorPercentile);
                        unifPriorSizeIM[charge] = binSizes[cutoff];

                        //also need uniform probability with right bound of max predicted RT
                        unifProbIM[charge] = 1.0f / (2 * Constants.IMbinMultiplier);

                        mzml.setKernelDensities(executorService, "IM");
                    }
                }

                //System.out.println("Getting predictions for each row");
                //int totalPSMs = 0;

                while (pin.next()) {
                    //totalPSMs += 1;
                    //peptide name
                    String pep = pin.getPep().baseCharge;

                    //trying filtering out low detectability
//                    if (featuresList.contains("detectability")) {
//                        if (dm.getDetectability(pep) < Constants.detectThreshold) {
//                            continue;
//                        }
//                    }

                    //get entry
                    peptideObj pepObj = null;
                    if (needsMGF) {
                        pepObj = mzml.scanNumberObjects.get(pin.getScanNum()).getPeptideObject(pep);
                    }

                    //write everything we already have, not including extra protein columns
                    String[] row = pin.getRow();
                    for (int j = 0; j < pin.header.length; j++) {
                        writer.addValue(pin.header[j], row[j]);
                    }
                    //add extra protein columns
                    if (pin.getRow().length > pin.header.length) {
                        for (int j = pin.header.length; j < pin.getRow().length; j++) {
                            writer.addValue(j + features.length, row[j]);
                        }
                    }

                    //switch case
                    for (String feature : features) {
                        switch (feature) {
                            case "detectFractionGreater":
                                float d = predictedSpectra.getPreds().get(pep).detectability;
                                //for each protein, get the position of pep's detect and see how many peptides with greater detect are present
                                //take max (proxy for protein that actually generated peptide)
                                String[] r = pin.getRow();
                                String[] prots = Arrays.copyOfRange(r, pin.pepIdx + 1, r.length);
                                float maxFraction = 0f;
                                for (String prot : prots) { //if more than one, this peptide is shared among proteins
                                    String protAbr;
                                    //skip protein if it is decoy and looking at target peptide
                                    if (prot.startsWith(Constants.decoyPrefix.substring(1))) {
                                        if (r[pin.labelIdx].equals("1")) {
                                            continue;
                                        } else { //decoy peptide compared to target protein
                                            protAbr = prot.substring(Constants.decoyPrefix.length() - 1);
                                        }
                                    } else {
                                        protAbr = prot;
                                    }

                                    float[] arr;
                                    try {
                                        arr = fasta.protToPep.get(protAbr).detects;
                                    } catch (Exception e) { //no peptides qualify from this protein
                                        continue;
                                    }

                                    int idx = Arrays.binarySearch(arr, d);
                                    if (idx < 0) { //not found
                                        idx = (-1 * idx) - 1;
                                    } else {
                                        idx += 1; //don't want to include itself in calculation
                                    }
                                    float[] presenceArr = Arrays.copyOfRange(fasta.protToPep.get(protAbr).presence, idx, arr.length);
                                    float total = 0f;
                                    for (float j : presenceArr) {
                                        if (j != 0f) {
                                            total = j;
                                            break;
                                        }
                                    }
                                    float fraction = (total + Constants.detectFractionGreaterNumerator) /
                                            (presenceArr.length + Constants.detectFractionGreaterDenominator); //customizable prior
                                    if (fraction > maxFraction) {
                                        maxFraction = fraction;
                                    }
                                }
                                writer.addValue("detect_fraction_greater", maxFraction);
                                break;
                            case "detectSubtractMissing":
                                d = predictedSpectra.getPreds().get(pep).detectability;
                                //for each protein, get the position of pep's detect and see how many peptides with greater detect are present
                                //take max (proxy for protein that actually generated peptide)
                                r = pin.getRow();
                                prots = Arrays.copyOfRange(r, pin.pepIdx + 1, r.length);
                                float minDiff = 1f;
                                for (String prot : prots) { //if more than one, this peptide is shared among proteins
                                    String protAbr;
                                    //skip protein if it is decoy and looking at target peptide
                                    if (prot.startsWith(Constants.decoyPrefix.substring(1))) {
                                        if (r[pin.labelIdx].equals("1")) {
                                            continue;
                                        } else { //decoy
                                            protAbr = prot.substring(Constants.decoyPrefix.length() - 1);
                                        }
                                    } else {
                                        protAbr = prot;
                                    }

                                    float[] arr;
                                    try {
                                        arr = fasta.protToPep.get(protAbr).detects;
                                    } catch (Exception e) { //no peptides qualify from this protein
                                        continue;
                                    }

                                    int idx = Arrays.binarySearch(arr, d);
                                    if (idx < 0) { //not found
                                        idx = (-1 * idx) - 1;
                                    } else {
                                        idx += 1; //don't want to include itself in calculation
                                    }
                                    float[] presenceArr = Arrays.copyOfRange(fasta.protToPep.get(protAbr).presence, idx, arr.length);
                                    float[] detectArr = Arrays.copyOfRange(arr, idx, arr.length);
                                    float total = 0f;
                                    for (int k = 0; k < presenceArr.length; k++) {
                                        if (presenceArr[k] == 0) {
                                            total += detectArr[k] - d;
                                        }
                                    }
                                    float diff = total / presenceArr.length;
                                    if (diff < minDiff) {
                                        minDiff = diff;
                                    }
                                    if (minDiff == 0) {
                                        break;
                                    }
                                }
                                writer.addValue("detect_subtract_missing", minDiff);
                                break;
                            case "detectProtSpearmanDiff":
                                SpearmansCorrelation sc = new SpearmansCorrelation();
                                r = pin.getRow();
                                prots = Arrays.copyOfRange(r, pin.pepIdx + 1, r.length);
                                double maxSpearmanDiff = -3;
                                float detect = predictedSpectra.getPreds().get(pep).detectability;;
                                for (String prot : prots) { //if more than one, this peptide is shared among proteins
                                    //skip protein if it is decoy and looking at target peptide
                                    String protAbr;
                                    if (prot.startsWith(Constants.decoyPrefix.substring(1))) {
                                        if (r[pin.labelIdx].equals("1")) {
                                            continue;
                                        } else {
                                            protAbr = prot.substring(Constants.decoyPrefix.length() - 1);
                                        }
                                    } else {
                                        protAbr = prot;
                                    }

                                    float[] arr;
                                    try {
                                        arr = fasta.protToPep.get(protAbr).detects;
                                    } catch (Exception e) { //no peptides qualify from this protein
                                        continue;
                                    }

                                    float[] counts = fasta.protToPep.get(protAbr).spectralCounts;

                                    //only add if not 0 spectral counts (lots of missing ones, need to be more lenient for targets)
                                    ArrayList<Double> newDetects = new ArrayList<>();
                                    ArrayList<Double> newCounts = new ArrayList<>();
                                    for (int k = 0; k < arr.length; k++) {
                                        if (counts[k] != 0) {
                                            if (! (arr[k] == detect)) { //will add later
                                                newDetects.add((double) arr[k]);
                                                newCounts.add((double) counts[k]);
                                            }
                                        }
                                    }
                                    if (newDetects.size() < 2) {
                                        continue;
                                    }
                                    double spear = sc.correlation(newDetects.stream().mapToDouble(dd -> dd).toArray(),
                                            newCounts.stream().mapToDouble(dd -> dd).toArray() );

                                    //add new pep to this calculation
                                    newDetects.add((double) detect);
                                    newCounts.add((double) predictedSpectra.getPreds().get(pep).counter);
                                    double spearDiff = sc.correlation(newDetects.stream().mapToDouble(dd -> dd).toArray(),
                                            newCounts.stream().mapToDouble(dd -> dd).toArray() ) - spear;
                                    if (spearDiff > maxSpearmanDiff) {
                                        maxSpearmanDiff = spearDiff;
                                    }
                                }
                                if (maxSpearmanDiff == -3) {
                                    maxSpearmanDiff = 0;
                                }
                                writer.addValue("detect_prot_spearman_diff", maxSpearmanDiff);
                                break;
                            case "deltaRTlinear":
                                if (Constants.noRTscores) {
                                    pepObj.deltaRT = 0;
                                }
                                writer.addValue("deltaRTlinear", pepObj.deltaRT);
                                break;
                            case "deltaRTbins":
                                if (Constants.noRTscores) {
                                    pepObj.deltaRTbin = 0;
                                }
                                writer.addValue("deltaRTbins", pepObj.deltaRTbin);
                                break;
                            case "deltaRTLOESS":
                                if (Constants.noRTscores) {
                                    pepObj.deltaRTLOESS = 0;
                                }
                                writer.addValue("delta_RT_loess", pepObj.deltaRTLOESS);
                                break;
                            case "deltaRTLOESSnormalized":
                                if (Constants.noRTscores) {
                                    pepObj.deltaRTLOESSnormalized = 0;
                                }
                                writer.addValue("delta_RT_loess_normalized", pepObj.deltaRTLOESSnormalized);
                                break;
                            case "RTzscore":
                                if (Constants.noRTscores) {
                                    pepObj.RTzscore = 0;
                                }
                                writer.addValue("RTzscore", pepObj.RTzscore);
                                break;
                            case "RTprobability":
                                if (Constants.noRTscores) {
                                    pepObj.RTprob = 0;
                                }
                                writer.addValue("RTprobability", pepObj.RTprob);
                                break;
                            case "RTprobabilityUnifPrior":
                                if (Constants.noRTscores) {
                                    writer.addValue("RT_probability_unif_prior", 0);
                                } else {
                                    float prob = StatMethods.probabilityWithUniformPrior(unifPriorSize, unifProb,
                                            pepObj.scanNumObj.RTbinSize, (float) pepObj.RTprob);
                                    writer.addValue("RT_probability_unif_prior", prob);
                                }
                                break;
                            case "brayCurtis":
                                writer.addValue("bray_curtis", pepObj.spectralSimObj.brayCurtis());
                                break;
                            case "cosineSimilarity":
                                writer.addValue("cosine_similarity", pepObj.spectralSimObj.cosineSimilarity());
                                break;
                            case "spectralContrastAngle":
                                writer.addValue("spectral_contrast_angle", pepObj.spectralSimObj.spectralContrastAngle());
                                break;
                            case "euclideanDistance":
                                writer.addValue("euclidean_distance", pepObj.spectralSimObj.euclideanDistance());
                                break;
                            case "pearsonCorr":
                                writer.addValue("pearson_corr", pepObj.spectralSimObj.pearsonCorr());
                                break;
                            case "dotProduct":
                                writer.addValue("dot_product", pepObj.spectralSimObj.dotProduct());
                                break;
                            case "deltaIMLOESS":
                                writer.addValue("delta_IM_loess", pepObj.deltaIMLOESS);
                                break;
                            case "deltaIMLOESSnormalized":
                                writer.addValue("delta_IM_loess_normalized", pepObj.deltaIMLOESSnormalized);
                                break;
                            case "IMprobabilityUnifPrior":
                                float prob = StatMethods.probabilityWithUniformPrior(unifPriorSizeIM[pepObj.charge - 1], unifProbIM[pepObj.charge - 1],
                                        pepObj.scanNumObj.IMbinSize, (float) pepObj.IMprob);
                                writer.addValue("IM_probability_unif_prior", prob);
                                break;
                            case "y_matched_intensity":
                                writer.addValue("y_matched_intensity", pepObj.matchedIntensities.get("y"));
                                break;
                            case "b_matched_intensity":
                                writer.addValue("b_matched_intensity", pepObj.matchedIntensities.get("b"));
                                break;
                            case "a_matched_intensity":
                                writer.addValue("a_matched_intensity", pepObj.matchedIntensities.get("a"));
                                break;
                            case "x_matched_intensity":
                                writer.addValue("x_matched_intensity", pepObj.matchedIntensities.get("x"));
                                break;
                            case "c_matched_intensity":
                                writer.addValue("c_matched_intensity", pepObj.matchedIntensities.get("c"));
                                break;
                            case "z_matched_intensity":
                                writer.addValue("z_matched_intensity", pepObj.matchedIntensities.get("z"));
                                break;
                            case "y-NL_matched_intensity":
                                writer.addValue("y-NL_matched_intensity", pepObj.matchedIntensities.get("y-NL"));
                                break;
                            case "b-NL_matched_intensity":
                                writer.addValue("b-NL_matched_intensity", pepObj.matchedIntensities.get("b-NL"));
                                break;
                            case "a-NL_matched_intensity":
                                writer.addValue("a-NL_matched_intensity", pepObj.matchedIntensities.get("a-NL"));
                                break;
                            case "x-NL_matched_intensity":
                                writer.addValue("x-NL_matched_intensity", pepObj.matchedIntensities.get("x-NL"));
                                break;
                            case "c-NL_matched_intensity":
                                writer.addValue("c-NL_matched_intensity", pepObj.matchedIntensities.get("c-NL"));
                                break;
                            case "z-NL_matched_intensity":
                                writer.addValue("z-NL_matched_intensity", pepObj.matchedIntensities.get("z-NL"));
                                break;
                            case "precursor_matched_intensity":
                                writer.addValue("precursor_matched_intensity", pepObj.matchedIntensities.get("precursor"));
                                break;
                            case "precursor-NL_matched_intensity":
                                writer.addValue("precursor-NL_matched_intensity", pepObj.matchedIntensities.get("precursor-NL"));
                                break;
                            case "internal_matched_intensity":
                                writer.addValue("internal_matched_intensity", pepObj.matchedIntensities.get("internal"));
                                break;
                            case "internal-NL_matched_intensity":
                                writer.addValue("internal-NL_matched_intensity", pepObj.matchedIntensities.get("internal-NL"));
                                break;
                            case "immonium_matched_intensity":
                                writer.addValue("immonium_matched_intensity", pepObj.matchedIntensities.get("immonium"));
                                break;
                            case "unknown_matched_intensity":
                                writer.addValue("unknown_matched_intensity", pepObj.matchedIntensities.get("unknown"));
                                break;
                            case "y_pred_intensity":
                                writer.addValue("y_pred_intensity", pepObj.predIntensities.get("y"));
                                break;
                            case "b_pred_intensity":
                                writer.addValue("b_pred_intensity", pepObj.predIntensities.get("b"));
                                break;
                            case "a_pred_intensity":
                                writer.addValue("a_pred_intensity", pepObj.predIntensities.get("a"));
                                break;
                            case "x_pred_intensity":
                                writer.addValue("x_pred_intensity", pepObj.predIntensities.get("x"));
                                break;
                            case "c_pred_intensity":
                                writer.addValue("c_pred_intensity", pepObj.predIntensities.get("c"));
                                break;
                            case "z_pred_intensity":
                                writer.addValue("z_pred_intensity", pepObj.predIntensities.get("z"));
                                break;
                            case "y-NL_pred_intensity":
                                writer.addValue("y-NL_pred_intensity", pepObj.predIntensities.get("y-NL"));
                                break;
                            case "b-NL_pred_intensity":
                                writer.addValue("b-NL_pred_intensity", pepObj.predIntensities.get("b-NL"));
                                break;
                            case "a-NL_pred_intensity":
                                writer.addValue("a-NL_pred_intensity", pepObj.predIntensities.get("a-NL"));
                                break;
                            case "x-NL_pred_intensity":
                                writer.addValue("x-NL_pred_intensity", pepObj.predIntensities.get("x-NL"));
                                break;
                            case "c-NL_pred_intensity":
                                writer.addValue("c-NL_pred_intensity", pepObj.predIntensities.get("c-NL"));
                                break;
                            case "z-NL_pred_intensity":
                                writer.addValue("z-NL_pred_intensity", pepObj.predIntensities.get("z-NL"));
                                break;
                            case "precursor_pred_intensity":
                                writer.addValue("precursor_pred_intensity", pepObj.predIntensities.get("precursor"));
                                break;
                            case "precursor-NL_pred_intensity":
                                writer.addValue("precursor-NL_pred_intensity", pepObj.predIntensities.get("precursor-NL"));
                                break;
                            case "internal_pred_intensity":
                                writer.addValue("internal_pred_intensity", pepObj.predIntensities.get("internal"));
                                break;
                            case "internal-NL_pred_intensity":
                                writer.addValue("internal-NL_pred_intensity", pepObj.predIntensities.get("internal-NL"));
                                break;
                            case "immonium_pred_intensity":
                                writer.addValue("immonium_pred_intensity", pepObj.predIntensities.get("immonium"));
                                break;
                            case "unknown_pred_intensity":
                                writer.addValue("unknown_pred_intensity", pepObj.predIntensities.get("unknown"));
                                break;
                            case "y_peak_counts":
                                writer.addValue("y_peak_counts", pepObj.peakCounts.get("y"));
                                break;
                            case "b_peak_counts":
                                writer.addValue("b_peak_counts", pepObj.peakCounts.get("b"));
                                break;
                            case "a_peak_counts":
                                writer.addValue("a_peak_counts", pepObj.peakCounts.get("a"));
                                break;
                            case "x_peak_counts":
                                writer.addValue("x_peak_counts", pepObj.peakCounts.get("x"));
                                break;
                            case "c_peak_counts":
                                writer.addValue("c_peak_counts", pepObj.peakCounts.get("c"));
                                break;
                            case "z_peak_counts":
                                writer.addValue("z_peak_counts", pepObj.peakCounts.get("z"));
                                break;
                            case "y-NL_peak_counts":
                                writer.addValue("y-NL_peak_counts", pepObj.peakCounts.get("y-NL"));
                                break;
                            case "b-NL_peak_counts":
                                writer.addValue("b-NL_peak_counts", pepObj.peakCounts.get("b-NL"));
                                break;
                            case "a-NL_peak_counts":
                                writer.addValue("a-NL_peak_counts", pepObj.peakCounts.get("a-NL"));
                                break;
                            case "x-NL_peak_counts":
                                writer.addValue("x-NL_peak_counts", pepObj.peakCounts.get("x-NL"));
                                break;
                            case "c-NL_peak_counts":
                                writer.addValue("c-NL_peak_counts", pepObj.peakCounts.get("c-NL"));
                                break;
                            case "z-NL_peak_counts":
                                writer.addValue("z-NL_peak_counts", pepObj.peakCounts.get("z-NL"));
                                break;
                            case "precursor_peak_counts":
                                writer.addValue("precursor_peak_counts", pepObj.peakCounts.get("precursor"));
                                break;
                            case "precursor-NL_peak_counts":
                                writer.addValue("precursor-NL_peak_counts", pepObj.peakCounts.get("precursor-NL"));
                                break;
                            case "internal_peak_counts":
                                writer.addValue("internal_peak_counts", pepObj.peakCounts.get("internal"));
                                break;
                            case "internal-NL_peak_counts":
                                writer.addValue("internal-NL_peak_counts", pepObj.peakCounts.get("internal-NL"));
                                break;
                            case "immonium_peak_counts":
                                writer.addValue("immonium_peak_counts", pepObj.peakCounts.get("immonium"));
                                break;
                            case "unknown_peak_counts":
                                writer.addValue("unknown_peak_counts", pepObj.peakCounts.get("unknown"));
                                break;
                        }
                    }
                    //flush values to output
                    writer.writeValuesToRow();
                }
                pin.close();
                //endTime = System.nanoTime();
                //duration = (endTime - startTime);
                //System.out.println("Pin editing took " + duration / 1000000 +" milliseconds");
                writer.close();
                mzml.clear();
                if (Constants.renamePin == 1) {
                    System.out.println("Edited pin file at " + newOutfile);
                } else { //really should be 0
                    //move file at newOutfile to pinFiles[i] canonical name
                    File movedFile = new File(newOutfile);
                    pinFiles[i].delete();
                    movedFile.renameTo(pinFiles[i]);
                }
            }
        } catch (Exception e) {
            executorService.shutdown();
            e.printStackTrace();
            System.exit(1);
        }
        executorService.shutdown();
    }

    public static void main(String[] args) throws IOException, InterruptedException, ExecutionException, FileParsingException {
        //CHANGE PPM TO 10 if wide, narrow

        //CHANGE PPM TO 20 if cptac
//        editPin("C:/Users/kevin/Downloads/proteomics/cptac/2021-2-21/pep1XML1tmp/percToPep/CPTAC_CCRCC_W_JHU_LUMOS_C3L-01665_T.pin",
//                "C:/Users/kevin/OneDriveUmich/proteomics/mzml/cptac/CPTAC_CCRCC_W_JHU_LUMOS_C3L-01665_T.mzML",
//                "C:/Users/kevin/Downloads/proteomics/cptac/2021-2-21/pep1XML1tmp/percToPep/spectraRT.predicted.bin",
//                "C:/Users/kevin/Downloads/proteomics/cptac/2021-2-21/pep1XML1tmp/percToPep/detect_Predictions.txt",
//                ("RTprobabilityUnifPrior,deltaRTLOESS,deltaRTLOESSnormalized").split(","),
//                "C:/Users/kevin/Downloads/proteomics/cptac/2021-2-21/pep1XML1tmp/percToPep/test_");

//        editPin("C:/Users/kevin/Downloads/proteomics/narrow/",
//                "C:/Users/kevin/OneDriveUmich/proteomics/mzml/narrowWindow/23aug2017_hela_serum_timecourse_4mz_narrow_6.mzML",
//                "C:/Users/kevin/Downloads/proteomics/narrow/spectraRT.predicted.bin",
//                "C:/Users/kevin/Downloads/proteomics/narrow/detect_Predictions.txt",
//                (Constants.features).split(","),
//                "C:/Users/kevin/Downloads/proteomics/narrow/edited_");
//        editPin("C:/Users/kevin/Downloads/proteomics/wide/",
//                "C:/Users/kevin/OneDriveUmich/proteomics/mzml/wideWindow/23aug2017_hela_serum_timecourse_pool_wide_001.mzML",
//                "C:/Users/kevin/Downloads/proteomics/wide/spectraRT.predicted.bin",
//                "C:/Users/kevin/Downloads/proteomics/wide/detect_Predictions.txt",
//                ("cosineSimilarity,spectralContrastAngle,euclideanDistance,brayCurtis,pearsonCorr,dotProduct," +
//                        "deltaRTLOESS,deltaRTLOESSnormalized,RTprobabilityUnifPrior," +
//                        "detectSubtractMissing").split(","),
//                "C:/Users/kevin/Downloads/proteomics/wide/edited_");
        editPin("C:/Users/yangkl/Downloads/proteomics/timsTOF/20180819_TIMS2_12-2_AnBr_SA_200ng_HeLa_50cm_120min_100ms_11CT_3_A1_01_2769.pin",
                "C:/Users/yangkl/OneDriveUmich/proteomics/mzml/" +
                        "20180819_TIMS2_12-2_AnBr_SA_200ng_HeLa_50cm_120min_100ms_11CT_3_A1_01_2769_uncalibrated.mgf",
                "C:/Users/yangkl/Downloads/proteomics/timsTOF/DIANN.predicted.bin",
                "C:/Users/yangkl/Downloads/proteomics/timsTOF/detect_Predictions.txt",
                ("deltaIMLOESS,deltaIMLOESSnormalized,IMprobabilityUnifPrior").split(","),
                "C:/Users/kevin/Downloads/proteomics/timsTOF/edited_");


    }
}
