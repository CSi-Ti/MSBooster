import org.apache.commons.lang.ArrayUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;

import static org.apache.commons.io.FileUtils.listFiles;

public class peptideFileCreator {
    private static String[] acceptableFormats = new String[] {"pDeep2", "pDeep3", "prosit"};

    public static HashSet<String> getUniqueHits(String[] allHits) {
        //remove duplicates from allHits
        //can reduce number of hits to a third
        HashSet<String> hSetHits = new HashSet<>();
        Collections.addAll(hSetHits, allHits);
        System.out.println("before filtering: " + allHits.length +
                ", after filtering: " + hSetHits.size());
        return hSetHits;
    }

    public static void createPeptideFile(String[] allHits, String outfile, String modelFormat) {
        //check that modelFormat is acceptable
        //doesn't actually work...
        //assert Arrays.asList(acceptableFormats).contains(modelFormat) :
        //        "choose one of the following as modelFormat: " + Arrays.toString(acceptableFormats);

        //filter out redundant peptides
        //this step can reduce number of predictions needed to 1/3, decreasing prediction time
        HashSet<String> hSetHits = getUniqueHits(allHits);

        //write to file
        try {
            FileWriter myWriter = new FileWriter(outfile);
            switch (modelFormat) {
                case "pDeep2":
                    myWriter.write("peptide" + "\t" + "modification" + "\t" + "charge\n");
                case "pDeep3":
                    myWriter.write("raw_name" + "\t" + "scan" + "\t" + "peptide" + "\t" +
                            "modinfo" + "\t" + "charge\n");
                case "prosit":
                    myWriter.write("modified_sequence" + "," + "collision_energy" + "," + "precursor_charge\n");
                    //instances of null being added because no n-acetyl
                    hSetHits.remove(null);
            }

            for (String hSetHit : hSetHits) {
                myWriter.write(hSetHit + "\n");
            }

            myWriter.close();
            System.out.println("Successfully wrote to the file.");
        } catch (IOException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException {
        //get all files to be analyzed
        //FileCreator x = new FileCreator("C:/Users/kevin/OneDriveUmich/proteomics/pepxml/rank4/");
        //Collection<File> x = listFiles(new File("C:/Users/kevin/OneDriveUmich/proteomics/pepxml/"),
        //        new String[]{"pepXML"}, true);
        Collection<File> x = listFiles(new File("C:/Users/kevin/Downloads/proteomics/ups/pepxml/"),
                new String[]{"xml"}, true);

        //read in pepXML files
        String[] allHits = new String[0];
        for (File f : x) {
            String fileName = f.getCanonicalPath();
            System.out.println(fileName);
            pepXMLReader xmlReader = new pepXMLReader(fileName);
            xmlReader.createPDeep3List();
            //xmlReader.createPDeepListNoMods();
            //xmlReader.createPrositList(35);
            allHits = (String[]) ArrayUtils.addAll(allHits, xmlReader.allHitsPDeep);
        }

        //create file for pDeep2 prediction
        createPeptideFile(allHits, "C:/Users/kevin/Downloads/proteomics/ups/peptides_for_pDeep3.tsv",
                "pDeep3");

        //got predictions for all peptides in pepXML
        //python predict.py
        //{'nce': 0.27, 'instrument': 'QE', 'input': 'narrow1rank1.txt', 'output': 'narrow1rank1_pred.txt'}
    }
}
