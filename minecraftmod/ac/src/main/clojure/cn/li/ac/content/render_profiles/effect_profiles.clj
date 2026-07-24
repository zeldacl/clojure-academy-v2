(ns cn.li.ac.content.render-profiles.effect-profiles
  "AC-owned ScriptRender profile definitions.

  Keeps renderer semantics in AC while execution stays in mc1201 runtime."
  (:require [cn.li.mcmod.runtime.install :as install]
	    [cn.li.mcmod.client.render.script-render-abi :as script-abi]
	    [cn.li.mcmod.client.render.script-render-registry :as script-registry]))

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
    :params {:segments 16
       :length 15.0
       :show-wiggle 0.2
       :hide-wiggle 0.8
       :wiggle-amp 0.8
	     :wiggle-freq 7.0
	     :color-a [140 220 255]
	     :color-b [240 250 255]}}

   ;; Diamond shield for LightShield (matching original EntityMdShield)
   {:id "md-shield"
    :kind :ring-lines
    :state {:layer :lines
            :blend :alpha}
    :params {:rings 5
             :segments 24
             :radius-start 0.5
             :radius-step 0.15
             :y 0.02
             :color-a [120 240 255]
             :color-b [60 180 220]}}

   {:id "marker-billboard"
    :kind :billboard-cross
    :state {:layer :translucent
	    :blend :alpha}
    :params {:size 0.45
  	     :color [240 255 180]}}

     ;; Tiered zigzag arcs for body intensify effect.
     ;; Matching original EntityIntensifyEffect (EntitySurroundArc THIN
     ;; with 7 height tiers, staggered delays, zigzag sub-arcs).
     {:id "intensify-arcs"
      :kind :intensify-arcs
      :state {:layer :lines
              :blend :alpha}
      :params {:life-ticks 15
               :arc-life-ticks 3
               :tier-heights [2.0 1.8 1.5 1.0 0.5 0.0 -0.1]
               :tier-delays [0 1 3 4 6 7 8]}}

     ;; Surround arc: orbiting rings around block (NORMAL) or player (THIN).
     ;; Matching original EntitySurroundArc visual.
     {:id "surround-arc"
      :kind :ring-lines
      :state {:layer :lines
              :blend :alpha}
      :params {:rings 3
               :segments 16
               :radius-start 0.3
               :radius-step 0.4
               :y 0.02
               :color-a [110 200 255]
               :color-b [80 150 220]}}

     ;; THIN surround arc: 1 ring (item mode).
     ;; Matching original EntitySurroundArc(THIN).
     {:id "surround-arc-thin"
      :kind :ring-lines
      :state {:layer :lines
              :blend :alpha}
      :params {:rings 1
               :segments 12
               :radius-start 0.2
               :radius-step 0.3
               :y 0.02
               :color-a [160 220 255]
               :color-b [100 180 230]}}

     ;; Charging arc: longer continuous beam with heavy wiggle
     ;; matching original ArcPatterns.chargingArc.
     {:id "charging-arc"
      :kind :polyline-arc
      :state {:layer :lines :blend :alpha}
      :params {:segments 24 :length 15.0
               :show-wiggle 0.2 :hide-wiggle 0.8
               :wiggle-amp 0.6 :wiggle-freq 8.0
               :color-a [140 220 255] :color-b [240 250 255]}}

       ;; Railgun charge-hand glow. Matching original RailgunHandEffect's
     ;; arc-burst billboard sequence at the caster's hand — simplified to a
     ;; billboard-cross here since it needs to be a world-anchored entity
     ;; (follow-owner?, see entities/all.clj's railgun_charge spec) rather
     ;; than a hand-runtime effect, so every nearby player sees it too.
     ;; :params is declared for documentation only, matching effect-billboard/
     ;; marker-billboard above — ScriptedEffectBillboardRenderer's
     ;; billboard-cross case doesn't read size/color from the profile yet.
     {:id "railgun-charge-glow"
      :kind :billboard-cross
      :state {:layer :translucent
              :blend :alpha}
      :params {:size 0.5
               :color [236 170 93]}}

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

(def ^:private ac-effect-kinds
  #{:intensify-arcs})

(defn init-render-profiles!
  []
  (install/framework-once! ::render-profiles-installed?
  (fn []
    (doseq [k ac-effect-kinds]
      (script-abi/register-scripted-effect-kind! k))
    ;; Map content-owned kind keywords to platform-neutral renderer keys
    ;; so the Minecraft component can dispatch without hardcoding content-specific strings.
    (script-abi/register-kind-renderer-key! :intensify-arcs :tiered-zigzag)
    (script-registry/register-profiles! v1-effect-profiles))))
