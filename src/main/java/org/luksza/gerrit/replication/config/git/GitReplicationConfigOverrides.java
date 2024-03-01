package org.luksza.gerrit.replication.config.git;

import static com.google.common.io.Files.getNameWithoutExtension;
import static com.googlesource.gerrit.plugins.replication.FanoutConfigResource.CONFIG_DIR;
import static com.googlesource.gerrit.plugins.replication.FileConfigResource.CONFIG_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.dircache.DirCacheEntry.STAGE_0;
import static org.eclipse.jgit.lib.FileMode.REGULAR_FILE;
import static org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD;
import static org.eclipse.jgit.lib.RefUpdate.Result.NEW;

import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.ReplicationConfigOverrides;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.BlobBasedConfig;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

public class GitReplicationConfigOverrides implements ReplicationConfigOverrides {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  static final String REF_NAME = RefNames.REFS_META + "replication";

  private final Project.NameKey allProjectsName;
  private final PersonIdent gerritPersonIdent;
  private final GitRepositoryManager repoManager;

  @Inject
  GitReplicationConfigOverrides(
      GitRepositoryManager repoManager,
      Provider<AllProjectsName> allProjectsNameProvider,
      @GerritPersonIdent PersonIdent gerritPersonIdent) {
    this.repoManager = repoManager;
    this.allProjectsName = allProjectsNameProvider.get();
    this.gerritPersonIdent = gerritPersonIdent;
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
  public void update(Config config) throws IOException {
    try (Repository repo = repoManager.openRepository(allProjectsName);
        RevWalk rw = new RevWalk(repo);
        RefUpdateContext ctx = RefUpdateContext.open(RefUpdateContext.RefUpdateType.PLUGIN);
        ObjectReader reader = repo.newObjectReader();
        ObjectInserter inserter = repo.newObjectInserter()) {
      ObjectId configHead = repo.resolve(REF_NAME);
      DirCache dirCache = readTree(repo, reader, configHead);
      DirCacheEditor editor = dirCache.editor();
      Config rootConfig = readConfig(CONFIG_NAME, repo, rw, configHead);

      for (String section : config.getSections()) {
        if ("remote".equals(section)) {
          updateRemoteConfig(config, repo, rw, configHead, editor, inserter);
        } else {
          updateRootConfig(config, section, rootConfig);
        }
      }
      insertConfig(CONFIG_NAME, rootConfig, editor, inserter);
      editor.finish();

      CommitBuilder cb = new CommitBuilder();
      ObjectId newTreeId = dirCache.writeTree(inserter);
      if (configHead != null) {
        RevTree oldTreeId = repo.parseCommit(configHead).getTree();
        if (newTreeId.equals(oldTreeId)) {
          logger.atInfo().log("No configuration changes were applied, ignoring");
          return;
        }
        cb.setParentId(configHead);
      }
      cb.setAuthor(gerritPersonIdent);
      cb.setCommitter(gerritPersonIdent);
      cb.setTreeId(newTreeId);
      cb.setMessage("Update configuration");
      ObjectId newConfigHead = inserter.insert(cb);
      inserter.flush();
      RefUpdate refUpdate = repo.getRefDatabase().newUpdate(REF_NAME, false);
      refUpdate.setNewObjectId(newConfigHead);
      RefUpdate.Result result = refUpdate.update();
      if (result != FAST_FORWARD && result != NEW) {
        throw new IOException("Updating replication config failed: " + result);
      }
    }
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

  private Config readConfig(
      String configPath, Repository repo, RevWalk rw, @Nullable ObjectId treeId) {
    if (treeId != null) {
      try {
        RevTree tree = rw.parseTree(treeId);
        TreeWalk tw = TreeWalk.forPath(repo, configPath, tree);
        if (tw != null) {
          return new BlobBasedConfig(new Config(), repo, tw.getObjectId(0));
        }
      } catch (ConfigInvalidException | IOException e) {
        logger.atWarning().withCause(e).log(
            "failed to load replication configuration from branch %s of %s, path %s",
            REF_NAME, allProjectsName.get(), configPath);
      }
    }

    return new Config();
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

  private void updateRemoteConfig(
      Config config,
      Repository repo,
      RevWalk rw,
      @Nullable ObjectId refId,
      DirCacheEditor editor,
      ObjectInserter inserter)
      throws IOException {
    for (String remoteName : config.getSubsections("remote")) {
      String configPath = String.format("%s/%s.config", CONFIG_DIR, remoteName);
      Config baseConfig = readConfig(configPath, repo, rw, refId);

      updateConfigSubSections(config, "remote", remoteName, baseConfig);
      insertConfig(configPath, baseConfig, editor, inserter);
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
    updateConfigSubSections(source, "remote", remoteName, destination);
  }

  private static DirCache readTree(Repository repo, ObjectReader reader, ObjectId configHead)
      throws IOException {
    DirCache dc = DirCache.newInCore();
    if (configHead != null) {
      RevTree tree = repo.parseCommit(configHead).getTree();
      DirCacheBuilder b = dc.builder();
      b.addTree(new byte[0], STAGE_0, reader, tree);
      b.finish();
    }
    return dc;
  }

  private static void updateRootConfig(Config config, String section, Config rootConfig) {
    for (String subsection : config.getSubsections(section)) {
      updateConfigSubSections(config, section, subsection, rootConfig);
    }

    for (String name : config.getNames(section, true)) {
      List<String> values = Lists.newArrayList(config.getStringList(section, null, name));
      rootConfig.setStringList(section, null, name, values);
    }
  }

  private static void updateConfigSubSections(
      Config source, String section, String subsection, Config destination) {
    for (String name : source.getNames(section, subsection, true)) {
      List<String> values = Lists.newArrayList(source.getStringList(section, subsection, name));
      destination.setStringList(section, subsection, name, values);
    }
  }


  private static void insertConfig(
      String configPath, Config config, DirCacheEditor editor, ObjectInserter inserter)
      throws IOException {
    String configText = config.toText();
    ObjectId configId = inserter.insert(Constants.OBJ_BLOB, configText.getBytes(UTF_8));
    editor.add(
        new DirCacheEditor.PathEdit(configPath) {
          @Override
          public void apply(DirCacheEntry ent) {
            ent.setFileMode(REGULAR_FILE);
            ent.setObjectId(configId);
          }
        });
  }
}
