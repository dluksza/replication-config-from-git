load("//tools/bzl:plugin.bzl", "gerrit_plugin")

gerrit_plugin(
    name = "replication-config-from-git",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: replication-config-from-git",
        "Gerrit-Module: org.luksza.gerrit.replication.config.git.GitReplicationConfigModule",
    ],
    deps = [":replication-neverlink"]
)

java_library(
    name = "replication-neverlink",
    neverlink = 1,
    exports = ["//plugins/replication"],
)
