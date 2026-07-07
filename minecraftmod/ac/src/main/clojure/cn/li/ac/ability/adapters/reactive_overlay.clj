(ns cn.li.ac.ability.adapters.reactive-overlay
  "Reactive overlay for HUD — signal-driven per-frame updates.
   Replace :client-build-overlay-plan with reactive node tree."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.dsl :as dsl]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.anim :as anim]
            [cn.li.mcmod.util.log :as log]))

(defn- build-overlay-spec [sw sh]
  (dsl/group {:id :root :w sw :h sh}
    (dsl/progress {:id :cp-bar :x 10 :y 10 :w 100 :h 8})
    (dsl/text {:id :fps :x (- sw 80) :y 5 :text "FPS: --" :font-size 12 :color 0xFF888888})))

(defn build-overlay-runtime [sw sh]
  (let [r (rt/create-runtime)]
    (rt/build! r (build-overlay-spec sw sh))
    r))

(defn update-overlay-signals!
  "Per-frame update. Called from overlay-host update-fn."
  [r]
  nil)
