package hex.tree.xgboost;

import hex.ModelMetricsBinomial;
import hex.ModelMetricsMultinomial;
import hex.ModelMetricsRegression;
import hex.SplitFrame;
import hex.genmodel.MojoModel;
import hex.genmodel.MojoReaderBackend;
import hex.genmodel.MojoReaderBackendFactory;
import hex.genmodel.algos.xgboost.XGBoostMojoModel;
import hex.genmodel.algos.xgboost.XGBoostMojoReader;
import hex.genmodel.algos.xgboost.XGBoostNativeMojoModel;
import hex.genmodel.utils.DistributionFamily;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;
import ml.dmlc.xgboost4j.java.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import water.*;
import water.exceptions.H2OModelBuilderIllegalArgumentException;
import water.fvec.Frame;
import water.fvec.TestFrameBuilder;
import water.fvec.Vec;
import water.util.Log;

import java.io.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static water.util.FileUtils.locateFile;

@RunWith(Parameterized.class)
public class XGBoostTest extends TestUtil {

  @Parameterized.Parameters(name = "XGBoost(javaMojoScoring={0}")
  public static Iterable<? extends Object> data() {
    return Arrays.asList("true", "false");
  }

  @Parameterized.Parameter
  public String confJavaScoring;

  @Before
  public void setupMojoJavaScoring() {
    System.setProperty("sys.ai.h2o.xgboost.scoring.java.enable", confJavaScoring);
  }

  public static final class FrameMetadata {
    Vec[] vecs;
    String[] names;
    long[] checksums;
    String[][] domains;

    public FrameMetadata(Frame f) {
      vecs = f.vecs();
      names = f.names();

      checksums = new long[vecs.length];
      for (int i = 0; i < vecs.length; i++)
        checksums[i] = vecs[i].checksum();

      domains = new String[vecs.length][];
      for (int i = 0; i < vecs.length; i++)
        domains[i] = vecs[i].domain();
    }

    @Override
    public boolean equals(Object o) {
      if (! (o instanceof FrameMetadata))
        return false;

      FrameMetadata fm = (FrameMetadata)o;

      boolean error = false;

      if (vecs.length != fm.vecs.length) {
        Log.warn("Training frame vec count has changed from: " +
                vecs.length + " to: " + fm.vecs.length);
        error = true;
      }
      if (names.length != fm.names.length) {
        Log.warn("Training frame vec count has changed from: " +
                names.length + " to: " + fm.names.length);
        error = true;
      }

      for (int i = 0; i < fm.vecs.length; i++) {
        if (!fm.vecs[i].equals(fm.vecs[i])) {
          Log.warn("Training frame vec number " + i + " has changed keys.  Was: " +
                  vecs[i] + " , now: " + fm.vecs[i]);
          error = true;
        }
        if (!fm.names[i].equals(fm.names[i])) {
          Log.warn("Training frame vec number " + i + " has changed names.  Was: " +
                  names[i] + " , now: " + fm.names[i]);
          error = true;
        }
        if (checksums[i] != fm.vecs[i].checksum()) {
          Log.warn("Training frame vec number " + i + " has changed checksum.  Was: " +
                  checksums[i] + " , now: " + fm.vecs[i].checksum());
          error = true;
        }
        if (domains[i] != null && ! Arrays.equals(domains[i], fm.vecs[i].domain())) {
          Log.warn("Training frame vec number " + i + " has changed domain.  Was: " +
                  domains[i] + " , now: " + fm.vecs[i].domain());
          error = true;
        }
      }

      return !error;
    }
  }

  @BeforeClass public static void stall() {
    stall_till_cloudsize(1);

    // we need to check for XGBoost backend availability after H2O is initialized, since we
    // XGBoost is a core extension and they are registered as part of the H2O's class main method
    Assume.assumeTrue("XGBoost was not loaded!\n"
                    + "H2O XGBoost needs binary compatible environment;"
                    + "Make sure that you have correct libraries installed"
                    + "and correctly configured LD_LIBRARY_PATH, especially"
                    + "make sure that CUDA libraries are available if you are running on GPU!",
            ExtensionManager.getInstance().isCoreExtensionsEnabled(XGBoostExtension.NAME));
  }


  static DMatrix[] getMatrices() throws XGBoostError {
    // load file from text file, also binary buffer generated by xgboost4j
    return new DMatrix[]{
            new DMatrix(locateFile("smalldata/xgboost/demo/data/agaricus.txt.train").getAbsolutePath()),
            new DMatrix(locateFile("smalldata/xgboost/demo/data/agaricus.txt.test").getAbsolutePath())
    };
  }
  static void saveDumpModel(File modelFile, String[] modelInfos) throws IOException {
    try{
      PrintWriter writer = new PrintWriter(modelFile, "UTF-8");
      for(int i = 0; i < modelInfos.length; ++ i) {
        writer.print("booster[" + i + "]:\n");
        writer.print(modelInfos[i]);
      }
      writer.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  static boolean checkPredicts(float[][] fPredicts, float[][] sPredicts) {
    if (fPredicts.length != sPredicts.length) {
      return false;
    }

    for (int i = 0; i < fPredicts.length; i++) {
      if (!Arrays.equals(fPredicts[i], sPredicts[i])) {
        return false;
      }
    }

    return true;
  }

  @Test
  public void testMatrices() throws XGBoostError {
    Map<String, String> rabitEnv = new HashMap<>();
    rabitEnv.put("DMLC_TASK_ID", "0");
    Rabit.init(rabitEnv);
    getMatrices();
    Rabit.shutdown();
  }

  @Test public void BasicModel() throws XGBoostError {
    Map<String, String> rabitEnv = new HashMap<>();
    rabitEnv.put("DMLC_TASK_ID", "0");
    Rabit.init(rabitEnv);
    // load file from text file, also binary buffer generated by xgboost4j
    DMatrix[] mat = getMatrices();
    DMatrix trainMat = mat[0];
    DMatrix testMat = mat[1];

    HashMap<String, Object> params = new HashMap<>();
    params.put("eta", 0.1);
    params.put("max_depth", 5);
    params.put("silent", 1);
    params.put("objective", "binary:logistic");

    HashMap<String, DMatrix> watches = new HashMap<>();
    watches.put("train", trainMat);
    watches.put("test",  testMat);

    Booster booster = XGBoost.train(trainMat, params, 10, watches, null, null);
    float[][] preds = booster.predict(testMat);
    for (int i=0;i<10;++i)
      Log.info(preds[i][0]);
    Rabit.shutdown();
  }

  @Test public void testScoring() throws XGBoostError {
    Map<String, String> rabitEnv = new HashMap<>();
    rabitEnv.put("DMLC_TASK_ID", "0");
    Rabit.init(rabitEnv);
    // load file from text file, also binary buffer generated by xgboost4j
    DMatrix[] mat = getMatrices();
    DMatrix trainMat = mat[0];
    DMatrix testMat = mat[1];

    HashMap<String, Object> params = new HashMap<>();
    params.put("eta", 0.1);
    params.put("max_depth", 5);
    params.put("silent", 1);
    params.put("objective", "reg:linear");

    HashMap<String, DMatrix> watches = new HashMap<>();
    watches.put("train", trainMat);
    watches.put("test",  testMat);

    Booster booster = XGBoost.train(trainMat, params, 10, watches, null, null);
    // slice some rows out and predict on those
    float[][] preds1 = booster.predict(trainMat.slice(new int[]{0}));
    float[][] preds2 = booster.predict(trainMat.slice(new int[]{1}));
    float[][] preds3 = booster.predict(trainMat.slice(new int[]{2}));
    float[][] preds4 = booster.predict(trainMat.slice(new int[]{0,1,2}));

    Assert.assertTrue(preds1.length==1);
    Assert.assertTrue(preds2.length==1);
    Assert.assertTrue(preds3.length==1);
    Assert.assertTrue(preds4.length==3);

    Assert.assertTrue(preds4[0][0]==preds1[0][0]);
    Assert.assertTrue(preds4[1][0]==preds2[0][0]);
    Assert.assertTrue(preds4[2][0]==preds3[0][0]);
    Assert.assertTrue(preds4[0][0]!=preds4[1][0]);
    Assert.assertTrue(preds4[0][0]!=preds4[2][0]);
    Rabit.shutdown();
  }

  @Test public void testScore0() throws XGBoostError {
    Map<String, String> rabitEnv = new HashMap<>();
    rabitEnv.put("DMLC_TASK_ID", "0");
    Rabit.init(rabitEnv);
    // trivial dataset with 3 rows and 2 columns
    // (4,5) -> 1
    // (3,1) -> 2
    // (2,3) -> 3
    DMatrix trainMat = new DMatrix(new float[]{4f,5f, 3f,1f, 2f,3f},3,2);
    trainMat.setLabel(new float[]{             1f,    2f,    3f       });

    HashMap<String, Object> params = new HashMap<>();
    params.put("eta", 1);
    params.put("max_depth", 5);
    params.put("silent", 1);
    params.put("objective", "reg:linear");

    HashMap<String, DMatrix> watches = new HashMap<>();
    watches.put("train", trainMat);

    Booster booster = XGBoost.train(trainMat, params, 10, watches, null, null);

    // check overfitting
    // (4,5) -> 1
    // (3,1) -> 2
    // (2,3) -> 3
    float[][] preds1 = booster.predict(new DMatrix(new float[]{4f,5f},1,2));
    float[][] preds2 = booster.predict(new DMatrix(new float[]{3f,1f},1,2));
    float[][] preds3 = booster.predict(new DMatrix(new float[]{2f,3f},1,2));

    Assert.assertTrue(preds1.length==1);
    Assert.assertTrue(preds2.length==1);
    Assert.assertTrue(preds3.length==1);

    Assert.assertTrue(Math.abs(preds1[0][0]-1) < 1e-2);
    Assert.assertTrue(Math.abs(preds2[0][0]-2) < 1e-2);
    Assert.assertTrue(Math.abs(preds3[0][0]-3) < 1e-2);
    Rabit.shutdown();
  }

  @Test
  public void saveLoadDataAndModel() throws XGBoostError, IOException {
    Map<String, String> rabitEnv = new HashMap<>();
    rabitEnv.put("DMLC_TASK_ID", "0");
    Rabit.init(rabitEnv);
    // load file from text file, also binary buffer generated by xgboost4j
    DMatrix[] mat = getMatrices();
    DMatrix trainMat = mat[0];
    DMatrix testMat = mat[1];

    HashMap<String, Object> params = new HashMap<>();
    params.put("eta", 0.1);
    params.put("max_depth", 5);
    params.put("silent", 1);
    params.put("objective", "binary:logistic");

    HashMap<String, DMatrix> watches = new HashMap<>();
    watches.put("train", trainMat);
    watches.put("test",  testMat);

    Booster booster = XGBoost.train(trainMat, params, 10, watches, null, null);

    float[][] predicts = booster.predict(testMat);

    //save model to modelPath
    File modelDir = java.nio.file.Files.createTempDirectory("xgboost-model").toFile();

    booster.saveModel(path(modelDir, "xgb.model"));

    //dump model with feature map
    String[] modelInfos = booster.getModelDump(locateFile("smalldata/xgboost/demo/data/featmap.txt").getAbsolutePath(), false);
    saveDumpModel(new File(modelDir, "dump.raw.txt"), modelInfos);

    //save dmatrix into binary buffer
    testMat.saveBinary(path(modelDir, "dtest.buffer"));

    //reload model and data
    Booster booster2 = XGBoost.loadModel(path(modelDir, "xgb.model"));
    DMatrix testMat2 = new DMatrix(path(modelDir, "dtest.buffer"));
    float[][] predicts2 = booster2.predict(testMat2);

    //check the two predicts
    System.out.println(checkPredicts(predicts, predicts2));

    //specify watchList
    HashMap<String, DMatrix> watches2 = new HashMap<>();
    watches2.put("train", trainMat);
    watches2.put("test", testMat2);
    Booster booster3 = XGBoost.train(trainMat, params, 10, watches2, null, null);
    float[][] predicts3 = booster3.predict(testMat2);

    //check predicts
    System.out.println(checkPredicts(predicts, predicts3));
    Rabit.shutdown();
  }

  private static String path(File parentDir, String fileName) {
    return new File(parentDir, fileName).getAbsolutePath();
  }

  @Test
  public void checkpoint() throws XGBoostError, IOException {
    Map<String, String> rabitEnv = new HashMap<>();
    rabitEnv.put("DMLC_TASK_ID", "0");
    Rabit.init(rabitEnv);
    // load file from text file, also binary buffer generated by xgboost4j
    DMatrix[] mat = getMatrices();
    DMatrix trainMat = mat[0];
    DMatrix testMat = mat[1];

    HashMap<String, Object> params = new HashMap<>();
    params.put("eta", 0.1);
    params.put("max_depth", 5);
    params.put("silent", 1);
    params.put("objective", "binary:logistic");

    HashMap<String, DMatrix> watches = new HashMap<>();
    watches.put("train", trainMat);

    Booster booster = XGBoost.train(trainMat, params, 0, watches, null, null);
    // Train for 10 iterations
    for (int i=0;i<10;++i) {
      booster.update(trainMat, i);
      float[][] preds = booster.predict(testMat);
      for (int j = 0; j < 10; ++j)
        Log.info(preds[j][0]);
    }
    Rabit.shutdown();
  }

  @Test
  public void WeatherBinary() {
    Frame tfr = null;
    Frame trainFrame = null;
    Frame testFrame = null;
    Frame preds = null;
    XGBoostModel model = null;
    Scope.enter();
    try {
      // Parse frame into H2O
      tfr = parse_test_file("./smalldata/junit/weather.csv");
      // define special columns
      String response = "RainTomorrow";
//      String weight = null;
//      String fold = null;
      Scope.track(tfr.replace(tfr.find(response), tfr.vecs()[tfr.find(response)].toCategoricalVec()));
      // remove columns correlated with the response
      tfr.remove("RISK_MM").remove();
      tfr.remove("EvapMM").remove();
      FrameMetadata metadataBefore = new FrameMetadata(tfr);  // make sure it's after removing those columns!
      DKV.put(tfr);

      // split into train/test
      SplitFrame sf = new SplitFrame(tfr, new double[] { 0.7, 0.3 }, null);
      sf.exec().get();
      Key[] ksplits = sf._destination_frames;
      trainFrame = (Frame)ksplits[0].get();
      testFrame = (Frame)ksplits[1].get();

      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      parms._ntrees = 5;
      parms._max_depth = 5;
      parms._train = trainFrame._key;
      parms._valid = testFrame._key;
      parms._response_column = response;

      model = new hex.tree.xgboost.XGBoost(parms).trainModel().get();
      Log.info(model);

      FrameMetadata metadataAfter = new FrameMetadata(tfr);
      Assert.assertEquals(metadataBefore, metadataAfter);

      preds = model.score(testFrame);
      Assert.assertTrue(model.testJavaScoring(testFrame, preds, 1e-6));
      Assert.assertEquals(
              ((ModelMetricsBinomial)model._output._validation_metrics).auc(),
              ModelMetricsBinomial.make(preds.vec(2), testFrame.vec(response)).auc(),
              1e-5
      );
      Assert.assertTrue(preds.anyVec().sigma() > 0);

    } finally {
      Scope.exit();
      if (trainFrame!=null) trainFrame.remove();
      if (testFrame!=null) testFrame.remove();
      if (tfr!=null) tfr.remove();
      if (preds!=null) preds.remove();
      if (model!=null) model.delete();
    }
  }

  @Test
  public void WeatherBinaryCV() {
    Frame tfr = null;
    Frame trainFrame = null;
    Frame testFrame = null;
    Frame preds = null;
    XGBoostModel model = null;
    try {
      Scope.enter();
      // Parse frame into H2O
      tfr = parse_test_file("./smalldata/junit/weather.csv");
      // define special columns
      String response = "RainTomorrow";
//      String weight = null;
//      String fold = null;
      Scope.track(tfr.replace(tfr.find(response), tfr.vecs()[tfr.find(response)].toCategoricalVec()));
      // remove columns correlated with the response
      tfr.remove("RISK_MM").remove();
      tfr.remove("EvapMM").remove();
      FrameMetadata metadataBefore = new FrameMetadata(tfr);  // make sure it's after removing those columns!
      DKV.put(tfr);

      // split into train/test
      SplitFrame sf = new SplitFrame(tfr, new double[] { 0.7, 0.3 }, null);
      sf.exec().get();
      Key[] ksplits = sf._destination_frames;
      trainFrame = (Frame)ksplits[0].get();
      testFrame = (Frame)ksplits[1].get();


      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      parms._ntrees = 5;
      parms._max_depth = 5;
      parms._train = trainFrame._key;
      parms._valid = testFrame._key;
      parms._nfolds = 5;
      parms._response_column = response;

      model = new hex.tree.xgboost.XGBoost(parms).trainModel().get();
      Log.info(model);

      FrameMetadata metadataAfter = new FrameMetadata(tfr);
      Assert.assertEquals(metadataBefore, metadataAfter);

      preds = model.score(testFrame);
      Assert.assertTrue(model.testJavaScoring(testFrame, preds, 1e-6));
      Assert.assertEquals(
              ((ModelMetricsBinomial)model._output._validation_metrics).auc(),
              ModelMetricsBinomial.make(preds.vec(2), testFrame.vec(response)).auc(),
              1e-5
      );
      Assert.assertTrue(preds.anyVec().sigma() > 0);

    } finally {
      Scope.exit();
      if (trainFrame!=null) trainFrame.remove();
      if (testFrame!=null) testFrame.remove();
      if (tfr!=null) tfr.remove();
      if (preds!=null) preds.remove();
      if (model!=null) {
        model.deleteCrossValidationModels();
        model.delete();
      }
    }
  }

  @Test(expected = H2OModelBuilderIllegalArgumentException.class)
  public void RegressionCars() {
    Frame tfr = null;
    Frame trainFrame = null;
    Frame testFrame = null;
    Frame preds = null;
    XGBoostModel model = null;
    Scope.enter();
    try {
      // Parse frame into H2O
      tfr = parse_test_file("./smalldata/junit/cars.csv");
      FrameMetadata metadataBefore = new FrameMetadata(tfr);
      DKV.put(tfr);

      Scope.track(tfr.replace(1, tfr.vecs()[1].toCategoricalVec()));   // Convert CAPSULE to categorical
      Scope.track(tfr.replace(3, tfr.vecs()[3].toCategoricalVec()));   // Convert RACE to categorical
      DKV.put(tfr);

      // split into train/test
      SplitFrame sf = new SplitFrame(tfr, new double[] { 0.7, 0.3 }, null);
      sf.exec().get();
      Key[] ksplits = sf._destination_frames;
      trainFrame = (Frame)ksplits[0].get();
      testFrame = (Frame)ksplits[1].get();

      // define special columns
//      String response = "cylinders"; // passes
      String response = "economy (mpg)"; //Expected to fail - contains NAs

      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      parms._train = trainFrame._key;
      parms._valid = testFrame._key;
      parms._response_column = response;
      parms._ignored_columns = new String[]{"name"};

      model = new hex.tree.xgboost.XGBoost(parms).trainModel().get();
      Log.info(model);

      FrameMetadata metadataAfter = new FrameMetadata(tfr);
      Assert.assertEquals(metadataBefore, metadataAfter);

      preds = model.score(testFrame);
      Assert.assertTrue(model.testJavaScoring(testFrame, preds, 1e-6));
      Assert.assertEquals(
              ((ModelMetricsRegression)model._output._validation_metrics).mae(),
              ModelMetricsRegression.make(preds.anyVec(), testFrame.vec(response), DistributionFamily.gaussian).mae(),
              1e-5
      );
      Assert.assertTrue(preds.anyVec().sigma() > 0);

    } finally {
      Scope.exit();
      if (trainFrame!=null) trainFrame.remove();
      if (testFrame!=null) testFrame.remove();
      if (tfr!=null) tfr.remove();
      if (preds!=null) preds.remove();
      if (model!=null) {
        model.delete();
      }
    }
  }

  @Test
  public void ProstateRegression() {
    Frame tfr = null;
    Frame trainFrame = null;
    Frame testFrame = null;
    Frame preds = null;
    XGBoostModel model = null;
    Scope.enter();
    try {
      // Parse frame into H2O
      tfr = parse_test_file("./smalldata/prostate/prostate.csv");
      FrameMetadata metadataBefore = new FrameMetadata(tfr);

      Scope.track(tfr.replace(1, tfr.vecs()[1].toCategoricalVec()));   // Convert CAPSULE to categorical
      Scope.track(tfr.replace(3, tfr.vecs()[3].toCategoricalVec()));   // Convert RACE to categorical
      DKV.put(tfr);

      // split into train/test
      SplitFrame sf = new SplitFrame(tfr, new double[] { 0.7, 0.3 }, null);
      sf.exec().get();
      Key[] ksplits = sf._destination_frames;
      trainFrame = (Frame)ksplits[0].get();
      testFrame = (Frame)ksplits[1].get();

      // define special columns
      String response = "AGE";
//      String weight = null;
//      String fold = null;

      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      parms._train = trainFrame._key;
      parms._valid = testFrame._key;
      parms._response_column = response;
      parms._ignored_columns = new String[]{"ID"};

      model = new hex.tree.xgboost.XGBoost(parms).trainModel().get();
      Log.info(model);

      FrameMetadata metadataAfter = new FrameMetadata(tfr);
      Assert.assertEquals(metadataBefore, metadataAfter);

      preds = model.score(testFrame);
      Assert.assertTrue(model.testJavaScoring(testFrame, preds, 1e-6));
      Assert.assertEquals(
              ((ModelMetricsRegression)model._output._validation_metrics).mae(),
              ModelMetricsRegression.make(preds.anyVec(), testFrame.vec(response), DistributionFamily.gaussian).mae(),
              1e-5
      );
      Assert.assertTrue(preds.anyVec().sigma() > 0);
    } finally {
      Scope.exit();
      if (trainFrame!=null) trainFrame.remove();
      if (testFrame!=null) testFrame.remove();
      if (tfr!=null) tfr.remove();
      if (preds!=null) preds.remove();
      if (model!=null) {
        model.delete();
      }
    }
  }

  @Test
  public void sparseMatrixDetectionTest() {
    Frame tfr = null;
    XGBoostModel model = null;
    Scope.enter();
    try {
      tfr = parse_test_file("./smalldata/prostate/prostate.csv");
      Scope.track(tfr.replace(8, tfr.vecs()[8].toCategoricalVec()));   // Convert GLEASON to categorical
      DKV.put(tfr);

      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      // Automatic detection should compute sparsity and decide
      parms._dmatrix_type = XGBoostModel.XGBoostParameters.DMatrixType.auto;
      parms._response_column = "AGE";
      parms._train = tfr._key;
      parms._ignored_columns = new String[]{"ID","DPROS", "DCAPS", "PSA", "VOL", "RACE", "CAPSULE"};

      model = new hex.tree.xgboost.XGBoost(parms).trainModel().get();
      assertTrue(model._output._sparse);

    } finally {
      Scope.exit();
      if (tfr!=null) tfr.remove();
      if (model!=null) {
        model.delete();
        model.deleteCrossValidationModels();
      }
    }

  }

  @Test
  public void denseMatrixDetectionTest() {
    Frame tfr = null;
    XGBoostModel model = null;
    Scope.enter();
    try {
      tfr = parse_test_file("./smalldata/prostate/prostate.csv");
      DKV.put(tfr);

      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      // Automatic detection should compute sparsity and decide
      parms._dmatrix_type = XGBoostModel.XGBoostParameters.DMatrixType.auto;
      parms._response_column = "AGE";
      parms._train = tfr._key;
      parms._ignored_columns = new String[]{"ID","DPROS", "DCAPS", "PSA", "VOL", "RACE", "CAPSULE"};

      // GLEASON used as predictor variable, numeric variable, dense
      model = new hex.tree.xgboost.XGBoost(parms).trainModel().get();
      assertFalse(model._output._sparse);

    } finally {
      Scope.exit();
      if (tfr!=null) tfr.remove();
      if (model!=null) {
        model.delete();
        model.deleteCrossValidationModels();
      }
    }

  }

  @Test
  public void ProstateRegressionCV() {
    for (XGBoostModel.XGBoostParameters.DMatrixType dMatrixType : XGBoostModel.XGBoostParameters.DMatrixType.values()) {
      Frame tfr = null;
      Frame trainFrame = null;
      Frame testFrame = null;
      Frame preds = null;
      XGBoostModel model = null;
      try {
        // Parse frame into H2O
        tfr = parse_test_file("./smalldata/prostate/prostate.csv");
        FrameMetadata metadataBefore = new FrameMetadata(tfr);

        // split into train/test
        SplitFrame sf = new SplitFrame(tfr, new double[] { 0.7, 0.3 }, null);
        sf.exec().get();
        Key[] ksplits = sf._destination_frames;
        trainFrame = (Frame)ksplits[0].get();
        testFrame = (Frame)ksplits[1].get();

        // define special columns
        String response = "AGE";
//      String weight = null;
//      String fold = null;

        XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
        parms._dmatrix_type = dMatrixType;
        parms._nfolds = 2;
        parms._train = trainFrame._key;
        parms._valid = testFrame._key;
        parms._response_column = response;
        parms._ignored_columns = new String[]{"ID"};

        model = new hex.tree.xgboost.XGBoost(parms).trainModel().get();
        Log.info(model);

        FrameMetadata metadataAfter = new FrameMetadata(tfr);
        Assert.assertEquals(metadataBefore, metadataAfter);

        preds = model.score(testFrame);
        Assert.assertTrue(model.testJavaScoring(testFrame, preds, 1e-6));
        Assert.assertTrue(preds.anyVec().sigma() > 0);

      } finally {
        if (trainFrame!=null) trainFrame.remove();
        if (testFrame!=null) testFrame.remove();
        if (tfr!=null) tfr.remove();
        if (preds!=null) preds.remove();
        if (model!=null) {
          model.delete();
          model.deleteCrossValidationModels();
        }
      }
    }
  }

  @Test
  public void MNIST() {
    Frame tfr = null;
    Frame preds = null;
    XGBoostModel model = null;
    Scope.enter();
    try {
      // Parse frame into H2O
      tfr = parse_test_file("bigdata/laptop/mnist/train.csv.gz");
      FrameMetadata metadataBefore = new FrameMetadata(tfr);
      Scope.track(tfr.replace(784, tfr.vecs()[784].toCategoricalVec()));   // Convert response 'C785' to categorical
      DKV.put(tfr);

      // define special columns
      String response = "C785";

      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      parms._ntrees = 3;
      parms._max_depth = 3;
      parms._train = tfr._key;
      parms._response_column = response;

      model = new hex.tree.xgboost.XGBoost(parms).trainModel().get();
      Log.info(model);

      FrameMetadata metadataAfter = new FrameMetadata(tfr);
      Assert.assertEquals(metadataBefore, metadataAfter);

      preds = model.score(tfr);
      Assert.assertTrue(model.testJavaScoring(tfr, preds, 1e-6));
      preds.remove(0).remove();
      Assert.assertTrue(preds.anyVec().sigma() > 0);
      Assert.assertEquals(
              ((ModelMetricsMultinomial)model._output._training_metrics).logloss(),
              ModelMetricsMultinomial.make(preds, tfr.vec(response), tfr.vec(response).domain()).logloss(),
              1e-5
      );
    } finally {
      if (tfr!=null) tfr.remove();
      if (preds!=null) preds.remove();
      if (model!=null) model.delete();
      Scope.exit();
    }
  }

  @Test
  public void testGPUIncompatParams() {
    XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
    parms._backend = XGBoostModel.XGBoostParameters.Backend.gpu;
    parms._grow_policy = XGBoostModel.XGBoostParameters.GrowPolicy.lossguide;
    Map<String, Object> expectedIncompats = Collections.singletonMap("grow_policy", (Object) XGBoostModel.XGBoostParameters.GrowPolicy.lossguide);
    Assert.assertEquals(expectedIncompats, parms.gpuIncompatibleParams());
  }

  @Test
  public void testGPUIncompats() {
    Scope.enter();
    try {
      Frame tfr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA", "ColB")
              .withVecTypes(Vec.T_NUM, Vec.T_CAT)
              .withDataForCol(0, ard(Double.NaN, 1, 2, 3, 4, 5.6, 7))
              .withDataForCol(1, ar("A", "B,", "A", "C", "A", "B", "A"))
              .build();
      Scope.track(tfr);

      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      parms._ntrees = 3;
      parms._max_depth = 3;
      parms._train = tfr._key;
      parms._response_column = "ColB";

      // Force GPU backend
      parms._backend = XGBoostModel.XGBoostParameters.Backend.gpu;

      // Set GPU incompatible parameter 'grow_policy = lossguide'
      parms._grow_policy = XGBoostModel.XGBoostParameters.GrowPolicy.lossguide;
      parms._tree_method = XGBoostModel.XGBoostParameters.TreeMethod.hist; // Needed by lossguide

      try {
        XGBoostModel model = new hex.tree.xgboost.XGBoost(parms).trainModel().get();
        Scope.track_generic(model);
        Assert.fail("Thes parameter settings are not suppose to work!");
      } catch (H2OModelBuilderIllegalArgumentException e) {
        String expected = "ERRR on field: _backend: GPU backend is not available for parameter setting 'grow_policy = lossguide'. Use CPU backend instead.\n";
        Assert.assertTrue(e.getMessage().endsWith(expected));
      }
    } finally {
      Scope.exit();
    }
  }

  @Test
  public void MNIST_LightGBM() {
    Frame tfr = null;
    Frame preds = null;
    XGBoostModel model = null;
    Scope.enter();
    try {
      // Parse frame into H2O
      tfr = parse_test_file("bigdata/laptop/mnist/train.csv.gz");
      FrameMetadata metadataBefore = new FrameMetadata(tfr);
      Scope.track(tfr.replace(784, tfr.vecs()[784].toCategoricalVec()));   // Convert response 'C785' to categorical
      DKV.put(tfr);

      // define special columns
      String response = "C785";

      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      parms._ntrees = 3;
      parms._max_depth = 3;
      parms._train = tfr._key;
      parms._response_column = response;

      // emulate LightGBM
      parms._tree_method = XGBoostModel.XGBoostParameters.TreeMethod.hist;
      parms._grow_policy = XGBoostModel.XGBoostParameters.GrowPolicy.lossguide;

      model = new hex.tree.xgboost.XGBoost(parms).trainModel().get();
      Log.info(model);

      FrameMetadata metadataAfter = new FrameMetadata(tfr);
      Assert.assertEquals(metadataBefore, metadataAfter);

      preds = model.score(tfr);
      Assert.assertTrue(model.testJavaScoring(tfr, preds, 1e-6));
      preds.remove(0).remove();
      Assert.assertTrue(preds.anyVec().sigma() > 0);
      Assert.assertEquals(
              ((ModelMetricsMultinomial)model._output._training_metrics).logloss(),
              ModelMetricsMultinomial.make(preds, tfr.vec(response), tfr.vec(response).domain()).logloss(),
              1e-5
      );
    } finally {
      if (tfr!=null) tfr.remove();
      if (preds!=null) preds.remove();
      if (model!=null) model.delete();
      Scope.exit();
    }
  }

  @Ignore
  @Test
  public void testCSC() {
    Frame tfr = null;
    Frame preds = null;
    XGBoostModel model = null;
    Scope.enter();
    try {
      // Parse frame into H2O
      tfr = parse_test_file("csc.csv");
      FrameMetadata metadataBefore = new FrameMetadata(tfr);
      String response = "response";

      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      parms._ntrees = 3;
      parms._max_depth = 3;
      parms._train = tfr._key;
      parms._response_column = response;

      model = new hex.tree.xgboost.XGBoost(parms).trainModel().get();
      Log.info(model);

      FrameMetadata metadataAfter = new FrameMetadata(tfr);
      Assert.assertEquals(metadataBefore, metadataAfter);

      preds = model.score(tfr);
      Assert.assertTrue(model.testJavaScoring(tfr, preds, 1e-6));
      Assert.assertTrue(preds.vec(2).sigma() > 0);
      Assert.assertEquals(
              ((ModelMetricsBinomial)model._output._training_metrics).logloss(),
              ModelMetricsBinomial.make(preds.vec(2), tfr.vec(response), tfr.vec(response).domain()).logloss(),
              1e-5
      );
    } finally {
      if (tfr!=null) tfr.remove();
      if (preds!=null) preds.remove();
      if (model!=null) model.delete();
      Scope.exit();
    }
  }

  @Test
  public void testModelMetrics() {
      Frame tfr = null, trainFrame = null, testFrame = null, validFrame = null;
      XGBoostModel model = null;
      try {
        // Parse frame into H2O
        tfr = parse_test_file("./smalldata/prostate/prostate.csv");
        FrameMetadata metadataBefore = new FrameMetadata(tfr);

        // split into train/test
        SplitFrame sf = new SplitFrame(tfr, new double[] { 0.6, 0.2, 0.2 }, null);
        sf.exec().get();

        trainFrame = sf._destination_frames[0].get();
        testFrame = sf._destination_frames[1].get();
        validFrame = sf._destination_frames[2].get();
        String response = "AGE";

        XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
        parms._ntrees = 2;
        parms._train = trainFrame._key;
        parms._valid = testFrame._key;
        parms._response_column = response;
        parms._ignored_columns = new String[]{"ID"};

        model = new hex.tree.xgboost.XGBoost(parms).trainModel().get();
        Assert.assertNotNull("Train metrics are not null", model._output._training_metrics);
        Assert.assertNotNull("Validation metrics are not null", model._output._validation_metrics);
        Assert.assertEquals("Initial model output metrics contains 2 model metrics",
                            2, model._output.getModelMetrics().length);
        for(String name : model._output._names){
          Assert.assertNotEquals(parms._ignored_columns[0], name);
        }

        model.score(testFrame).remove();
        Assert.assertEquals("After scoring on test data, model output metrics contains 2 model metrics",
                            2, model._output.getModelMetrics().length);

        model.score(validFrame).remove();
        Assert.assertEquals("After scoring on unseen data, model output metrics contains 3 model metrics",
                            3, model._output.getModelMetrics().length);


        FrameMetadata metadataAfter = new FrameMetadata(tfr);
        Assert.assertEquals(metadataBefore, metadataAfter);

      } finally {
        if (trainFrame!=null) trainFrame.remove();
        if (testFrame!=null) testFrame.remove();
        if (validFrame!=null) validFrame.remove();
        if (tfr!=null) tfr.remove();
        if (model!=null) {
          model.delete();
        }
      }
  }

  @Test
  public void testCrossValidation() {
    Scope.enter();
    XGBoostModel denseModel = null;
    XGBoostModel sparseModel = null;
    try {
      Frame tfr = Scope.track(parse_test_file("./smalldata/prostate/prostate.csv"));

      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      parms._train = tfr._key;
      parms._response_column = "AGE";
      parms._ignored_columns = new String[]{"ID"};
      parms._seed = 42;
      parms._ntrees = 5;
      parms._weights_column = "CAPSULE";
      parms._dmatrix_type = XGBoostModel.XGBoostParameters.DMatrixType.dense;

      // Dense model utilizes fold column zero values to calculate precise memory requirements
      denseModel = (XGBoostModel) Scope.track_generic(new hex.tree.xgboost.XGBoost(parms).trainModel().get());
      assertNotNull(denseModel);

      parms._dmatrix_type = XGBoostModel.XGBoostParameters.DMatrixType.sparse;
      sparseModel = (XGBoostModel) Scope.track_generic(new hex.tree.xgboost.XGBoost(parms).trainModel().get());
      assertNotNull(sparseModel);


      Log.info(denseModel);
    } finally {
      if(denseModel != null) denseModel.deleteCrossValidationModels();
      if(sparseModel != null) sparseModel.deleteCrossValidationModels();
      Scope.exit();
    }
  }

  @Test
  public void testSparsityDetection(){
    Scope.enter();
    XGBoostModel sparseModel = null;
    XGBoostModel denseModel = null;
    try {
      Frame sparseFrame = Scope.track(TestUtil.generate_enum_only(2, 10, 10, 0));
      Frame denseFrame = Scope.track(TestUtil.generate_enum_only(2, 10, 2, 0));

      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      parms._train = sparseFrame._key;
      parms._response_column = "C1";
      parms._seed = 42;
      parms._ntrees = 1;
      parms._dmatrix_type = XGBoostModel.XGBoostParameters.DMatrixType.auto;

      sparseModel = (XGBoostModel) Scope.track_generic(new hex.tree.xgboost.XGBoost(parms).trainModel().get());
      assertNotNull(sparseModel);
      assertTrue(sparseModel._output._sparse);

      parms._train = denseFrame._key;
      parms._response_column = "C1";
      parms._seed = 42;
      parms._ntrees = 1;
      parms._dmatrix_type = XGBoostModel.XGBoostParameters.DMatrixType.auto;

      // Dense model utilizes fold column zero values to calculate precise memory requirements
      denseModel = (XGBoostModel) Scope.track_generic(new hex.tree.xgboost.XGBoost(parms).trainModel().get());
      assertNotNull(denseModel);
      assertFalse(denseModel._output._sparse);

      Log.info(sparseModel);
    } finally {
      if(sparseModel != null) sparseModel.deleteCrossValidationModels();
      if(denseModel != null) denseModel.deleteCrossValidationModels();
      Scope.exit();
    }
  }



  @Test
  public void testMojoBoosterDump() throws IOException {
    Assume.assumeTrue(! XGBoostMojoReader.useJavaScoring());
    Scope.enter();
    try {
      Frame tfr = Scope.track(parse_test_file("./smalldata/prostate/prostate.csv"));

      Scope.track(tfr.replace(1, tfr.vecs()[1].toCategoricalVec()));   // Convert CAPSULE to categorical
      Scope.track(tfr.replace(3, tfr.vecs()[3].toCategoricalVec()));   // Convert RACE to categorical
      DKV.put(tfr);

      XGBoostModel.XGBoostParameters parms = new XGBoostModel.XGBoostParameters();
      parms._train = tfr._key;
      parms._response_column = "AGE";
      parms._ignored_columns = new String[]{"ID"};
      parms._seed = 42;
      parms._ntrees = 7;

      XGBoostModel model = (XGBoostModel) Scope.track_generic(new hex.tree.xgboost.XGBoost(parms).trainModel().get());
      Log.info(model);

      XGBoostMojoModel mojo = getMojo(model);
      assertTrue(mojo instanceof XGBoostNativeMojoModel);

      String[] dump = ((XGBoostNativeMojoModel) mojo).getBoosterDump(false, "text");
      assertEquals(parms._ntrees, dump.length);
    } finally {
      Scope.exit();
    }
  }

  private static XGBoostMojoModel getMojo(XGBoostModel model) throws IOException {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    model.getMojo().writeTo(os);
    os.close();
    MojoReaderBackend mojoReaderBackend = MojoReaderBackendFactory.createReaderBackend(
            new ByteArrayInputStream(os.toByteArray()), MojoReaderBackendFactory.CachingStrategy.MEMORY);
    return (XGBoostMojoModel) MojoModel.load(mojoReaderBackend);
  }

}
