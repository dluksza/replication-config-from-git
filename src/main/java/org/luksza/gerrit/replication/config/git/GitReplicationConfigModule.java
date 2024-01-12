package org.luksza.gerrit.replication.config.git;

import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.inject.AbstractModule;
import com.googlesource.gerrit.plugins.replication.ReplicationConfigOverrides;

public class GitReplicationConfigModule extends AbstractModule {

  @Override
  protected void configure() {
    DynamicItem.bind(binder(), ReplicationConfigOverrides.class)
        .to(GitReplicationConfigOverrides.class);
  }
}
