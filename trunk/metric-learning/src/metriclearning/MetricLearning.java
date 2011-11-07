/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package metriclearning;

import au.com.bytecode.opencsv.CSVReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import libsvm.svm;
import libsvm.svm_model;
import libsvm.svm_node;
import libsvm.svm_parameter;
import libsvm.svm_problem;
import org.math.array.LinearAlgebra;
import uk.ac.shef.wit.simmetrics.similaritymetrics.Levenshtein;

/**
 *
 * @author Tommaso Soru
 */
public class MetricLearning {
    
    // the source cache
    static ArrayList<Resource> sources = new ArrayList<Resource>();
    
    // the target cache
    static ArrayList<Resource> targets = new ArrayList<Resource>();
    
    // the source properties
    static String[] sourceProperties;
    
    // the target properties
    static String[] targetProperties;
    
    // the source KB
//    static String sourcePath = "data/1-dblp-acm/DBLP2.csv";
    static String sourcePath = "data/dummy/sources.csv";
    
    // the target KB
//    static String targetPath = "data/1-dblp-acm/ACM.csv";
    static String targetPath = "data/dummy/targets.csv";
    
    // The oracle's knowledge is a mapping among instances of source KB
    // and target KB (oracle's answers).
//    static String mappingPath = "data/1-dblp-acm/DBLP-ACM_perfectMapping.csv";
    static String mappingPath = "data/dummy/couples.csv";
    static ArrayList<Couple> oraclesAnswers = new ArrayList<Couple>();
    
    // all elements in S×T
    static ArrayList<Couple> couples = new ArrayList<Couple>();
    
    // size of the most informative examples sets
    static int MOST_INF_SIZE = 10;
    static ArrayList<Couple> selected = new ArrayList<Couple>();
    static ArrayList<Couple> posSelected = new ArrayList<Couple>();
    static ArrayList<Couple> negSelected = new ArrayList<Couple>();
    
    static ArrayList<Couple> actualPos = new ArrayList<Couple>();
    static ArrayList<Couple> actualNeg = new ArrayList<Couple>();
    
    static ArrayList<Couple> answered = new ArrayList<Couple>();
    
    // The edit distance calculator
    static Levenshtein l;
    
    // the dimensions, ergo the number of similarities applied to the properties
    static int n;
    
    // linear classifier and its bias
    static double[] C;
    static double bias;
    
    // the model
    static svm_model model;
    
    // the similarity lower bound [0,1] for a couple to be a candidate
    static final double TAU = 0.5;

    // training errors upper bound
    static double NU = 0.01;

    // the learning rates
    static double eta_plus = 0.5, eta_minus = -0.5; 
    
    // weights (and counts) of the perceptron
    static double[] weights = new double[4032];
    static int[] counts = new int[4032];
    
    // sets to calculate precision and recall
    static double tp = 0, fp = 0, tn = 0, fn = 0;
    
    // vector used to normalize the weights
    static double[] oldmax;
    
    // output for the Matlab/Octave file
    static String outString = "";
    static boolean createOctaveScript = false;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        
        loadKnowledgeBases();
        loadMappings();
        initializeClassifier();
        
        l = new Levenshtein(n);
                
        // initialize the weights normalizer
        oldmax = new double[n];
        for(int i=0; i<n; i++)
            oldmax[i] = 1.0;
        
        // for all sources and for all targets
        for(Resource s : sources) {
            for(Resource t : targets) {
                Couple c = new Couple(s, t);
                couples.add(c);
                // compute the similarity
                c.initializeCount(n);
                computeSimilarity(c);
                System.out.print(c.getSimsum()+"\t");
            }
        }
        w("");
        
        double f1 = 0.0;
        for(int iter=0; ; iter++) {
//            w("\n----- ITER #"+iter+" -----");
            
            posSelected.clear();
            negSelected.clear();
            
            // compute gamma and update the set of most informative examples
            for(Couple c : couples) {
                if(!answered.contains(c)) {
                    computeGamma(c);
                    updateSelected(c);
                }
            }
//            w("MOST INF:"); // print the m.inf.ex.
//            for(Couple c : posSelected)
//                w("C("+c.getSource().getID()+","+c.getTarget().getID()+") = true");
//            for(Couple c : negSelected)
//                w("C("+c.getSource().getID()+","+c.getTarget().getID()+") = false");

            selected.clear();
            selected.addAll(posSelected);
            selected.addAll(negSelected);

            // ask the oracle
            for(int i=0; i<selected.size(); i++) {
                Couple couple = selected.get(i);
                if( isPositive( couple ) ) {
                    actualPos.add( couple );
                } else {
                    actualNeg.add( couple );
                }
                answered.add(couple);
                w("O("+couple.getSource().getID()+","+couple.getTarget().getID()+") = "+isPositive( couple ));
            }
            
            w("");
            for(int miter = 0; f1 != 1.0 && miter<100; miter++) {
                System.out.print(miter+".\t");
                f1 = computeF1();
                computeM();
            
                l.resetCount();
                for(Couple c: couples)
                    c.resetCount();

                // compute the new similarity according with the updated weights
                for(Couple c : couples) {
                    computeSimilarity(c);
                    System.out.print(c.getSimsum()+"\t");
                }
                w("");
                
            }
            
            // train classifier
            updateClassifier();
            
            System.out.print((iter+1)+"\t");
            f1 = computeF1();
            
            // loop break conditions
            if(answered.size() == couples.size() || f1 == 1.0)
                break;
            
            
        }
        
        double[][][] M = l.getCostsMatrix();
        for(int i=0; i<M.length; i++) {
            for(int j=0; j<M[i].length; j++)
                System.out.print(((double)(int)(M[i][j][0]*1000))/1000+"\t");
            w("");
        }
        
        if(createOctaveScript)
            createOctaveScript(0);
    }
    
    
    /**
     * W·X - b
     * @param c the couple
     * @return true if positive, false if negative
     */
    private static boolean classify(Couple c) {
        ArrayList<Double> sim = c.getSimilarities();
        double sum = 0.0;
        for(int i=0; i<n; i++)
            sum += sim.get(i) * C[i];
        sum = sum + bias;
//        w("sum is "+sum+"");
        if(sum <= 0)
            return true;
        else
            return false;
    }
    
    /**
     * This function returns true iff the source and the target have been mapped
     * together, so iff the couple (s,t) is positive.
     */
    private static boolean isPositive(Couple c) {
        String s = c.getSource().getID();
        String t = c.getTarget().getID();
        for(Couple pc : oraclesAnswers)
            if(pc.getSource().getID().equals(s) && pc.getTarget().getID().equals(t))
                return true;
        return false;
    }

    private static void loadKnowledgeBases() throws IOException {
        CSVReader reader = new CSVReader(new FileReader(sourcePath));
        String [] titles = reader.readNext(); // gets the column titles
        String [] nextLine;
        while ((nextLine = reader.readNext()) != null) {
            Resource r = new Resource(nextLine[0]);
            for(int i=0; i<nextLine.length; i++)
                if(!titles[i].toLowerCase().equals("id") && !titles[i].toLowerCase().equals("year")
//                         && !titles[i].toLowerCase().equals("venue")
                        )
                    r.setPropertyValue(titles[i], nextLine[i]);
            sources.add(r);
            n = r.getPropertyNames().size();
        }

        reader = new CSVReader(new FileReader(targetPath));
        titles = reader.readNext(); // gets the column titles
        while ((nextLine = reader.readNext()) != null) {
            Resource r = new Resource(nextLine[0]);
            for(int i=0; i<nextLine.length; i++)
                if(!titles[i].toLowerCase().equals("id") && !titles[i].toLowerCase().equals("year")
//                         && !titles[i].toLowerCase().equals("venue")
                        )
                    r.setPropertyValue(titles[i], nextLine[i]);
            targets.add(r);
        }
    }

    private static void loadMappings() throws IOException {
        CSVReader reader = new CSVReader(new FileReader(mappingPath));
        reader.readNext(); // skips the column titles
        String [] nextLine;
        while ((nextLine = reader.readNext()) != null) {
            oraclesAnswers.add(new Couple(new Resource(nextLine[0]), new Resource(nextLine[1])));
        }
    }

    private static double editDistance(String sourceStringValue, String targetStringValue, int k, Couple c) {
        return l.getDijkstraSimilarity(sourceStringValue, targetStringValue, k, c);
    }

    private static double mahalanobisDistance(double sourceDoubleValue, double targetDoubleValue, int k) {
        // TODO Mahalanobis distance algorithm
        return 0.0;
    }

    /** 
     * The initial classifier C should be the hyperplane that is equidistant
     * from points (0, ..., 0) and (1, ..., 1). Analytically, it's the vector
     * [-1, ..., -1, n/2].
     */
    private static void initializeClassifier() {
        C = new double[n];
        for(int i=0; i<C.length; i++) {
            C[i] = -1.0;
        }
        bias = (double)n / 2.0;
    }

    /**
     * Updates the classifier and builds a Matlab/Octave script to visualize
     * 2D or 3D graphs. SVM statements have been implemented for 2 classes only.
     */
    private static void updateClassifier() {
        svm_problem problem = new svm_problem();
        problem.l = actualPos.size()+actualNeg.size();
        svm_node[][] x = new svm_node[actualPos.size()+actualNeg.size()][n];
        double[] y = new double[actualPos.size()+actualNeg.size()];
        for(int i=0; i<actualPos.size(); i++) {
            ArrayList<Double> p = actualPos.get(i).getSimilarities();
            for(int j=0; j<p.size(); j++) {
                x[i][j] = new svm_node();
                x[i][j].index = j;
                x[i][j].value = p.get(j);
            }
            y[i] = 1;
        }
        for(int i=actualPos.size(); i<actualPos.size()+actualNeg.size(); i++) {
            ArrayList<Double> p = actualNeg.get(i-actualPos.size()).getSimilarities();
            for(int j=0; j<p.size(); j++) {
                x[i][j] = new svm_node();
                x[i][j].index = j;
                x[i][j].value = p.get(j);
            }
            y[i] = -1;
        }
        problem.x = x;
        problem.y = y;
        svm_parameter parameter = new svm_parameter();
        parameter.nu = NU;
        parameter.svm_type = svm_parameter.NU_SVC;
        parameter.kernel_type = svm_parameter.LINEAR;
        parameter.eps = 0.001;
        model = svm.svm_train(problem, parameter);
        // sv = ( nSV ; n )
        svm_node[][] sv = model.SV;
        // sv_coef = ( 1 ; nSV )
        double[][] sv_coef = model.sv_coef;
        
        // calculate w and b
        // w = sv' * sv_coef' = (sv_coef * sv)' = ( n ; 1 )
        // b = -rho
        double[][] w = new double[sv[0].length][sv_coef.length];
        int signum = (model.label[0] == -1.0) ? 1 : -1;
        
        w = LinearAlgebra.transpose(LinearAlgebra.times(sv_coef,toDouble(sv)));
        double b = -model.rho[0];
        
        for(int i=0; i<C.length; i++) {
            C[i] = signum * w[i][0];
            w("C["+i+"] = "+C[i]);
        }
        
        bias = signum * b;
        w("bias = "+bias);
        
        // if needed, save the Matlab/Octave script
        if(createOctaveScript) {
            s("hold off;",true);
            // for each dimension...
            for(int j=0; j<x[0].length; j++) {
                // all positives...
                s("x"+j+"p = [",false);
                for(int i=0; i<actualPos.size(); i++)
                    s(x[i][j].value+" ",false);
                s("];",true);
                // all negatives...
                s("x"+j+"n = [",false);
                for(int i=actualPos.size(); i<x.length; i++)
                    s(x[i][j].value+" ",false);
                s("];",true);
            }

            if(sv.length > 0) {

        //        double[][] xd = toDouble(x);
        //        for(int i=0; i<x.length; i++)
        //            w("#"+i+".\tprediction = "+predict(xd[i])+"\tactual = "+y[i]);

                for(int i=0; i<w.length; i++)
                    s("w"+i+" = "+w[i][0]+";",true);

                switch(n) {
                    case 2:
                        s("q = -(" + b + ")/w1;",true);
                        s("plot(x0p,x1p,'xb'); hold on; "
                                + "plot(x0n,x1n,'xr');",true);
                        s("xl = [0:0.025:1];",true);
                        s("yl = "+signum+" * xl * w0/w1 + q;",true);
                        s("plot(xl,yl,'k');",true);
                        s("axis([0 1 0 1]);",true);
                        break;
                    case 3:
                        s("if w2 == 0\nw2 = w1;\nw1 = 0;\nq = -("+b+")/w2;\n"
                                + "xT = x2;\nx2 = x1;\nx1 = xT;\nelse\nq = -(" + b + ")/w2;\nend",true);
                        s("plot3(x0p,x1p,x2p,'xb'); hold on; "
                                + "plot3(x0n,x1n,x2n,'xr');",true);
                        s("x = [0:0.025:1];",true);
                        s("[xx,yy] = meshgrid(x,x);",true);
                        s("zz = "+signum+" * w0/w2 * xx + "+signum+" * w1/w2 * yy + q;",true);
                        s("mesh(xx,yy,zz);",true);
                        s("axis([0 1 0 1 0 1]);",true);
                        break;
                }
                
            } else {
                // TODO what to do when there are no SVs?
            }
        }
    }

    private static void s(String out, boolean newline) {
        System.out.print(out+(newline ? "\n" : ""));
        outString += out+(newline ? "\n" : "");
    }

    private static double[][] toDouble(svm_node[][] sv) {
        double[][] t = new double[sv.length][sv[0].length];
        for(int i=0; i<sv.length; i++)
            for(int j=0; j<sv[i].length; j++)
                t[i][j] = sv[i][j].value;
        return t;
    }
    
    private static double predict(double[] values) {
        svm_node[] svm_nds = new svm_node[values.length];
        for(int i=0; i<values.length; i++) {
            svm_nds[i] = new svm_node();
            svm_nds[i].index = i;
            svm_nds[i].value = values[i];
        }

        return svm.svm_predict(model, svm_nds);
    }

    private static void createOctaveScript(int i) {
        try{
            // Create file 
            FileWriter fstream = new FileWriter("svmplot"+i+".m");
            BufferedWriter out = new BufferedWriter(fstream);
            out.write(outString);
            //Close the output stream
            out.close();
            outString = "";
        } catch (Exception e){//Catch exception if any
            System.err.println("Error: " + e.getMessage());
        }
    }
    
    private static void w(String string) {
        System.out.println(string);
    }

    private static void computeSimilarity(Couple couple) {
        Resource source = couple.getSource();
        Resource target = couple.getTarget();
        
        // get couple similarities
        Set<String> propNames = source.getPropertyNames();
        couple.clearSimilarities();
        int k = 0;
        for(String prop : propNames) {
            String sourceStringValue = source.getPropertyValue(prop);
            String targetStringValue = target.getPropertyValue(prop);
            double sourceNumericValue = 0.0, targetNumericValue = 0.0;
            boolean isNumeric = true;
            try {
                sourceNumericValue = Double.parseDouble(sourceStringValue);
                targetNumericValue = Double.parseDouble(targetStringValue);
            } catch(NumberFormatException e) {
//                    System.out.println(sourceStringValue + " OR " + targetStringValue + " isn't numeric.");
                isNumeric = false;
            }
            if(isNumeric) {
                couple.addSimilarity( mahalanobisDistance(sourceNumericValue, targetNumericValue, k) );
//                    System.out.println("sim(" + prop + ") = " + d);
            } else {
                couple.addSimilarity( editDistance(sourceStringValue, targetStringValue, k, couple) );
//                    System.out.println("sim(" + prop + ") = " + d);
            }
            k ++;
        }
    }

    /**
     * Computes the measure of how much a couple is informative.
     * Given a point Q and a classifier C, the distance D is
     * D = |(Q-P)·C| / ||C|| = |C·Q + bias| / ||C||
     * where P is a point on the hyperplane. The measure is
     * gamma = 1/D
     * TODO normalize the gamma
     * @param c The couple.
     */
    private static void computeGamma(Couple c) {
        ArrayList<Double> Q = c.getSimilarities();
        double numer = 0.0, denom = 0.0;
        for(int i=0; i<n; i++) {
            numer += Q.get(i) * C[i];
            denom += Math.pow(C[i], 2);
        }
        numer += bias;
        denom = Math.sqrt(denom);
        c.setGamma(Math.abs(denom/numer)); // gamma = 1/D
    }
    
    /**
     * Updates the positive and negative sets of best informative examples.
     * @param c 
     */
    private static void updateSelected(Couple c) {
        if(!answered.contains(c)) {
            if(classify(c)) {
                if(posSelected.size() < MOST_INF_SIZE) {
                    posSelected.add(c);
                    return;
                }
                double min = 99999;
                Couple c_min = null;
                for(Couple c1 : posSelected)
                    if(c1.getGamma() < min) {
                        min = c1.getGamma();
                        c_min = c1;
                    }
                if(c.getGamma() > min) {
                    posSelected.add(c);
                    posSelected.remove(c_min);
                }
            } else {
                if(negSelected.size() < MOST_INF_SIZE) {
                    negSelected.add(c);
                    return;
                }
                double min = 99999;
                Couple c_min = null;
                for(Couple c1 : negSelected)
                    if(c1.getGamma() < min) {
                        min = c1.getGamma();
                        c_min = c1;
                    }
                if(c.getGamma() > min) {
                    negSelected.add(c);
                    negSelected.remove(c_min);
                }
            }
        }
    }
    
    /**
     * Computes the F-Score, or F1.
     * @return The F-score.
     */
    private static double computeF1() {
        // compute the four categories
        tp = 0; fp = 0; tn = 0; fn = 0;
        for(Couple c : actualPos)
            if(classify(c)) {
                tp ++;
                c.setClassification(Couple.TP);
            } else {
                fp ++;
                c.setClassification(Couple.FP);
            }
        for(Couple c : actualNeg)
            if(classify(c)) {
                fn ++;
                c.setClassification(Couple.FN);
            } else {
                tn ++;
                c.setClassification(Couple.TN);
            }

        // compute f1
        double pre = tp / (tp + fp);
        double rec = tp / (tp + fn);
        double f1 = 2 * pre * rec / (pre + rec);

        w("mcs(0) = "+l.getMatrixCheckSum(0)+
//                    "\tmcs(1) = "+l.getMatrixCheckSum(1)+"\tmcs(2) = "+l.getMatrixCheckSum(2)+
                "\tf1 = "+f1+" (tp="+tp+", fp="+fp+", tn="+tn+", fn="+fn+")");
        return f1;
    }
    
    private static void computeM() {
        int[] countSumFalse = new int[4032];
        int[] countSumTrue = new int[4032];
        // for all properties...
        for(int k=0; k<n; k++) {
            // sum up error weights
            for(int i=0; i<4032; i++) {
                countSumFalse[i] = 0;
                countSumTrue[i] = 0;
            }
            for(Couple c : couples) {
                if(c.getClassification() == Couple.FP || c.getClassification() == Couple.FN) {
                    int[] cArr = c.getCountMatrixAsArray(k);
                    for(int i=0; i<4032; i++)
                        countSumFalse[i] += cArr[i];
                }
                if(c.getClassification() == Couple.TP || c.getClassification() == Couple.TN) {
                    int[] cArr = c.getCountMatrixAsArray(k);
                    for(int i=0; i<4032; i++)
                        countSumTrue[i] += cArr[i];
                }
            }
            
            weights = l.getCostsMatrixAsArray(k);
//            counts = l.getCountMatrixAsArray(k);
            double max = 0.0;
            for(int i=0; i<weights.length; i++) {
                // TODO change count method
                // we should count how many times a weight was used
                // *only* when we have errors (fp and fn).
                weights[i] = Math.pow(weights[i], 1) * oldmax[k] +
                        countSumFalse[i] * eta_plus +
                        countSumTrue[i] * eta_minus;
                if(weights[i] > max)
                    max = weights[i];
            }
            oldmax[k] = max;
            // update and normalize each weight
            // w' = root3(w / M)
            for(int i=0; i<weights.length; i++) {
                int a = i/63;
                int b = i%63;
                int b0 = b + (a<=b ? 1 : 0);
                if(weights[i] < 0)
                    weights[i] = 0;
                l.setWeight(a, b0, k, Math.pow(weights[i]/max, 1));
//                w(a+","+b0+","+k+" = "+weights[i]+"/"+max+"^0.3 = "+Math.pow(weights[i]/max, 0.3333));
            }
        }
    }
}