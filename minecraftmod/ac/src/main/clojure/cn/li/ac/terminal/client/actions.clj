(ns cn.li.ac.terminal.client.actions
  "CLIENT-ONLY: side-checked dynamic-load entry points for shared item handlers and client init."
  (:require [cn.li.ac.terminal.client.apps.skill-tree :as skill-tree]
            [cn.li.ac.terminal.client.apps.tutorial :as tutorial-app]
            [cn.li.ac.terminal.client.shell :as shell]
            [cn.li.ac.terminal.client.shell-reactive :as shell-reactive]))

(defn install-ui-hooks!
  []
  (shell-reactive/install-ui-hooks-reactive!))

(defn open-terminal!
  [player]
  (shell/open-terminal player))

(defn open-tutorial!
  [player]
  (tutorial-app/open! player))

(defn open-skill-tree!
  ([player] (skill-tree/open! player))
  ([player learn-context] (skill-tree/open! player learn-context)))

(defn toggle-terminal!
  [player]
  (shell/toggle-terminal! player))
