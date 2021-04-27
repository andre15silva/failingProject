package fr.inria.spirals.repairnator.pipeline.github;

import fr.inria.spirals.repairnator.LauncherUtils;
import fr.inria.spirals.repairnator.notifier.BugAndFixerBuildsNotifier;
import fr.inria.spirals.repairnator.notifier.engines.NotifierEngine;
import fr.inria.spirals.repairnator.notifier.AbstractNotifier;
import fr.inria.spirals.repairnator.notifier.PatchNotifier;
import fr.inria.spirals.repairnator.notifier.GitRepositoryPatchNotifierImpl;
import fr.inria.spirals.repairnator.notifier.GitRepositoryErrorNotifier;
import fr.inria.spirals.repairnator.pipeline.IInitNotifiers;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* Notifiers init behavior for repairing with Github instead of Travis */
public class GithubInitNotifiers implements IInitNotifiers {
	private static Logger LOGGER = LoggerFactory.getLogger(GithubInitNotifiers.class);
	protected List<AbstractNotifier> notifiers;
	protected PatchNotifier patchNotifier;

	@Override
	public void initNotifiers() {
        List<NotifierEngine> notifierEngines = LauncherUtils.initNotifierEngines(LOGGER);
        GitRepositoryErrorNotifier.getInstance(notifierEngines);

        this.notifiers = new ArrayList<>();
        this.notifiers.add(new BugAndFixerBuildsNotifier(notifierEngines));

        this.patchNotifier = new GitRepositoryPatchNotifierImpl(notifierEngines);
    }

    public List<AbstractNotifier> getNotifiers() {
    	return this.notifiers;
    }

    public PatchNotifier getPatchNotifiers() {
    	return this.patchNotifier;
    }
}