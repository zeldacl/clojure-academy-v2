(ns cn.li.ac.terminal.client.actions
  "CLIENT-ONLY: side-checked dynamic-load entry points for shared item handlers and client init."
  (:require [cn.li.ac.terminal.client.apps.skill-tree :as skill-tree]
            [cn.li.ac.terminal.client.apps.static-pages :as static-pages]
            [cn.li.ac.terminal.client.shell :as shell]))

(defn install-ui-hooks!
  []
  (shell/install-ui-hooks!))

(defn open-terminal!
  [player]
  (shell/open-terminal player))

(defn open-tutorial!
  [player]
  (static-pages/open-tutorial! player))

(defn open-skill-tree!
  ([player] (skill-tree/open! player))
  ([player learn-context] (skill-tree/open! player learn-context)))
