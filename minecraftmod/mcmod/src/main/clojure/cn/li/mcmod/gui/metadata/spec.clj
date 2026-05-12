(ns cn.li.mcmod.gui.metadata.spec
  "GuiSpec constructor-focused entrypoint extracted from split GUI modules."
  (:require [cn.li.mcmod.gui.parser :as gui-parser]
            [cn.li.mcmod.gui.validator :as gui-validator]))

(def create-gui-spec gui-parser/create-gui-spec)
(def validate-gui-spec gui-validator/validate-gui-spec)
(def parse-slot gui-parser/parse-slot)
(def parse-button gui-parser/parse-button)
(def parse-label gui-parser/parse-label)
