load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "PLUGIN_DEPS", "PLUGIN_TEST_DEPS", "gerrit_plugin")

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

junit_tests(
    name = "replication-config-from-git_tests",
    timeout = "long",
    srcs = glob([
        "src/test/java/**/*Test.java",
    ]),
    visibility = ["//visibility:public"],
    deps = PLUGIN_TEST_DEPS + PLUGIN_DEPS +[
      ":replication-config-from-git__plugin",
      "//plugins/replication"
    ],
)
