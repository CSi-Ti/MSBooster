package Features;

import java.util.ArrayList;
import java.util.Map;

//lots of different ways to format peptide string
public class PeptideFormatter {
    String base;
    String diann;
    String predfull;
    String prosit;
    String stripped;
    String baseCharge;
    String dlib;

    ArrayList<Integer> starts = new ArrayList<>();
    ArrayList<Integer> ends = new ArrayList<>();

    String charge;

    private void findPTMlocations() {
        for (int i = 0; i < base.length(); i++) {
            if (base.charAt(i) == '[') {
                starts.add(i);
            } else if (base.charAt(i) == ']') {
                ends.add(i);
            }
        }
    }

    private void pinTObase(String peptide) {
        //remove AA and period at beginning and end
        peptide = peptide.substring(2, peptide.length() - 2);

        //n and c term mods
        if (peptide.charAt(0) == 'n') {
            peptide = peptide.replace("n", "");
        }
        peptide = peptide.replace("c","");

        base = peptide;
        findPTMlocations();
    }

    private void diannTObase(String peptide) {

        peptide = peptide.replace("UniMod:","");
        //convert unimod to mass
        ArrayList<Integer> newStarts = new ArrayList<>();
        ArrayList<Integer> newEnds = new ArrayList<>();
        for (int i = 0; i < peptide.length(); i++) {
            if (peptide.charAt(i) == '[') {
                newStarts.add(i);
            } else if (peptide.charAt(i) == ']') {
                newEnds.add(i);
            }
        }
        for (int i = newStarts.size() - 1; i > -1; i--) {
            try {
                peptide = peptide.substring(0, newStarts.get(i) + 1) +
                        Constants.unimodtoModAAmass.get(peptide.substring(newStarts.get(i) + 1, newEnds.get(i))) +
                        peptide.substring(newEnds.get(i));
            } catch (Exception ignored) {

            }
        }

        base = peptide;
        findPTMlocations();
    }

    private void prositTObase(String peptide) {
        //remove (O) and add [57] to C
        base = peptide.replace("(ox)", "[" + Constants.oxidationMass + "]")
                .replace("C", "C[" + Constants.carbamidomethylationMass + "]");
        findPTMlocations();
    }

    private void predfullTObase(String peptide) {
        //remove (O) and add [57] to C
        base = peptide.replace("(O)", "[" + Constants.oxidationMass + "]")
                .replace("C", "C[" + Constants.carbamidomethylationMass + "]");
        findPTMlocations();
    }

    private void mspTObase(String peptide) {
        String[] pepSplit = peptide.split("\\|");

        base = pepSplit[0];
        if (! pepSplit[1].equals("0")) { //mods
            String[] mods = pepSplit[1].split("/");
            for (int i = mods.length - 1; i > 0; i--) {
                String[] modsSplit = mods[i].split(",");
                int position = Integer.parseInt(modsSplit[0]) + 1;
                base = base.substring(0, position) + "[" +
                        Constants.prositToModAAmass.get(modsSplit[2]) + "]" + base.substring(position);
            }
        }

        findPTMlocations();
    }

    private void baseTOstripped() {
        stripped = base;

        ArrayList<Integer> newStarts = new ArrayList<>(starts);
        newStarts.add(stripped.length());

        ArrayList<Integer> newEnds = new ArrayList<>();
        newEnds.add(-1);
        newEnds.addAll(ends);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < newStarts.size(); i++) {
            sb.append(stripped, newEnds.get(i) + 1, newStarts.get(i));
        }
        stripped = sb.toString();
    }

    private void baseTOdiann() {
        diann = base;

        for (int i = starts.size() - 1; i > -1; i--) {
            double reportedMass = Double.parseDouble(diann.substring(starts.get(i) + 1, ends.get(i)));
            boolean foundReplacement = false;
            for (Map.Entry<Double, Integer> entry : Constants.modAAmassToUnimod.entrySet()) {
                Double PTMmass = entry.getKey();

                if (Math.abs(PTMmass - reportedMass) < 0.01) {
                    diann = diann.substring(0, starts.get(i) + 1) + "UniMod:" + Constants.modAAmassToUnimod.get(PTMmass) +
                            diann.substring(ends.get(i));
                    foundReplacement = true;
                    break;
                }
            }
            if (! foundReplacement) {
                //DIANN won't predict this anyway
                diann = diann.substring(0, starts.get(i)) + diann.substring(ends.get(i) + 1);
            }
        }
    }

    private void baseTOpredfull() {
        predfull = base;

        //PredFull only supports oxM and assumes C is carbamidomethylated. Below, we format it this way, but also keep on other PTM masses so m/z values for fragments can be adjusted
        for (int i = starts.size() - 1; i > -1; i--) {
            double reportedMass = Double.parseDouble(predfull.substring(starts.get(i) + 1, ends.get(i)));
            if (starts.get(i) - 1 > -1) { //no changes to nterm mod
                if (Math.abs(Constants.oxidationMass - reportedMass) < 0.01 &&
                        predfull.charAt(starts.get(i) - 1) == 'M') {
                    predfull = predfull.substring(0, starts.get(i)) + "(O)" + predfull.substring(ends.get(i) + 1);
                } else { //mod unsupported, remove for now. Or C carbamidomethylation
                    predfull = predfull.substring(0, starts.get(i)) + predfull.substring(ends.get(i) + 1);
                }
            } else { //deal with nterm mod by deleting
                predfull = predfull.substring(0, starts.get(i)) + predfull.substring(ends.get(i) + 1);
            }
        }
    }

    private void predfullTOprosit() {
        prosit = predfull.replace("M(O)", "M(ox)");
    }

    private void strippedTOdlib() { dlib = stripped.replace("C", "C[" + Constants.carbamidomethylationMass + "]") + "|" + charge;
    }

    public PeptideFormatter(String peptide, Object c, String format) {
        charge = (String) c;

        if (format.equals("pin")) {
            pinTObase(peptide);
            baseTOdiann();
            baseTOstripped();
            baseTOpredfull();
            predfullTOprosit();
            strippedTOdlib();
            baseCharge = base + "|" + charge;
        }

        if (format.equals("diann")) {
            diann = peptide;
            diannTObase(peptide);
            baseTOstripped();
            baseTOpredfull();
            predfullTOprosit();
            baseCharge = base + "|" + charge;
        }

        if (format.equals("base")) {
            base = peptide;

            //find locations of PTMs
            for (int i = 0; i < base.length(); i++) {
                if (base.charAt(i) == '[') {
                    starts.add(i);
                } else if (base.charAt(i) == ']') {
                    ends.add(i);
                }
            }

            baseTOstripped();
            baseTOpredfull();
            baseTOdiann();
            predfullTOprosit();
            baseCharge = base + "|" + charge;
        }

        if (format.equals("predfull")) {
            predfull = peptide;
            predfullTObase(peptide);
            baseTOstripped();
            baseTOdiann();
            predfullTOprosit();
            baseCharge = base + "|" + charge;
        }

        if (format.equals("msp")) {
            mspTObase(peptide);
            baseTOstripped();
            baseTOpredfull();
            baseTOdiann();
            predfullTOprosit();
            baseCharge = base + "|" + charge;
        }

        if (format.equals("prosit")) {
            prosit = peptide;
            prositTObase(peptide);
            baseTOstripped();
            baseTOpredfull();
            baseTOdiann();
            baseCharge = base + "|" + charge;
        }
    }
}