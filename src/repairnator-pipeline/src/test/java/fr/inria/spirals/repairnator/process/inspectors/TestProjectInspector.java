package fr.inria.spirals.repairnator.process.inspectors;

import ch.qos.logback.classic.Level;
import fr.inria.jtravis.entities.Build;
import fr.inria.spirals.repairnator.BuildToBeInspected;
import fr.inria.spirals.repairnator.utils.Utils;
import fr.inria.spirals.repairnator.config.RepairnatorConfig;
import fr.inria.spirals.repairnator.notifier.AbstractNotifier;
import fr.inria.spirals.repairnator.notifier.PatchNotifierImpl;
import fr.inria.spirals.repairnator.notifier.engines.NotifierEngine;
import fr.inria.spirals.repairnator.pipeline.RepairToolsManager;
import fr.inria.spirals.repairnator.process.files.FileHelper;
import fr.inria.spirals.repairnator.process.step.AbstractStep;
import fr.inria.spirals.repairnator.process.step.BuildProject;
import fr.inria.spirals.repairnator.process.step.StepStatus;
import fr.inria.spirals.repairnator.process.step.checkoutrepository.CheckoutPatchedBuild;
import fr.inria.spirals.repairnator.process.step.push.PushProcessEnd;
import fr.inria.spirals.repairnator.process.step.repair.NPERepair;
import fr.inria.spirals.repairnator.process.utils4tests.Utils4Tests;
import fr.inria.spirals.repairnator.serializer.*;
import fr.inria.spirals.repairnator.serializer.engines.SerializedData;
import fr.inria.spirals.repairnator.serializer.engines.SerializerEngine;
import fr.inria.spirals.repairnator.states.LauncherMode;
import fr.inria.spirals.repairnator.states.PipelineState;
import fr.inria.spirals.repairnator.states.PushState;
import fr.inria.spirals.repairnator.states.ScannedBuildStatus;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.hamcrest.core.Is;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.*;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

/**
 * Created by urli on 24/04/2017.
 */
public class TestProjectInspector {

    private File tmpDir;

    @Before
    public void setUp() {
        RepairToolsManager.getInstance().discoverRepairTools();  // we want to refresh repair tools in order to avoid getting the same steps between several tests
        RepairnatorConfig config = RepairnatorConfig.getInstance();
        config.setLauncherMode(LauncherMode.REPAIR);
        config.setZ3solverPath(Utils4Tests.getZ3SolverPath());
        config.setPush(true);
        config.setPushRemoteRepo("");
        config.setRepairTools(new HashSet<>(Arrays.asList("NPEFix", "NopolSingleTest")));
        config.setGithubUserEmail("noreply@github.com");
        config.setGithubUserName("repairnator");
        config.setJTravisEndpoint("https://api.travis-ci.com");
        Utils.setLoggersLevel(Level.ERROR);
    }

    @After
    public void tearDown() throws IOException {
        RepairnatorConfig.deleteInstance();
        FileHelper.deleteFile(tmpDir);
    }

    @Test
    public void testPatchFailingProject() throws IOException, GitAPIException {
        long buildId = 220944190; // repairnator/failingProject only-one-failing

        tmpDir = Files.createTempDirectory("test_complete").toFile();

        Build failingBuild = this.checkBuildAndReturn(buildId, false);

        BuildToBeInspected buildToBeInspected = new BuildToBeInspected(failingBuild, null, ScannedBuildStatus.ONLY_FAIL, "test");

        List<AbstractNotifier> notifiers = new ArrayList<>();

        List<SerializerEngine> serializerEngines = new ArrayList<>();
        SerializerEngine serializerEngine = mock(SerializerEngine.class);
        serializerEngines.add(serializerEngine);

        List<NotifierEngine> notifierEngines = new ArrayList<>();
        NotifierEngine notifierEngine = mock(NotifierEngine.class);
        notifierEngines.add(notifierEngine);

        ProjectInspector inspector = InspectorFactory.getTravisInspector(buildToBeInspected, tmpDir.getAbsolutePath(), notifiers);

        inspector.getSerializers().add(new InspectorSerializer(serializerEngines, inspector));
        inspector.getSerializers().add(new PatchesSerializer(serializerEngines, inspector));
        inspector.getSerializers().add(new ToolDiagnosticSerializer(serializerEngines, inspector));

        inspector.setPatchNotifier(new PatchNotifierImpl(notifierEngines));
        inspector.run();

        JobStatus jobStatus = inspector.getJobStatus();

        List<StepStatus> stepStatusList = inspector.getJobStatus().getStepStatuses();

        Map<Class<? extends AbstractStep>, StepStatus.StatusKind> expectedStatuses = new HashMap<>();
        expectedStatuses.put(PushProcessEnd.class, StepStatus.StatusKind.SKIPPED); // no remote info provided
        expectedStatuses.put(CheckoutPatchedBuild.class, StepStatus.StatusKind.FAILURE); // no patch build to find
        expectedStatuses.put(NPERepair.class, StepStatus.StatusKind.SKIPPED); // No NPE

        this.checkStepStatus(stepStatusList, expectedStatuses);

        assertThat(jobStatus.getPushStates().contains(PushState.REPAIR_INFO_COMMITTED), is(true));
        assertThat(jobStatus.getFailureLocations().size(), is(1));
        assertThat(jobStatus.getFailureNames().size(), is(1));

        String finalStatus = AbstractDataSerializer.getPrettyPrintState(inspector);
        assertThat(finalStatus, is("PATCHED"));

        String remoteBranchName = "repairnator-failingProject-220944190-20210323-104424";
        assertEquals(remoteBranchName, inspector.getRemoteBranchName());

        verify(notifierEngine, atLeast(1)).notify(anyString(), anyString());
        verify(serializerEngine, times(1)).serialize(anyListOf(SerializedData.class), eq(SerializerType.INSPECTOR));
        verify(serializerEngine, times(1)).serialize(anyListOf(SerializedData.class), eq(SerializerType.PATCHES));
        verify(serializerEngine, times(1)).serialize(anyListOf(SerializedData.class), eq(SerializerType.TOOL_DIAGNOSTIC));

        Git gitDir = Git.open(new File(inspector.getRepoToPushLocalPath()));
        Iterable<RevCommit> logs = gitDir.log().call();

        Iterator<RevCommit> iterator = logs.iterator();
        assertThat(iterator.hasNext(), is(true));

        RevCommit commit = iterator.next();
        assertThat(commit.getShortMessage(), containsString("End of the Repairnator process"));

        commit = iterator.next();
        assertThat(commit.getShortMessage(), containsString("Automatic repair"));

        commit = iterator.next();
        assertThat(commit.getShortMessage(), containsString("Bug commit"));

        assertThat(iterator.hasNext(), is(false));
    }

    @Test
    public void testFailingProjectNotBuildable() throws IOException {
        long buildId = 220945185; // repairnator/failingProject only-one-failing

        tmpDir = Files.createTempDirectory("test_complete2").toFile();

        Build failingBuild = this.checkBuildAndReturn(buildId, false);

        BuildToBeInspected buildToBeInspected = new BuildToBeInspected(failingBuild, null, ScannedBuildStatus.ONLY_FAIL, "test");

        List<AbstractNotifier> notifiers = new ArrayList<>();

        List<SerializerEngine> serializerEngines = new ArrayList<>();
        SerializerEngine serializerEngine = mock(SerializerEngine.class);
        serializerEngines.add(serializerEngine);

        ProjectInspector inspector = InspectorFactory.getTravisInspector(buildToBeInspected, tmpDir.getAbsolutePath(), notifiers);

        inspector.getSerializers().add(new InspectorSerializer(serializerEngines, inspector));

        inspector.run();

        JobStatus jobStatus = inspector.getJobStatus();
        assertThat(jobStatus.getLastPushState(), is(PushState.REPO_NOT_PUSHED));

        List<StepStatus> stepStatusList = inspector.getJobStatus().getStepStatuses();

        Map<Class<? extends AbstractStep>, StepStatus.StatusKind> expectedStatuses = new HashMap<>();
        expectedStatuses.put(BuildProject.class, StepStatus.StatusKind.FAILURE); // step supposed to fail by this test case

        this.checkStepStatus(stepStatusList, expectedStatuses);

        String finalStatus = AbstractDataSerializer.getPrettyPrintState(inspector);
        assertThat(finalStatus, is(PipelineState.NOTBUILDABLE.name()));

        verify(serializerEngine, times(1)).serialize(anyListOf(SerializedData.class), eq(SerializerType.INSPECTOR));

    }

    private Build checkBuildAndReturn(long buildId, boolean isPR) {
        Optional<Build> optionalBuild = RepairnatorConfig.getInstance().getJTravis().build().fromId(buildId);
        int retryCount = 0;
        while(!optionalBuild.isPresent()) {
            if (retryCount > 3) {
                break;
            }
        }
        assertTrue(optionalBuild.isPresent());

        Build build = optionalBuild.get();
        assertThat(build, notNullValue());
        assertThat(buildId, Is.is(build.getId()));
        assertThat(build.isPullRequest(), Is.is(isPR));

        return build;
    }

    private void checkStepStatus(List<StepStatus> statuses, Map<Class<? extends AbstractStep>,StepStatus.StatusKind> expectedValues) {
        for (StepStatus stepStatus : statuses) {
            if (!expectedValues.containsKey(stepStatus.getStep().getClass())) {
                assertThat("Step failing: "+stepStatus, stepStatus.isSuccess(), is(true));
            } else {
                StepStatus.StatusKind expectedStatus = expectedValues.get(stepStatus.getStep().getClass());
                assertThat("Status was not as expected" + stepStatus, stepStatus.getStatus(), is(expectedStatus));
                expectedValues.remove(stepStatus.getStep().getClass());
            }
        }

        assertThat(expectedValues.isEmpty(), is(true));
    }
}
