# Gerrit Replication Config from Git

Example plugin implementing `GitReplicationConfigOverrides` extension point
from Gerrit `replication` plugin.

It will read `replication.config` and `replication/*.config` files from the
`refs/meta/replication` of the `All-Projects` repository. Providing a convenient
way to override and extend the `replication` configuration found in
`$gerrit_site/etc/replication.config`.

## Building

Clone this repository and create a symbolic link in the `plugins/` directory of
your local Gerrit checkout. Make sure to use the `replication` plugin source
at least `v3.9.1-10-g65fbf9b`. Then in the root of Gerrit checkout run:

```
$ bazel build plugins/replication-config-from-git
```

Deploy the `bazel-bin/plugins/replication-config-from-git/replication-config-from-git.jar`
to your Geerit instance `plugins/` directory. You will also need
`replication.jar` in the `plugins/` directory, at least in version
`v3.9.1-10-g65fbf9b`. 

## Usage

Replication overrides kick in when the replication config is reloaded, this
means that after Gerrit starts configuration will be refreshed 2 minutes
later, then it will be checked for changes every 1 minute.

In order to create and modify the configuration you would need:
`Create Reference`, `Push` and `Read` permission on `refs/meta/replication`
branch of `All-Projects` repository. From this point, you can just create
the `replication.config` file, commit and push to the `refs/meta/replication`.
Wait at most 60s and see your replication configuration being updated.

