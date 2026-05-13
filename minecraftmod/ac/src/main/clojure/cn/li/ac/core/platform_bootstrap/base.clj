(ns cn.li.ac.core.platform-bootstrap.base
  "Base bootstrap: mod-id binding, world data, config initialization."
  (:require [cn.li.ac.config.modid :as modid]))

(defn bind-mod-id!
  "Bind mod-id constants. Called at startup without args."
  []
  nil)

(defn init-world-data!
  "Initialize world data layer."
  []
  nil)

(defn init-configs!
  "Initialize configuration system."
  []
  nil)