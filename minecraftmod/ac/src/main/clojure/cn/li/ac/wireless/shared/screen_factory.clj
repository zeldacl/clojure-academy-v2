(ns cn.li.ac.wireless.shared.screen-factory
  "Shared wireless screen factory entry.

  During migration this namespace delegates to existing GUI shared implementation."
  (:require [cn.li.ac.wireless.gui.screen-factory :as legacy]))

(def create-screen legacy/create-screen)
