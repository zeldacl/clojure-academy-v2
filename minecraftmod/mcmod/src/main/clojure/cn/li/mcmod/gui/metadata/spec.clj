(ns cn.li.mcmod.gui.metadata.spec
  "GuiSpec constructor-focused entrypoint extracted from gui.dsl."
  (:require [cn.li.mcmod.gui.dsl :as gui-dsl]))

(def create-gui-spec gui-dsl/create-gui-spec)
(def validate-gui-spec gui-dsl/validate-gui-spec)
(def parse-slot gui-dsl/parse-slot)
(def parse-button gui-dsl/parse-button)
(def parse-label gui-dsl/parse-label)
