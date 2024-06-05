import io.github.pixee.security.SystemCommand;
import java.util.*;
import java.io.*;
import java.lang.*;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.nio.channels.FileChannel;
import epic.sequences.SemiCRF;
import epic.sequences.SemiConllNerPipeline;
import org.apache.commons.lang3.math.NumberUtils;

public class Tester {
    public static int batchSize = 10;
    public static String methodChoice = "LC";
    public static double noise = 0;//0.15/(4000/274); // 0 = no simulated noise.
    public static boolean boo =true;
    public static List<String[]> trainingStrings = new ArrayList<>();
    public static String[] trainingString = {"--train",
            "data/PoolData/labeledPool.conll",
            "--test", "data/epicEvaluationTestSet/epicEvalutationTestSet.conll",
            "--modelOut", "data/our_malware.ser.gz","--useStochastic","false","--regularization","1"};
    public static boolean noiseCut = false;
    public static boolean error = false;
    public static double threshold = 0;
    public static boolean quadraticBatchSize = false;
    public static int originalBatchSize = 0;
    public static boolean infodens = false;
    public static boolean random = false;
    public static int labeledPoolSize = 0;
    public static List<SemiCRF<String, String>> models = new ArrayList<>();
    public static File fileNameUnlabeledSet;
    public static File fileNameLabeledSet;
    public static String modelFileName = "./data/our_malware.ser.gz";
    public static int totalPoolSize = 0;

    /**
     * Active learning tester
     * @param args second input is batch size
    Input "train" to start the run of with training
    Input "stochastic" to use the stochastic epic model
    Input "gibbs"/"lc" to choose active learning method
    Input "noise" to add batch cutting
    Input "error" for error adjustment
    Input "db" to retrain the database
     */

    public static void main(String[] args) {
        //************* SETUP *************
        Properties prop = new Properties();
        String pathToEpic = "";
        try {
            prop.load(new FileInputStream("src/main/resources/config.properties"));
            pathToEpic = prop.getProperty("pathToEpic");

        } catch (IOException ex) {
            System.out.println("Could not find config file. " + ex);
            System.exit(0);
        }
        copyFile(pathToEpic); //Copys sets to txt files
        fileNameUnlabeledSet = new File(pathToEpic + "/epic/data/PoolData/unlabeledPool.txt");
        fileNameLabeledSet = new File(pathToEpic+ "/epic/data/PoolData/labeledPool.txt");
        totalPoolSize = getPoolSize(fileNameLabeledSet, fileNameUnlabeledSet);





        try {
            PrintWriter pw=new PrintWriter(new FileOutputStream(
                    new File("data/stats.txt"),
                    true /* append = true */));
            String timeStamp = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(new Date());
            try {
                pw.append("*\n*\nTimestamp: "+timeStamp+" Training stats:\n");//Kör append när man vill köra flera körningar är detta vettigt
                pw.close();
                pw = new PrintWriter(new FileOutputStream(
                        new File("data/labeledRunSize.txt"),true));
                pw.append("*\n*\nTimestamp: "+timeStamp+" Labeled pool size:\n");
                pw.close();
                pw = new PrintWriter(new FileOutputStream(
                        new File("data/PositivePercentagePerBatch.txt"),true));
                pw.append("*\n*\nTimestamp: "+timeStamp+" Positive Percentage:\n");
                pw.close();
            } catch(IOException fe){
                System.out.println("Unable to open PrintWriter labeledRunSize.txt: "+ fe);
            }

            setStaticVariables(args,pathToEpic);

            System.out.println("Welcome");
            long startTime = System.currentTimeMillis();
            double noiseParameter = 1;
            String s = null;
            List<List<Double>> informationDensities = getInfoDens(infodens,pathToEpic);
            System.out.println("InfoDens is of size: "+ informationDensities.size());

            // Initialize s
            SelectQuery sq = new SelectQuery();
            SelectQueryRandom sqr = new SelectQueryRandom();
            CreatePythonFile cp = new CreatePythonFile();
            Batch b;

            System.out.println("Before writer");
            PrintWriter writer = new PrintWriter(pathToEpic + "/epic/data/unsure.txt", "UTF-8");
            FileWriter posWrite;

            List<Double> batch = new ArrayList<Double>();
            batch.add(0.0);
            List<Double> batchAndPercentage;
            Double posPercentage;
            Double posPercentageUnlabeled;
            int c = 0;


            boolean labelNewBatch = true;

            // *********** SETUP DONE **************
            // *********** START RUNS **************
            while(boo ) {
                System.out.println("Batchsize: "+batch.size()+ " LabeledPoolSize: "+labeledPoolSize);
                System.out.println("labelNewBatch: "+labelNewBatch);
                if (labelNewBatch) {
                    c++;
                    if(quadraticBatchSize) {
                        batchSize =  c*c* originalBatchSize;
                        if (batchSize>10000){batchSize = 10000;}

                    }
                    System.out.println("Batchsize: "+batchSize);
                    System.out.println("******** Batch number " + c + " evaluating **********\n");

                    //********** NOISE ADAPTION *************
                    if (noiseCut) { //Noise adjustment -> don't pick the hardest
                        batchAndPercentage =  getBatchNoiseCut(noiseParameter,sq, informationDensities);
                        batch = batchAndPercentage.subList(0,batchAndPercentage.size()-2);
                        posPercentage = batchAndPercentage.get(batchAndPercentage.size()-1);
                        posPercentageUnlabeled = 0.0;
                        addLabeledSizeToFile(pw);
                    } else{
                        System.out.println("Batch is of length (before)" + batchSize);
                        //********** RANDOM *************
                        if(random){ b = sqr.SelectQueryRandom(fileNameUnlabeledSet, batchSize);}
                        //********** NO ADD-ONS *************
                        else {
                            System.out.println("**********BATCH SIZE BFOR SELECTQUERY*********"+batchSize);
                            System.out.println("**********labeled pool size BFOR SELECTQUERY*********"+labeledPoolSize+ " "+totalPoolSize);
                            b = sq.SelectQuery(fileNameUnlabeledSet, batchSize, methodChoice, models, threshold, informationDensities);
                        }
                        batch = b.getIds();
                        labeledPoolSize += batch.size();
                        System.out.println("Labeled pool size: "+labeledPoolSize);
                        addLabeledSizeToFile(pw);
                        System.out.println("Batch is of length (no noise)" + batch.size());
                        posPercentage = b.getPercentagePositiveSentences();
                        posPercentageUnlabeled = b.getUnlabeledPercentage();

                    }

                    //*********** ADD CHOSEN BATCH AND RETRAIN ***********
                    System.out.println("Positive percentage: "+posPercentage);
                    moveBatch(cp,noise,batch,labelNewBatch);
                    posWrite = new FileWriter(pathToEpic + "/epic/data/PositivePercentagePerBatch.txt", true);
                    posWrite.append(posPercentage.toString()+" "+ posPercentageUnlabeled.toString()+"\n");
                    posWrite.close();
                    Train(trainingStrings, pathToEpic);
                    System.out.print("Finished training");
                    if (batch.size() < 50 || totalPoolSize-labeledPoolSize < 0) {
                        break;
                    }
                    System.out.println();
                    if (c == 200){
                        labelNewBatch = false;
                    }
                }
                //************ RELABEL ************
                else if (!labelNewBatch & error) { //"Relabel"
                    System.out.println("************RELABELING********\n" +
                            "              ******\n             **********\n");
                    b = sq.SelectQuery(fileNameLabeledSet, 200, "error", models,threshold,informationDensities);
                    System.out.println("B size: "+b.getIds().size());
                    writeUnsure(b,200, writer);

                    if (batch.size() == 0) {
                        break;
                    }

                    moveBatch(cp,noise,batch,labelNewBatch);

                    Train(trainingStrings, pathToEpic);

                    labelNewBatch = true;
                }

            }
            long endTime = System.currentTimeMillis();

            System.out.println("That took " + (endTime - startTime) + " milliseconds");
        } catch (FileNotFoundException ex) {
            System.out.println(
                    "Unable to open file unsure.txt or stats.txt: " + ex);
        } catch (IOException ex) {
            System.out.println(
                    "Error reading file unsure.txt or stats.txt: "+ ex);
        }
    }

    /**
     * Prints the labeled pool size to a file. Useful when plotting F1 values vs n of labeled objects
     * @param pw A printwriter to write to a file with
     */
    private static void addLabeledSizeToFile(PrintWriter pw){
        try{
            pw = new PrintWriter(new FileOutputStream(
                    new File("data/labeledRunSize.txt"),true));
            pw.append(labeledPoolSize+"\n");
            pw.close();
        } catch (IOException f) {
            System.out.println("Could not open labeledRunSize.txt: "+f);
        }
    }

    /**
     * Used when noise is applied. Specifies an enlarged batch size to select, where the most uncertain values are to be cut
     * so that a batch of the original batch size remains at the end.
     * @param noiseParameter Adaptable parameter to change the amout to add/cut
     * @param sq The SelectQuery object
     * @param informationDensities Added so as to make possible the combination of information density with noise
     * @return list of the object IDs in the batch
     */
    private static List<Double> getBatchNoiseCut(double noiseParameter,SelectQuery sq, List<List<Double>> informationDensities){
        double sizeOfLabeledPool = sizeOfFile(fileNameLabeledSet);
        double sizeOfUnlabeledPool = sizeOfFile(fileNameUnlabeledSet);
        double positives = 0.0;
        System.out.println("Total pool: "+ totalPoolSize+ "  labeled pool: "+sizeOfLabeledPool+ " size unlabeled: "+ sizeOfUnlabeledPool);
        int amountToCut = (int) ((sizeOfLabeledPool)*
                (sizeOfLabeledPool * noiseParameter/totalPoolSize));
        System.out.println("Cuttin away "+amountToCut);
        Batch b = sq.SelectQuery(fileNameUnlabeledSet, batchSize+amountToCut, methodChoice, models,threshold, informationDensities);
        List<Double> batch = b.sortBatchIds();
        List<String> sentences = b.sortBatchSentences();
        System.out.println("Batch before cut is of length (noise)" + batch.size());
        if (batch.size()>batchSize) {
            batch = batch.subList(0, batchSize);
            sentences = sentences.subList(0, batchSize);
        }
        for (int i = 0; i < sentences.size(); i++) {
            if (sentences.get(i).contains("_MALWARE")) {
                positives++;
            }
            else{
                System.out.println("MALWARE NOT found in '"+sentences.get(i)+"'");
            }
        }
        labeledPoolSize += batch.size();
        System.out.println("Batch is of length (noise)" + batch.size());
        System.out.println("Labeled pool size: "+labeledPoolSize);

        batch.add(positives/batch.size());
        return batch;
    }
    private static void copyFile(String pathToEpic){
        File sourceFile1 = new File(pathToEpic + "/epic/data/PoolData/unlabeledPoolStart.txt");
        File destFile1 = new File(pathToEpic + "/epic/data/PoolData/unlabeledPool.txt");
        File sourceFile2 = new File(pathToEpic + "/epic/data/PoolData/labeledPoolStart.txt");
        File destFile2 = new File(pathToEpic + "/epic/data/PoolData/labeledPool.txt");
        File sourceFile3 = new File(pathToEpic + "/epic/data/PoolData/labeledPoolStart.conll");
        File destFile3 = new File(pathToEpic + "/epic/data/PoolData/labeledPool.conll");

        FileChannel source1 = null;
        FileChannel destination1 = null;
        FileChannel source2 = null;
        FileChannel destination2 = null;
        FileChannel source3 = null;
        FileChannel destination3 = null;
        try{
            try {
                source1 = new FileInputStream(sourceFile1).getChannel();
                destination1 = new FileOutputStream(destFile1).getChannel();
                destination1.transferFrom(source1, 0, source1.size());
                source2 = new FileInputStream(sourceFile2).getChannel();
                destination2 = new FileOutputStream(destFile2).getChannel();
                destination2.transferFrom(source2, 0, source2.size());
                source3 = new FileInputStream(sourceFile3).getChannel();
                destination3 = new FileOutputStream(destFile3).getChannel();
                destination3.transferFrom(source3, 0, source3.size());
            }
            finally {
                if(source1 != null) {
                    source1.close();
                }
                if(destination1 != null) {
                    destination1.close();
                }
                if(source2 != null) {
                    source2.close();
                }
                if(destination2 != null) {
                    destination2.close();
                }
                if(source3 != null) {
                    source3.close();
                }
                if(destination3 != null) {
                    destination3.close();
                }
            }
        }
        catch (IOException ex) {
            System.out.println(
                    "Something went wrong when trying to copy labeledPoolStart and unlabeledPoolStart" +
                            "in the beginning of the simulation: " + ex);
            System.exit(0);
        }


    }

    /**
     * Gets the size (number of lines) of a file
     * @param fileName the file which size is wanted
     * @return file size
     */
    private static double sizeOfFile(File fileName){
        System.out.println("******** Create all pools and datasets **********\n");
        String s = null;
        double size = 0.0;
        try {
            FileReader tmpR = new FileReader(fileName);
            BufferedReader tmp = new BufferedReader(tmpR);
            while ((tmp.readLine()) != null) {
                size++;
            }
        } catch (IOException ex) {
            System.out.println(
                    "Could not open file" + fileName +" for reading: " + ex);
        }
        return size;

    }

    /**
     * Splits the database into training and test pools, training further split into labeled and "unlabeled" pool
     * Then writes these pools to text files for easier access
     * @param noise The level of simluated noise when writing to files
     */
    private static void splitAndWriteDB(double noise){
        System.out.println("******** Create all pools and datasets **********\n");
        String s = null;
        try {
            Process p = SystemCommand.runCommand(Runtime.getRuntime(), "python src/main/scala/JavaProject/PythonScripts/writeFilesFromDatabase.py 0.8 1"+Double.toString(noise));
            BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(p.getInputStream()));
            BufferedReader stdError = new BufferedReader(new
                    InputStreamReader(p.getErrorStream()));
            // read the output from the command
            System.out.println("Here is the standard output of the command writeFiles:\n");
            while ((s = stdInput.readLine()) != null) {
                System.out.println(s);
            }
        } catch (IOException ex) {
            System.out.println(
                    "Trying to run writeFilesFromDatabase.py but there was an error when opening the script: " + ex);
        }

    }

    /**
     * Get the size of the entire available pool (training pool)
     * @param fileNameLabeledSet File name for the labeled pool
     * @param fileNameUnlabeledSet File name for the unlabeled pool
     * @return pool size
     */
    private static int getPoolSize(File fileNameLabeledSet, File fileNameUnlabeledSet){
        int size = 0;
        try{
            FileReader tmpL = new FileReader(fileNameLabeledSet);
            FileReader tmpUn = new FileReader(fileNameUnlabeledSet);
            BufferedReader tmpl = new BufferedReader(tmpL);
            BufferedReader tmpun = new BufferedReader(tmpUn);
            while ((tmpl.readLine()) != null) {
                size++;
            }
            labeledPoolSize = size;
            while ((tmpun.readLine()) != null) {
                size++;
            }
            System.out.println("Total pool size is "+ size);
        } catch (IOException ex) {
            System.out.println(
                    "Could not open files \"" +fileNameLabeledSet+ "\" and  \""+ fileNameUnlabeledSet + "\" for reading: " + ex);
        }
        return size;

    }

    /**
     * Trains the model on the current labeled pool. When vote entropy is applied, this includes training the child models.
     * Noteworthy is that for each training, the stats of the current model is saved in data/stats.txt
     * @param trainingStrings Specifies how to train each model
     */
    private  static void Train(List<String[]> trainingStrings, String pathToEpic) {
        if (trainingStrings.size() > 1) { // If vote, split the labeled conll before training.
            System.out.println("******** Splitting child conll **********\n");
            String s = null;
            deleteDirectory(new File(pathToEpic+ "/epic/data/PoolData/unlabeledPool.txt"));
            try {
                Process p = SystemCommand.runCommand(Runtime.getRuntime(), "python src/main/scala/JavaProject/PythonScripts/makeChildConll.py "
                        + labeledPoolSize + " " + (trainingStrings.size() - 1)); // Input number of lines and number of models
                BufferedReader stdInput = new BufferedReader(new
                        InputStreamReader(p.getInputStream()));
                System.out.println("Here is the standard output of the command writeFiles:\n");
                while ((s = stdInput.readLine()) != null) {
                    System.out.println(s);
                }
            } catch (IOException ex) {
                System.out.println(
                        "Error when trying to run makeChildConll.py could not open file: " + ex);
            }
        }
        for (int i = 0; i < trainingStrings.size(); i++){
            if (i == 0) {
                System.out.println("Training main model");
            } else {
                System.out.println("Training child model " + i );
            }
        SemiConllNerPipeline.main(trainingStrings.get(i));
        }
    }

    /**
     * What can I say? This deletes a directory.
     * @param directory You guessed it, the directory to be deleted.
     */
    public static void deleteDirectory(File directory) {
        if(directory.exists()){
            File[] files = directory.listFiles();
            if(null!=files){
                for(int i=0; i<files.length; i++) {
                    if(files[i].isDirectory()) {
                        deleteDirectory(files[i]);
                    }
                    else {
                        files[i].delete();
                    }
                }
            }
        }
    }

    /**
     * Moves the selected batch from unlabeled to labeled.
     * @param cp The CreatePythonFile object used
     * @param noise Simulated noise level
     * @param batch The batch to be moved
     * @param newBatch A boolean to denote if it is a new labeling, or a relabeling
     */
    private static void moveBatch(CreatePythonFile cp, double noise,List<Double> batch, Boolean newBatch){
        System.out.println("******** Move a batch **********");
        cp.CreatePythonFile(batch, noise, newBatch);
        String s=null;
        try {
            Process p = Runtime.getRuntime().exec("python src/main/scala/JavaProject/PythonScripts/tmp.py");
            BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(p.getInputStream()));
            BufferedReader stdError = new BufferedReader(new
                    InputStreamReader(p.getErrorStream()));
            System.out.println("Here is the standard output of the command MoveBatch:\n");
            while ((s = stdInput.readLine()) != null) {
                System.out.println(s);
            }
        } catch (IOException ex) {
            System.out.println(
                    "Error when trying to move batch, could not run tmp.py: "+ ex);
        }

    }

    /**
     * Saves the objects' IDs to a file. (usefull after training on all data to find the most unsure objects)
     * @param b The batch of selected objects
     * @param batchSize Number of selected objects
     * @param writer The writer that writes to file
     */
    private static void writeUnsure(Batch b, int batchSize,PrintWriter writer){

        List<Double>batch = b.getIds();
        List<String>sentences = b.getSentences();
        double medSentLength = 0;
        double medLC = 0;
        for (int i = 0; i < batch.size(); i++) {
            String tmp = sentences.get(i);//.replace(". . \n","");
            //tmp = tmp.replace(". . B_MALWARE\n","");
            //tmp = tmp.replace(". . I_MALWARE\n","");
            String[] splitSentence = tmp.split(" ");
            medSentLength += (splitSentence.length-1)/batchSize;
            medLC += Double.parseDouble(splitSentence[0])/batchSize;
            writer.println(batch.get(i) + " " +sentences.get(i));
        }
        writer.println("Medium LC value: " + medLC+" Medium sent length: " + medSentLength);
        writer.close();
    }

    /**
     * Gets information density of each object to all other objects
     * @param id Boolean to denote if information density is used
     * @return information density of each object to all other objects, alongside with the id of the object
     */
    private static List<List<Double>> getInfoDens(boolean id, String pathToEpic){
        if(id) {
            List<List<Double>> infoDens = new ArrayList<>();
            List<Double> ids = new ArrayList<>();
            List<Double> densities = new ArrayList<>();
            File infoFile = new File(pathToEpic + "/epic/data/simTestFile.txt");
            try{
                FileReader tmp = new FileReader(infoFile);
                BufferedReader tmpb = new BufferedReader(tmp);
                String s = null;
                String[] split;

                while ((s=tmpb.readLine()) != null) {
                    split = s.split(" ");
                    ids.add(Double.parseDouble(split[0]));
                    densities.add(Double.parseDouble(split[1]));
                }
                infoDens.add(ids);
                infoDens.add(densities);
                System.out.println("InfoDens is of size: "+ infoDens.size() +" and length "+ infoDens.get(0).size());
                return infoDens;
            } catch (IOException ex) {
                System.out.println(
                        "Could not open file \""+infoFile+ "\" for reading " + ex);
            }
        }

        return new ArrayList<>();
    }

    /**
     * Sets up all static variables that specify what type of active learning is in use.
     * @param args Input arguments from the command line
     */
    private static void setStaticVariables(String[] args, String pathToEpic){
        System.out.println("*********************************************");
        System.out.println("****************Set Variables****************");

        trainingStrings.add(trainingString);
        models.add(getModel.getModel(modelFileName));

        for (int i = 0; i<args.length; i++) {
            args[i] = args[i].toLowerCase();
        }
        if (args.length>1&& !Arrays.asList(args).contains("threshold") ){
            batchSize = Integer.parseInt(args[0]);
            if (!Arrays.asList(args).contains("quad")) {
                System.out.println("batchSize has been manually set to: " + batchSize);
            }
        }

        if (Arrays.asList(args).contains("infodens")| Arrays.asList(args).contains("id") | Arrays.asList(args).contains("information")) {
            infodens = true;
            System.out.println("Implement information density");
        }

        if (Arrays.asList(args).contains("gibbs")) {
            methodChoice = "gibbs";
            System.out.println("Method has been manually set to Gibbs");
        }
        else{
            System.out.println("Method has been set to default LC");
        }

        if (Arrays.asList(args).contains("threshold")) {
            int index = Arrays.asList(args).indexOf("threshold");
            if (args.length> index+1) {
                if (NumberUtils.isNumber(args[index + 1])) {
                    threshold = Double.parseDouble(args[index + 1]);

                }
            }
            else { threshold = 100;}
            batchSize = Integer.MAX_VALUE;
            System.out.println("Threshold batch size set to: "+threshold);
        }

        if (Arrays.asList(args).contains("random")) {
            random = true;
            System.out.println("Random run with batch size "+batchSize);
        }

        if (Arrays.asList(args).contains("stochastic")|Arrays.asList(args).contains("stoc")){
            trainingString[7]= "true";
            System.out.println("Epic uses stochastic training");
        }
        else{
            System.out.println("Epic uses non-stochastic training");
        }
        if (Arrays.asList(args).contains("quad")|Arrays.asList(args).contains("quadratic")) {
            quadraticBatchSize = true;
            originalBatchSize = batchSize;
            System.out.println("Quadratic batch size starting at: "+batchSize);

        }

        if(Arrays.asList(args).contains("error")){
            error = true;
            System.out.println("Error adjustment active. Relabeling included.");
        }

        if(Arrays.asList(args).contains("noise")){
            noiseCut = true;
            System.out.println("Noise reduction active. Batch cutting included");
        }
        if (Arrays.asList(args).contains("db")|Arrays.asList(args).contains("database")) {
            splitAndWriteDB(noise);
            boo = false;
            System.out.println("Database is rewritten");

        }

        if(Arrays.asList(args).contains("vote")){
            methodChoice = "vote";
            int nOfModels = 10;
            int index = Arrays.asList(args).indexOf("vote");
            if (args.length> index+1) {
                if (NumberUtils.isNumber(args[index + 1])) {
                    nOfModels = Integer.parseInt(args[index + 1]);

                }
            }
            for (int m = 0; m < nOfModels ; m++) {
                String [] childTrainingString = trainingString.clone();
                childTrainingString[1] = "data/child_conlls/childLabeledPool"+(m+1)+".conll";
                childTrainingString[5] = "data/child_models/child_model"+(m+1)+".ser.gz";
                trainingStrings.add(childTrainingString.clone());
                models.add(getModel.getModel("./data/child_model"+(m+1)+".ser.gz"));
            }
            System.out.println(nOfModels+" models set for " + methodChoice +" entropy");
        }

        if(Arrays.asList(args).contains("train")){
            Train(trainingStrings, pathToEpic);
            try {
                PrintWriter pw = new PrintWriter(new FileOutputStream(
                        new File("data/labeledRunSize.txt"), true));
                pw.append(labeledPoolSize + "\n");
                pw.close();
            }
            catch(FileNotFoundException f){
                System.out.println("Error when opening labeledRunSize.txt: " +f);
            }
            System.out.println("Run starts with training the model before batch selection");
        }

        System.out.println("****************Variables Set****************");
        System.out.println("*********************************************");



    }
}
