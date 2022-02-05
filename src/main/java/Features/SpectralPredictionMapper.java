package Features;

import umich.ms.fileio.exceptions.FileParsingException;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public interface SpectralPredictionMapper {

    HashMap<String, PredictionEntry> getPreds() throws IOException;
    void clear();

    float getMaxPredRT();

    static SpectralPredictionMapper createSpectralPredictionMapper(String file, ExecutorService executorService) throws IOException, InterruptedException, ExecutionException, FileParsingException {
        //detecting file extension
        String[] extensionSplit = file.split("\\.");
        String extension = extensionSplit[extensionSplit.length - 1];

        if (extension.equals("bin")) {
            return new DiannSpeclibReader(file);
        } else if (extension.equals("mgf")) {
            //if PredFull, use PredFullSpeclibReader class
            if (Constants.spectraRTPredModel.equals("PredFull")) {
                return new PredFullSpeclibReader(file, false, executorService);
            }
            //check if this works with pDeep
            return new mgfFileReader(file, false, executorService);
        } else if (extension.equals("msp")) {
            return new MspReader(file);
        } else {
            System.out.println("not valid prediction file format");
            return null;
        }
    }
}
