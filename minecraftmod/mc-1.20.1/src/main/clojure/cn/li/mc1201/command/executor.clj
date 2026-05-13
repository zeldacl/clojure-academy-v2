(ns cn.li.mc1201.command.executor
  "New command executor API facade over executor_core for migration."
  (:require [cn.li.mc1201.command.executor-core :as core]))

(def default-ability-data (var-get #'core/default-ability-data))
(def get-player-runtime-data core/get-player-runtime-data)
(def set-player-runtime-data! core/set-player-runtime-data!)
(def execute-send-message-action core/execute-send-message-action)
(def grant-advancement! core/grant-advancement!)
(def execute-grant-advancement-action core/execute-grant-advancement-action)
(def execute-switch-category-action core/execute-switch-category-action)
(def execute-learn-node-action core/execute-learn-node-action)
(def execute-unlearn-node-action core/execute-unlearn-node-action)
(def execute-learn-all-nodes-action core/execute-learn-all-nodes-action)
(def execute-list-learned-nodes-action core/execute-list-learned-nodes-action)
(def execute-list-available-nodes-action core/execute-list-available-nodes-action)
(def execute-set-level-action core/execute-set-level-action)
(def execute-set-node-exp-action core/execute-set-node-exp-action)
