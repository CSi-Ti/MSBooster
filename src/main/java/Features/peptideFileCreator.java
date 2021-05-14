package Features;

import org.apache.commons.lang3.ArrayUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import static org.apache.commons.io.FileUtils.listFiles;

public class peptideFileCreator {
    //private static String[] acceptableFormats = new String[] {"pDeep2", "pDeep3", "prosit"};

    public static HashSet<String> getUniqueHits(String[] allHits) {
        //remove duplicates from allHits
        //can reduce number of hits to a third
        HashSet<String> hSetHits = new HashSet<>();
        Collections.addAll(hSetHits, allHits);
        System.out.println("Before filtering: " + allHits.length +
                " peptides, after filtering: " + hSetHits.size() + " peptides");
        return hSetHits;
    }

    //infile is pepXML file locations
    public static void createPeptideFile(String infile, String outfile, String modelFormat, String psmFormat)
            throws IOException { //pepXML or pin
        long startTime = System.nanoTime();

        //file or directory
        Collection<File> x = new ArrayList<File>();
        File newFile = new File(infile);
        if (newFile.isFile()) {
            x.add(newFile);
        } else { //directory
            x = listFiles(new File(infile), new String[]{psmFormat}, false);
        }

        File[] infileArray = new File[x.size()];
        int i = 0;
        for (File f : x) {
            infileArray[i] = f;
            i++;
        }

        createPeptideFile(infileArray, outfile, modelFormat, psmFormat);
    }

    public static void createPeptideFile(File[] x, String outfile, String modelFormat, String psmFormat)
            throws IOException { //pepXML or pin
        long startTime = System.nanoTime();

        //read in pepXML files
        String[] allHits = new String[0];

        if (psmFormat.equals("pepXML")) {
            for (File f : x) {
                String fileName = f.getCanonicalPath();
                pepXMLReader xmlReader = new pepXMLReader(fileName);
                String[] hitsToAdd = new String[0];
                switch (modelFormat) {
                    case "pDeep3":
                        hitsToAdd = xmlReader.createPDeep3List();
                        break;
                    case "DeepMSPeptide": //ignores charge and mods
                        hitsToAdd = xmlReader.createDeepMSPeptideList();
                        break;
                    case "DeepMSPeptideAll": //ignores charge and mods
                        hitsToAdd = xmlReader.createDeepMSPeptideList();
                        break;
                    case "Diann":
                        hitsToAdd = xmlReader.createDiannList();
                        break;
                    case "prosit":
                        hitsToAdd = xmlReader.createPrositList(34);
                }

                allHits = ArrayUtils.addAll(allHits, hitsToAdd);
            }
        } else { //pin
            for (File f : x) {
                String fileName = f.getCanonicalPath();
                pinReader pin = new pinReader(fileName);
                String[] hitsToAdd = new String[0];
                switch (modelFormat) {
                    case "pDeep3":
                        hitsToAdd = pin.createPDeep3List();
                        break;
                    case "DeepMSPeptide": //ignores charge and mods
                        hitsToAdd = pin.createDeepMSPeptideList();
                        break;
                    case "DeepMSPeptideAll": //ignores charge and mods
                        hitsToAdd = pin.createDeepMSPeptideList();
                        break;
                    case "Diann":
                        hitsToAdd = pin.createDiannList();
                        break;
                    //TODO: prosit case
//                    case "prosit":
//                        hitsToAdd = pin.createPrositList(34);
                }
                pin.close();

                allHits = ArrayUtils.addAll(allHits, hitsToAdd);
            }
        }

        //filter out redundant peptides
        //this step can reduce number of predictions needed to 1/3, decreasing prediction time
        HashSet<String> hSetHits = getUniqueHits(allHits);
        if (modelFormat.equals("DeepMSPeptideAll")) {
            //add all targets from fasta
            FastaReader fasta = new FastaReader(Constants.fasta);
            for (ArrayList<String> array : fasta.protToPep.values()) {
                hSetHits.addAll(array);
            }
        }

        //write to file
        try {
            FileWriter myWriter = new FileWriter(outfile);
            switch (modelFormat) {
                case "prosit":
                    System.out.println("Writing prosit input file");
                    myWriter.write("modified_sequence" + "," + "collision_energy" + "," + "precursor_charge\n");
                    //instances of null being added because no n-acetyl
                    hSetHits.remove(null);
                    break;
                case "pDeep2":
                    System.out.println("Writing pDeep2 input file");
                    myWriter.write("peptide" + "\t" + "modification" + "\t" + "charge\n");
                    break;
                case "pDeep3":
                    System.out.println("Writing pDeep3 input file");
                    myWriter.write("raw_name" + "\t" + "scan" + "\t" + "peptide" + "\t" +
                            "modinfo" + "\t" + "charge\n");
                    break;
                case "fineTune":
                    myWriter.write("raw_name" + "\t" + "scan" + "\t" + "peptide" + "\t" +
                            "modinfo" + "\t" + "charge" + "\t" + "RTInSeconds\n");
                    break;
                case "DeepMSPeptide":
                    System.out.println("Writing DeepMSPeptide input file");
                    break; //no header
                case "DeepMSPeptideAll":
                    System.out.println("Writing DeepMSPeptideAll input file");
                    break; //no header
                case "Diann":
                    System.out.println("Writing DIA-NN input file");
                    myWriter.write("peptide" + "\t" + "charge\n");
                    break;
            }

            for (String hSetHit : hSetHits) {
                myWriter.write(hSetHit + "\n");
            }

            long endTime = System.nanoTime();
            long duration = (endTime - startTime);
            System.out.println(modelFormat + " input file generation took " + duration / 1000000000 +" seconds");
            myWriter.close();
            System.out.println("Input file at  " + outfile);
        } catch (IOException e) {
            System.out.println("An error occurred");
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException {
        createPeptideFile("C:/Users/kevin/Downloads/proteomics/cptac/2021-2-21/pepXMLtmp/",
                "C:/Users/kevin/Downloads/proteomics/cptac/2021-2-21/detectTest.csv",
                "DeepMSPeptideAll", "pin");
    }
}
