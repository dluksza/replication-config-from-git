package org.luksza.gerrit.replication.config.git;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllUsersNameProvider;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.testing.InMemoryRepositoryManager;
import com.google.inject.util.Providers;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GitReplicationConfigOverridesTest {
  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  private static AllProjectsName ALL_PROJECTS = new AllProjectsName(AllUsersNameProvider.DEFAULT);
  private GitRepositoryManager repoManager = new InMemoryRepositoryManager();
  private GitReplicationConfigOverrides configOverrides;
  private PersonIdent gerritPersonaIdent;

  @Before
  public void setUp() throws Exception {
    gerritPersonaIdent = new PersonIdent("Gerrit", "gerrit@unittest.com");
    repoManager.createRepository(ALL_PROJECTS).close();
    configOverrides = newGitReplicationConfigOverrides();
  }

  @Test
  public void createRootConfigWhenNotExist() throws Exception {
    Config update = new Config();
    update.setString("update", null, "option", "value");

    configOverrides.update(update);
    Config updatedConfig = newGitReplicationConfigOverrides().getConfig();

    assertThat(updatedConfig.getString("update", null, "option")).isEqualTo("value");
  }

  @Test
  public void createFanoutConfig() throws Exception {
    Config update = new Config();
    update.setString("remote", "example", "option", "value");

    configOverrides.update(update);
    Config updatedConfig = newGitReplicationConfigOverrides().getConfig();

    assertThat(updatedConfig.getString("remote", "example", "option")).isEqualTo("value");
  }

  @Test
  public void updateRootConfig() throws Exception {
    Config update = new Config();
    update.setString("update", null, "option", "value");
    configOverrides.update(update);

    update.setString("update", null, "option", "updated");
    configOverrides.update(update);
    Config updatedConfig = newGitReplicationConfigOverrides().getConfig();

    assertThat(updatedConfig.getString("update", null, "option")).isEqualTo("updated");
  }

  @Test
  public void updateFanoutConfig() throws Exception {
    Config update = new Config();
    update.setString("remote", "example", "option", "value");
    configOverrides.update(update);

    update.setString("remote", "example", "option", "updated");
    configOverrides.update(update);
    Config updatedConfig = newGitReplicationConfigOverrides().getConfig();

    assertThat(updatedConfig.getString("remote", "example", "option")).isEqualTo("updated");
  }

  private GitReplicationConfigOverrides newGitReplicationConfigOverrides() {
    return new GitReplicationConfigOverrides(
        repoManager, Providers.of(ALL_PROJECTS), gerritPersonaIdent);
  }
}
