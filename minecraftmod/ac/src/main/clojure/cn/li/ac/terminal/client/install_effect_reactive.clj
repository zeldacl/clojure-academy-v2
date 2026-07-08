(ns cn.li.ac.terminal.client.install-effect-reactive
  "Complete reactive replacement for install_effect.clj.
   Signal-driven alpha blending + progress animation (replaces on-frame polling)."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.terminal.messages :as terminal-messages]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.anim :as anim]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.dsl :as dsl]))

;; ============================================================================
;; Original params (TerminalInstallEffect.java:31-33)
;; ============================================================================

(def ^:private anim-length 4.0) (def ^:private wait-time 0.7)
(def ^:private blend-in 0.2)  (def ^:private blend-out 0.2)

(def ^:private tex-progress-bg   (modid/asset-path "textures" "guis/data_terminal/install_effect_1.png"))
(def ^:private tex-progress-fg   (modid/asset-path "textures" "guis/data_terminal/install_effect_2.png"))
(def ^:private tex-key-hint      (modid/asset-path "textures" "guis/data_terminal/press_esc.png"))

(defn- now-secs [] (/ (double (System/nanoTime)) 1.0e9))

(defn- blend-alpha [dt]
  (cond (< dt blend-in)       (/ (double dt) blend-in)
        (> dt anim-length)    (max 0.0 (- 1.0 (/ (- (double dt) anim-length) blend-out)))
        :else                 1.0))

;; ============================================================================
;; Network (preserved from old install_effect.clj)
;; ============================================================================

(defn- send-install-done! [player]
  (net-client/send-to-server {:logical-side :client :player-uuid player}
    (terminal-messages/msg-id :terminal-install-effect) {}
    (fn [_] (log/info "Install effect: done callback"))))

;; ============================================================================
;; Reactive UI
;; ============================================================================

(defn create-runtime [player]
  (let [r (rt/create-runtime)
        start-time (now-secs)
        clock (rt/clock-ms-sig r)
        ;; Progress signal (0→1 over anim-length seconds)
        progress-sig (sig/computed-d [clock]
                       (fn [_] (let [dt (- (now-secs) start-time)]
                                 (min 1.0 (/ (max 0.0 dt) anim-length)))))
        ;; Alpha blend signal (fade-in→hold→fade-out)
        alpha-sig (sig/computed-d [clock]
                    (fn [_] (blend-alpha (- (now-secs) start-time))))
        ;; Key hint visibility (appears after wait-time)
        hint-visible (sig/computed-o [clock]
                       (fn [_] (>= (- (now-secs) start-time) wait-time)))]
    ;; Build spec
    (rt/build! r
      {:kind :group :id :root :props {:w 256 :h 128}
       :children
       [{:kind :image :id :bg-bar :props {:x 28 :y 56 :w 200 :h 16 :src tex-progress-bg}}
        {:kind :progress :id :progress-bar :props {:x 28 :y 56 :w 200 :h 16}}
        {:kind :image :id :key-hint :props {:x 78 :y 90 :w 100 :h 20 :src tex-key-hint :visible? false}}
        {:kind :text :id :title :props {:x 10 :y 10 :text "Installing Terminal..." :font-size 14 :color 0xFFFFFFFF}}]})
    ;; Bind progress
    (rt/put-user-signal! r :progress progress-sig)
    (rt/put-user-signal! r :alpha alpha-sig)
    (rt/put-user-signal! r :hint-visible hint-visible)
    ;; ESC handler — close screen
    (events/on! r :root :key
      (fn [_rt _n evt] (when (= (:key-code evt) 256) (send-install-done! player) (bridge/close-screen!))))
    r))

(defn open! [player]
  (let [r (create-runtime player)]
    (bridge/open-reactive-screen! r "Installing...")))
