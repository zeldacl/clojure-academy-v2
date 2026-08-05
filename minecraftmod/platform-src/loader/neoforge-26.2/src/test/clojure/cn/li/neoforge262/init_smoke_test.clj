(ns cn.li.neoforge262.init-smoke-test
  "Smoke tests for NeoForge 26.2 that avoid loading Minecraft/logging stacks.
  Full loader bootstrap is covered by :platform:compileClojure AOT + entrypoint checks."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]))

(deftest loader-sources-present
  (testing "mod and platform init sources are on the classpath as resources or were AOT'd"
    (let [mod-init (io/resource "cn/li/neoforge262/mod__init.class")
          platform-init (io/resource "cn/li/neoforge262/platform/init__init.class")]
      (is (some? mod-init) "cn.li.neoforge262.mod should be AOT'd into test runtime classpath")
      (is (some? platform-init) "cn.li.neoforge262.platform.init should be AOT'd into test runtime classpath"))))
