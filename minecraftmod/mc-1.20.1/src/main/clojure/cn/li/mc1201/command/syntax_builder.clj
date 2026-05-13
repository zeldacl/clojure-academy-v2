(ns cn.li.mc1201.command.syntax-builder
  "New command syntax builder API facade over brigadier_tree for migration."
  (:require [cn.li.mc1201.command.brigadier-tree :as tree]))

(def execute-command tree/execute-command)
(def build-executor tree/build-executor)
(def build-argument-node tree/build-argument-node)
(def build-arguments-chain tree/build-arguments-chain)
(def build-subcommand-node tree/build-subcommand-node)
(def build-command-node tree/build-command-node)
