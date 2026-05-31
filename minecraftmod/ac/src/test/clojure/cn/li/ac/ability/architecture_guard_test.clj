(ns cn.li.ac.ability.architecture-guard-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private guarded-runtime-install-files
  ["src/main/clojure/cn/li/ac/ability/registry/category.clj"
   "src/main/clojure/cn/li/ac/ability/registry/skill.clj"
   "src/main/clojure/cn/li/ac/ability/registry/event.clj"
   "src/main/clojure/cn/li/ac/ability/spi_lifecycle.clj"
   "src/main/clojure/cn/li/ac/ability/service/context_dispatcher.clj"])

(defn- project-file
  [rel-path]
  (io/file (System/getProperty "user.dir") rel-path))

(deftest runtime-installation-no-alter-var-root-guard-test
  (doseq [rel-path guarded-runtime-install-files]
    (let [file (project-file rel-path)]
      (is (.exists file) (str "Missing guarded file: " rel-path))
      (let [source (slurp file)]
        (is (not (str/includes? source "alter-var-root"))
            (str "Forbidden alter-var-root found in guarded runtime file: " rel-path))))))
