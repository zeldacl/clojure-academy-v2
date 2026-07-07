(ns cn.li.ac.terminal.client.apps.tutorial-reactive
  "Reactive Tutorial app — signal-driven page navigation.
   Migration stub for tutorial.clj."
  (:require [cn.li.ac.terminal.client.apps.reactive-helpers :as h]))

(defn create-runtime []
  (h/load-app "guis/tutorial.xml"))

(defn open! []
  (let [r (create-runtime)]
    (h/open-app! r "Tutorial")))
