(ns cn.li.ac.item.developer-portable-reactive
  "Portable developer entry point backed by the shared developer Presentation controller."
  (:require [cn.li.ac.block.developer.presentation :as developer-presentation]))

(defn open! [player]
  (developer-presentation/open-portable! player))