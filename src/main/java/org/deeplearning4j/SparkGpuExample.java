package org.deeplearning4j;

import org.apache.commons.io.FileUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.deeplearning4j.datasets.iterator.impl.MnistDataSetIterator;
import org.deeplearning4j.eval.Evaluation;import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.OutputPreProcessor;
import org.deeplearning4j.nn.conf.distribution.NormalDistribution;
import org.deeplearning4j.nn.conf.layers.RBM;
import org.deeplearning4j.nn.conf.override.ClassifierOverride;
import org.deeplearning4j.nn.conf.preprocessor.BinomialSamplingPreProcessor;
import org.deeplearning4j.nn.layers.factory.LayerFactories;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.api.IterationListener;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.spark.impl.multilayer.SparkDl4jMultiLayer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.jcublas.context.ContextHolder;
import org.nd4j.linalg.jcublas.kernel.KernelFunctionLoader;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.io.File;
import java.util.*;

/**
 * @author sonali
 */
public class SparkGpuExample {

    public static void main(String[] args) throws Exception {
        Nd4j.MAX_ELEMENTS_PER_SLICE = Integer.MAX_VALUE;
        Nd4j.MAX_SLICES_TO_PRINT = Integer.MAX_VALUE;
        System.out.println("Running on " + ContextHolder.getInstance().deviceNum() + " devices");
        // set to test mode
        SparkConf sparkConf = new SparkConf().set("spark.executor.memory","5g").set("spark.driver.memory","5g")
                .setMaster("local[8]").set(SparkDl4jMultiLayer.AVERAGE_EACH_ITERATION,"false")
                .set("spark.akka.frameSize", "1000")
                .setAppName("mnist");

        System.out.println("Setting up Spark Context...");

        JavaSparkContext sc = new JavaSparkContext(sparkConf);


        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .momentum(0.9).iterations(10)
                .weightInit(WeightInit.DISTRIBUTION).batchSize(1000)
                .dist(new NormalDistribution(0, 1)).lossFunction(LossFunctions.LossFunction.RMSE_XENT)
                .nIn(784).nOut(10).layer(new RBM())
                .list(4).hiddenLayerSizes(600, 500, 400)
                .override(3, new ClassifierOverride(3)).build();




        MultiLayerNetwork network = new MultiLayerNetwork(conf);
        network.init();
        network.setListeners(Arrays.asList(
                (IterationListener) new ScoreIterationListener(1)
        ));
        System.out.println("Initializing network");
        SparkDl4jMultiLayer master = new SparkDl4jMultiLayer(sc,conf);
        DataSet d = new MnistDataSetIterator(1000,60000).next();
        List<DataSet> next = new ArrayList<>();
        for(int i = 0; i < d.numExamples(); i++)
            next.add(d.get(i).copy());

        JavaRDD<DataSet> data = sc.parallelize(next);
        MultiLayerNetwork network2 = master.fitDataSet(data);

        Evaluation evaluation = new Evaluation();
        evaluation.eval(d.getLabels(),network2.output(d.getFeatureMatrix()));
        System.out.println("Averaged once " + evaluation.stats());


        INDArray params = network2.params();
        Nd4j.writeTxt(params,"params.txt",",");
        FileUtils.writeStringToFile(new File("conf.json"), network2.getLayerWiseConfigurations().toJson());
    }
}
