package JavaProject

import java.util.*;
import java.io.*;
import java.lang.*;

public class EuclidianDistance {


    public double EuclidianDistance(double[] v1, double[] v2) {

        double norm = 0;
        for(int i = 0; i < v1.length; i++) {
            norm = norm + (v1[i] - v2[i]) * (v1[i] - v2[i]);
        }
        norm = Math.sqrt(norm);

        return norm;
    }

}