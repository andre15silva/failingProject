package fr.inria.spirals.repairnator.process.step.repair;

import ch.qos.logback.classic.Level;
import fr.inria.jtravis.entities.Build;
import fr.inria.spirals.repairnator.BuildToBeInspected;
import fr.inria.spirals.repairnator.utils.Utils;
import fr.inria.spirals.repairnator.config.RepairnatorConfig;
import fr.inria.spirals.repairnator.process.files.FileHelper;
import fr.inria.spirals.repairnator.process.inspectors.ProjectInspector;
import fr.inria.spirals.repairnator.process.inspectors.RepairPatch;
import fr.inria.spirals.repairnator.process.step.StepStatus;
import fr.inria.spirals.repairnator.process.step.AddExperimentalPluginRepo;
import fr.inria.spirals.repairnator.process.step.CloneRepository;
import fr.inria.spirals.repairnator.process.step.TestProject;
import fr.inria.spirals.repairnator.process.step.checkoutrepository.CheckoutBuggyBuild;
import fr.inria.spirals.repairnator.process.step.gatherinfo.BuildShouldFail;
import fr.inria.spirals.repairnator.process.step.gatherinfo.GatherTestInformation;
import fr.inria.spirals.repairnator.serializer.AbstractDataSerializer;
import fr.inria.spirals.repairnator.states.ScannedBuildStatus;
import org.hamcrest.core.Is;
import org.hamcrest.core.IsNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Tests the safe mode of NpeFix
 */
public class TestNPERepairSafe {

    private File tmpDir;

    @Before
    public void setup() {
        Utils.setLoggersLevel(Level.ERROR);
        RepairnatorConfig config = RepairnatorConfig.getInstance();
        config.setJTravisEndpoint("https://api.travis-ci.com");

    }

    @After
    public void tearDown() throws IOException {
        RepairnatorConfig.deleteInstance();
        FileHelper.deleteFile(tmpDir);
    }

    // @Test breaks CI in Jan 2020
    public void testNPERepairSafe() throws IOException {
        long buildId = 252712792; // surli/failingProject build

        Build build = this.checkBuildAndReturn(buildId, false);

        tmpDir = Files.createTempDirectory("test_nperepairsafe").toFile();

        BuildToBeInspected toBeInspected = new BuildToBeInspected(build, null, ScannedBuildStatus.ONLY_FAIL, "");

        RepairnatorConfig.getInstance().setRepairTools(Collections.singleton(NPERepairSafe.TOOL_NAME));
        ProjectInspector inspector = new ProjectInspector(toBeInspected, tmpDir.getAbsolutePath(), null, null);

        CloneRepository cloneStep = new CloneRepository(inspector);
        NPERepairSafe npeRepair = new NPERepairSafe();
        npeRepair.setProjectInspector(inspector);

        cloneStep.addNextStep(new CheckoutBuggyBuild(inspector, true))
                .addNextStep(new AddExperimentalPluginRepo(inspector, "ExperimentalNPEFixSafe", "repairnator.proj.kth", "http://repairnator.proj.kth.se:55555/"))
                .addNextStep(new TestProject(inspector))
                .addNextStep(new GatherTestInformation(inspector, true, new BuildShouldFail(), false))
                .addNextStep(npeRepair);
        cloneStep.execute();

        assertThat(npeRepair.isShouldStop(), is(false));

        List<StepStatus> stepStatusList = inspector.getJobStatus().getStepStatuses();
        assertThat(stepStatusList.size(), is(6));
        StepStatus npeStatus = stepStatusList.get(5);
        assertThat(npeStatus.getStep(), is(npeRepair));

        for (StepStatus stepStatus : stepStatusList) {
            assertThat(stepStatus.isSuccess(), is(true));
        }

        String finalStatus = AbstractDataSerializer.getPrettyPrintState(inspector);
        assertThat(finalStatus, is("PATCHED"));

        List<RepairPatch> allPatches = inspector.getJobStatus().getAllPatches();
        assertThat(allPatches.size(), is(6));
        assertThat(inspector.getJobStatus().getToolDiagnostic().get(npeRepair.getRepairToolName()), notNullValue());

        for (RepairPatch repairPatch : allPatches) {
            assertTrue(new File(repairPatch.getFilePath()).exists());
        }
    }

    private Build checkBuildAndReturn(long buildId, boolean isPR) {
        Optional<Build> optionalBuild = RepairnatorConfig.getInstance().getJTravis().build().fromId(buildId);
        assertTrue(optionalBuild.isPresent());

        Build build = optionalBuild.get();
        assertThat(build, IsNull.notNullValue());
        assertThat(buildId, Is.is(build.getId()));
        assertThat(build.isPullRequest(), Is.is(isPR));

        return build;
    }
}
