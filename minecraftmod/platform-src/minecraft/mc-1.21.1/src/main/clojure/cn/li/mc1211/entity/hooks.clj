(ns cn.li.mc1211.entity.hooks
  "Data-driven scripted entity hook registration entrypoint."
  (:require [cn.li.mc1211.entity.hook-registry-core :as hook-core]))

(defn register-all-hooks!
  "Register all scripted entity hook kinds defined by hook registry specs."
  []
  (hook-core/register-all-scripted-hooks!))
