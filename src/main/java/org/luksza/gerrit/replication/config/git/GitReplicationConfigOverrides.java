package org.luksza.gerrit.replication.config.git;

import static com.google.common.io.Files.getNameWithoutExtension;
import static com.googlesource.gerrit.plugins.replication.FanoutConfigResource.CONFIG_DIR;
import static com.googlesource.gerrit.plugins.replication.FileConfigResource.CONFIG_NAME;
import static org.eclipse.jgit.lib.FileMode.REGULAR_FILE;

import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.ReplicationConfigOverrides;
import java.io.IOException;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.BlobBasedConfig;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

public class GitReplicationConfigOverrides implements ReplicationConfigOverrides {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  static final String REF_NAME = RefNames.REFS_META + "replication";

  private final Project.NameKey allProjectsName;
  private final GitRepositoryManager repoManager;

  @Inject
  GitReplicationConfigOverrides(
      GitRepositoryManager repoManager, Provider<AllProjectsName> allProjectsNameProvider) {
    this.repoManager = repoManager;
    this.allProjectsName = allProjectsNameProvider.get();
  }

  @Override
  public Config getConfig() {
    try (Repository repo = repoManager.openRepository(allProjectsName);
        RevWalk rw = new RevWalk(repo)) {
      Ref ref = repo.exactRef(REF_NAME);
      if (ref != null) {
        RevTree tree = rw.parseTree(ref.getObjectId());
        Config baseConfig = getBaseConfig(repo, tree);
        addFanoutRemotes(repo, tree, baseConfig);

        return baseConfig;
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Cannot read replication config from git repository");
    } catch (ConfigInvalidException e) {
      logger.atWarning().withCause(e).log("Cannot parse replication config from git repository");
    }
    return new Config();
  }

  @Override
  public String getVersion() {
    try (Repository repo = repoManager.openRepository(allProjectsName)) {
      ObjectId configHead = repo.resolve(REF_NAME);
      if (configHead != null) {
        return configHead.name();
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Could not open replication configuration repository");
    }

    return "";
  }

  private Config getBaseConfig(Repository repo, RevTree tree)
      throws ConfigInvalidException, IOException {
    TreeWalk tw = TreeWalk.forPath(repo, CONFIG_NAME, tree);
    if (tw == null) {
      return new Config();
    }
    return new BlobBasedConfig(new Config(), repo, tw.getObjectId(0));
  }

  private void addFanoutRemotes(Repository repo, RevTree tree, Config destination)
      throws IOException, ConfigInvalidException {
    TreeWalk tw = TreeWalk.forPath(repo, CONFIG_DIR, tree);
    if (tw == null) {
      return;
    }

    removeRemotes(destination);

    tw.enterSubtree();
    while (tw.next()) {
      if (tw.getFileMode() == REGULAR_FILE && tw.getNameString().endsWith(".config")) {
        Config remoteConfig = new BlobBasedConfig(new Config(), repo, tw.getObjectId(0));
        addRemoteConfig(tw.getNameString(), remoteConfig, destination);
      }
    }
  }

  private static void removeRemotes(Config config) {
    Set<String> remoteNames = config.getSubsections("remote");
    if (!remoteNames.isEmpty()) {
      logger.atSevere().log(
          "When replication directory is present replication.config file cannot contain remote configuration. Ignoring: %s",
          String.join(",", remoteNames));

      for (String name : remoteNames) {
        config.unsetSection("remote", name);
      }
    }
  }

  private static void addRemoteConfig(String fileName, Config source, Config destination) {
    String remoteName = getNameWithoutExtension(fileName);
    for (String name : source.getNames("remote")) {
      destination.setStringList(
          "remote",
          remoteName,
          name,
          Lists.newArrayList(source.getStringList("remote", null, name)));
    }
  }
}
