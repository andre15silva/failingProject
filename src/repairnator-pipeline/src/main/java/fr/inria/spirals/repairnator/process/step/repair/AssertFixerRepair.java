package fr.inria.spirals.repairnator.process.step.repair;

import com.google.common.io.Files;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.stamp.project.assertfixer.AssertFixerResult;
import eu.stamp.project.assertfixer.Configuration;
import eu.stamp.project.assertfixer.Main;
import fr.inria.spirals.repairnator.process.inspectors.JobStatus;
import fr.inria.spirals.repairnator.process.inspectors.RepairPatch;
import fr.inria.spirals.repairnator.process.step.StepStatus;
import fr.inria.spirals.repairnator.process.testinformation.FailureLocation;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class AssertFixerRepair extends AbstractRepairStep {
    protected static final String TOOL_NAME = "AssertFixer";
    private static final int TOTAL_TIME = 30; // 30 minutes

    public AssertFixerRepair() {

    }

    @Override
    public String getRepairToolName() {
        return TOOL_NAME;
    }

    @Override
    protected StepStatus businessExecute() {
        this.getLogger().info("Start AssertFixerRepair");
        JobStatus jobStatus = this.getInspector().getJobStatus();
        List<URL> classPath = jobStatus.getRepairClassPath();
        File[] sources = jobStatus.getRepairSourceDir();
        File[] tests = jobStatus.getTestDir();

        if (tests == null || tests.length == 0) {
            addStepError("No test directory found, this step won't be executed.");
            return StepStatus.buildSkipped(this, "No test directory found, this step won't be executed.");
        }

        Configuration configuration = new Configuration();
        configuration.setVerbose(true);

        if (sources != null && sources.length > 0) {
            List<String> sourceList = new ArrayList<>();
            for (File source : sources) {
                sourceList.add(source.getAbsolutePath());
            }

            configuration.setPathToSourceFolder(sourceList);
        }

        List<String> testList = new ArrayList<>();
        for (File testFolder : tests) {
            testList.add(testFolder.getAbsolutePath());
        }

        configuration.setPathToTestFolder(testList);

        StringBuilder classpathBuilder = new StringBuilder();
        for (String s: System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (s.contains("assert-fixer")) { // require to get access to "Logger" in the instrumented code
                try {
                    classPath.add(new URI("file:" + new File(s).getAbsolutePath()).toURL());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        // FIXME: AssertFixer is not compliant with junit 4.4
        for (int i = 0; i < classPath.size(); i++) {
            classpathBuilder.append(classPath.get(i).getPath());

            if (i < classPath.size() - 1) {
                classpathBuilder.append(":");
            }
        }
        configuration.setClasspath(classpathBuilder.toString());

        Map<String, List<String>> multipleTestCases = new HashMap<>();
        for (FailureLocation failureLocation : this.getInspector().getJobStatus().getFailureLocations()) {
            List<String> failingMethods = new ArrayList<>(failureLocation.getErroringMethods());
            failingMethods.addAll(failureLocation.getFailingMethods());
            multipleTestCases.put(failureLocation.getClassName(), failingMethods);
        }

        configuration.setMultipleTestCases(multipleTestCases);
        File outDir = Files.createTempDir();
        configuration.setOutput(outDir.getAbsolutePath());

        String asJson = new GsonBuilder().setPrettyPrinting().create().toJson(configuration);
        this.getLogger().info("Launcher AssertFixer with the following configuration: "+asJson);


        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<List<AssertFixerResult>> assertFixerExecution = executor.submit(() -> {
            try {
                Main main = new Main(configuration);
                return main.runWithResults();
            } catch (Throwable throwable) {
                addStepError("Got exception when running AssertFixer: ", throwable);
                return new ArrayList<>();
            }
        });

        List<AssertFixerResult> assertFixerResults = new ArrayList<>();
        try {
            executor.shutdown();
            assertFixerResults.addAll(assertFixerExecution.get(TOTAL_TIME, TimeUnit.MINUTES));
        } catch (Exception e) {
            addStepError("Error while executing AssertFixer", e);
        }

        List<RepairPatch> listPatches = new ArrayList<>();
        JsonArray toolDiagnostic = new JsonArray();

        boolean success = false;
        for (AssertFixerResult result : assertFixerResults) {
            JsonObject diag = new JsonObject();

            diag.addProperty("success", result.isSuccess());
            diag.addProperty("className", result.getTestClass());
            diag.addProperty("methodName", result.getTestMethod());
            diag.addProperty("exceptionMessage",result.getExceptionMessage());
            diag.addProperty("repairType", result.getRepairType().name());
            toolDiagnostic.add(diag);

            if (result.isSuccess()) {
                success = true;

                String path = result.getTestClass().replace(".","/") + ".java";
                for (File dir : this.getInspector().getJobStatus().getTestDir()) {
                    String tmpPath = dir.getAbsolutePath() + "/" + path;
                    if (new File(tmpPath).exists()) {
                        path = tmpPath;
                        break;
                    }
                }

                RepairPatch patch = new RepairPatch(this.getRepairToolName(), path, result.getDiff());
                listPatches.add(patch);
            }
        }

        listPatches = this.performPatchAnalysis(listPatches);
        if (listPatches.isEmpty()) {
            return StepStatus.buildPatchNotFound(this);
        }
        this.recordPatches(listPatches,MAX_PATCH_PER_TOOL);
        this.recordToolDiagnostic(toolDiagnostic);

        outDir.delete();

        if (success) {
            jobStatus.setHasBeenPatched(true);
            return StepStatus.buildSuccess(this);
        } else {
            return StepStatus.buildPatchNotFound(this);
        }
    }
}