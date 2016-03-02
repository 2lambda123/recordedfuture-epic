package JavaProject;


import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.text.NumberFormat;

/**
 * Created by elin on 12/02/16.
 */
public class CreatePythonFile {

    public void CreatePythonFile(List<Double> list, double noise, Boolean newBatch) {

        try {
            PrintWriter writer = new PrintWriter("src/main/scala/JavaProject/PythonScripts/tmp.py", "UTF-8");

            writer.println("import pymongo \nimport sys \nimport os \nfrom pymongo import MongoClient \nfrom moveBatch import moveBatch");
            if (newBatch) { // Move new batch from unlabeled to labeled
                writer.print("rString = moveBatch([");
                System.out.println("Random id format: " + list.size());
                for (int i = 0; i < list.size() - 1; i++) {
                    double d = list.get(i);
                    NumberFormat nf = NumberFormat.getInstance();
                    nf.setMaximumFractionDigits(Integer.MAX_VALUE);
                    //System.out.println("Random id format: " +i +" " +nf.format(d));
                    writer.print(nf.format(d) + ",");
                }
                double d = list.get(list.size() - 1);
                NumberFormat nf = NumberFormat.getInstance();
                nf.setMaximumFractionDigits(Integer.MAX_VALUE);
                //System.out.println("Random id format: " +nf.format(d));
                writer.println(nf.format(d) + "]," + noise + ")");
                writer.println("print str(rString)");
                writer.close();
                System.out.println("I did it!");
            }
            else { // Add new instance of labeled (chosen for relabeling) to labeled pool
                writer.println("from relabelBatch import relabeledBatch");
                writer.print("rString = relabelBatch([");
                System.out.println("Random id format: " + list.size());
                for (int i = 0; i < list.size() - 1; i++) {
                    double d = list.get(i);
                    NumberFormat nf = NumberFormat.getInstance();
                    nf.setMaximumFractionDigits(Integer.MAX_VALUE);
                    //System.out.println("Random id format: " +i +" " +nf.format(d));
                    writer.print(nf.format(d) + ",");
                }
                double d = list.get(list.size() - 1);
                NumberFormat nf = NumberFormat.getInstance();
                nf.setMaximumFractionDigits(Integer.MAX_VALUE);
                //System.out.println("Random id format: " +nf.format(d));
                writer.println(nf.format(d) + "]," + noise + ")");
                writer.println("print str(rString)");
                writer.close();
                System.out.println("I did it!");
            }
        }
        catch(IOException ex) {
            System.out.println(
                    "Error: Something went wrong with CreatePythonFile " + ex);
        }

    }
}