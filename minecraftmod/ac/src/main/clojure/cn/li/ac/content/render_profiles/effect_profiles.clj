(ns cn.li.ac.content.render-profiles.effect-profiles
  "AC-owned ScriptRender profile definitions.

  Keeps renderer semantics in AC while execution stays in mc1201 runtime."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.runtime.install :as install]
	    [cn.li.mcmod.client.render.script-render-abi :as script-abi]
	    [cn.li.mcmod.client.render.script-render-registry :as script-registry]))

(def ^:private v1-effect-profiles
  [{:id "effect-billboard"
    :kind :billboard-cross
    :state {:layer :translucent
	    :blend :alpha}
    :params {:size 0.6
	     :color-r 180 :color-g 220 :color-b 255}}

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
  	     :color-r 240 :color-g 255 :color-b 180}}

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

     ;; RailgunHandEffect: arc_burst/0..39, one frame per 40 ms.
     {:id "railgun-charge-glow"
      :kind :animated-billboard
      :state {:layer :translucent
              :blend :alpha}
      :params {:texture-prefix
               (modid/namespaced-path "textures/effects/arc_burst/")
               :frame-count 40
               :frame-ms 40.0
               :half-size 0.5
               :offset-y 0.8
               :offset-z -1.0}}

     ;; EntityCoinThrowing/RendererCoinThrowing: two-sided coin, player-relative
     ;; offset, 0.3 scale and a 300 ms full rotation around a per-entity axis.
     {:id "railgun-coin"
      :kind :spinning-double-sided
      :state {:layer :translucent
              :blend :alpha
              :cull :off}
      :params {:front-texture
               (modid/namespaced-path "textures/item/coin_front.png")
               :back-texture
               (modid/namespaced-path "textures/item/coin_back.png")
               :scale 0.3
               :offset-x -0.63
               :offset-y 1.0
               :offset-z 0.3
               :rotation-period-ms 300.0}}

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
         :end-color 0x6AF26A}}

     ;; MdBall (electron-bomb/electron-missile/scatter-bomb entity): pulsing
     ;; glow ball. Replaces the pre-refactor MdBallRenderer — 5 core frames,
     ;; fullbright translucent billboard. The old renderer's separate glow
     ;; pass + alpha/size ramps are approximated by the texture's own alpha.
     {:id "md-ball"
      :kind :animated-billboard
      :state {:layer :translucent
              :blend :alpha}
      :params {:texture-prefix (modid/namespaced-path "textures/effects/mdball/")
               :frame-count 5
               :frame-ms 100.0
               :half-size 0.25
               :offset-y 0.0
               :offset-z 0.0}}

     ;; EntityBloodSplash (flesh-ripping hit splash): 10-frame splash,
     ;; matching the pre-refactor BloodSplashRenderer.
     {:id "blood-splash"
      :kind :animated-billboard
      :state {:layer :translucent
              :blend :alpha}
      :params {:texture-prefix (modid/namespaced-path "textures/effects/blood_splash/")
               :frame-count 10
               :frame-ms 50.0
               :half-size 0.4
               :offset-y 0.0
               :offset-z 0.0}}])

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
