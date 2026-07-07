(ns cn.li.ac.block.developer.panel-reactive
  "Reactive Developer Panel — core screen + info area.
   Complex modules (skill-tree, console, wireless overlay) are TBD stubs.
   Replaces ~400 lines of find-widget + set-text! + set-progress! + set-texture!."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.dsl :as dsl]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.anim :as anim]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.gui.block-gui-reactive :as bgui]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str]))

;; ============================================================================
;; Signal-driven property binders (replace textbox-of + set-text-path!)
;; ============================================================================

(defn- bind-text! [r id value-fn]
  "Bind a text node to a computed signal that calls value-fn each frame."
  (let [clock (rt/clock-ms-sig r)
        txt-sig (sig/computed-o [clock] (fn [_] (str (or (value-fn) ""))))]
    (ui/bind! r id :text txt-sig)))

(defn- bind-progress! [r id value-fn]
  "Bind a progress node to a computed signal."
  (let [clock (rt/clock-ms-sig r)
        prog-sig (sig/computed-d [clock] (fn [_] (double (or (value-fn) 0.0))))]
    (ui/bind! r id :progress prog-sig)))

;; ============================================================================
;; Developer screen builder
;; ============================================================================

(defn create-screen
  "Create reactive developer screen.
   container: atom map with :energy :max-energy :tier :is-developing :category etc.
   on-dev-start: (fn [action extra callback]) — handles learn/level-up/reset."
  [{:keys [container menu player on-dev-start]}]
  (let [r (rt/create-runtime)
        safe-val #(some-> % deref)
        clock (rt/clock-ms-sig r)
        ;; Load developer XML page or build inline
        spec (dsl/group {:id :root :w 400 :h 187 :align-w :center :align-h :middle}
               ;; Left panel background
               (dsl/image {:id :bg :x 0 :y 0 :w 400 :h 187
                           :src (modid/asset-path "textures" "guis/developer/page_developer.png")})
               ;; Energy bar
               (dsl/progress {:id :energy-bar :x 20 :y 150 :w 120 :h 10})
               ;; Energy text
               (dsl/text {:id :energy-text :x 20 :y 135 :text "Energy: 0/0 IF"
                          :font-size 10 :color 0xFFCCCCCC})
               ;; Tier text
               (dsl/text {:id :tier-text :x 160 :y 135 :text "Tier: ..."
                          :font-size 10 :color 0xFFCCCCCC})
               ;; Status text
               (dsl/text {:id :status-text :x 160 :y 150 :text "Status: IDLE"
                          :font-size 10 :color 0xFF888888})
               ;; Developer progress bar
               (dsl/progress {:id :dev-progress :x 20 :y 170 :w 360 :h 6})
               ;; Right panel area (placeholder for skill-tree/console)
               (dsl/group {:id :right-panel :x 280 :y 10 :w 110 :h 167 :clip? true}
                 (dsl/text {:id :right-content :x 5 :y 5 :text "..."
                            :font-size 10 :color 0xFFAAAAAA}))
               ;; Upgrade button
               (dsl/box {:id :btn-upgrade :x 300 :y 150 :w 80 :h 20
                         :fill 0xFF3366CC :hover-tint 0.5}
                 (dsl/text {:id :btn-upgrade-txt :x 0 :y 0 :text "Upgrade"
                            :font-size 11 :color 0xFFFFFFFF})))]
    (rt/build! r spec)
    ;; Bind energy progress
    (bind-progress! r :energy-bar
      (fn [] (/ (double (or (safe-val (:energy container)) 0.0))
                (max 1.0 (double (or (safe-val (:max-energy container)) 1.0))))))
    ;; Bind energy text
    (bind-text! r :energy-text
      (fn [] (format "Energy: %.0f/%.0f IF"
                     (double (or (safe-val (:energy container)) 0.0))
                     (double (or (safe-val (:max-energy container)) 0.0)))))
    ;; Bind tier
    (bind-text! r :tier-text
      (fn [] (str "Tier: " (name (or (safe-val (:tier container)) :portable)))))
    ;; Bind status
    (bind-text! r :status-text
      (fn [] (if (safe-val (:is-developing container)) "DEVELOPING" "IDLE")))
    ;; Bind dev progress
    (bind-progress! r :dev-progress
      (fn [] (double (or (safe-val (:progress container)) 0.0))))
    ;; Upgrade button handler
    (events/on! r :btn-upgrade :left-click
      (fn [_rt _n _e]
        (when on-dev-start (on-dev-start :level-up {} nil))))
    ;; Store for external update
    {:runtime r :container container :menu menu}))

;; ============================================================================
;; Per-frame update (optional — most work done by computed signals above)
;; ============================================================================

(defn update!
  "Per-frame: currently no-op (signals self-update via safe-val closures).
   Future: add skill-tree/console specific updates here."
  [_screen]
  nil)

;; ============================================================================
;; Screen open
;; ============================================================================

(defn open!
  [{:keys [runtime]}]
  (bridge/open-reactive-screen! runtime "Ability Developer"))
