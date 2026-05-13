(ns cn.li.ac.content.render-profiles.effect-profiles
  "AC-owned ScriptRender profile definitions.

  Keeps renderer semantics in AC while execution stays in mc1201 runtime."
  (:require [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
	    [cn.li.mcmod.client.render.script-render-registry :as script-registry]))

(defonce-guard render-profiles-installed?)

(def ^:private v1-effect-profiles
  [{:id "effect-billboard"
    :kind :billboard-cross
    :state {:layer :translucent
	    :blend :alpha}
    :params {:size 0.6
	     :color [180 220 255]}}

   {:id "ripple-mark"
    :kind :ring-lines
    :state {:layer :lines
	    :blend :alpha}
    :params {:rings 3
	     :segments 16
	     :radius-start 0.4
	     :radius-step 0.5
	     :y 0.02
	     :color-a [150 100 255]
	     :color-b [120 80 200]}}

   ;; Dark-launch capable; dispatcher keeps native fallback intact.
   {:id "arc-generic"
    :kind :polyline-arc
    :state {:layer :lines
	    :blend :alpha}
    :params {:segments 20
	     :length 20.0
	     :wiggle-amp 0.1
	     :wiggle-freq 7.0
	     :color-a [110 190 255]
	     :color-b [200 230 255]}}

   {:id "marker-billboard"
    :kind :billboard-cross
    :state {:layer :translucent
	    :blend :alpha}
    :params {:size 0.45
  	     :color [240 255 180]}}

     {:id "ray-composite"
      :kind :ray-composite
      :state {:layer :translucent
        :blend :alpha}
      :params {:length 15.0
         :inner-width 0.17
         :outer-width 0.22
         :glow-width 1.5
         :blend-in-ms 200.0
         :blend-out-ms 700.0
         :start-color 0xD8F8D8
         :end-color 0x6AF26A}}])

(defn init-render-profiles!
  []
  (with-init-guard render-profiles-installed?
    (script-registry/register-profiles! v1-effect-profiles)))