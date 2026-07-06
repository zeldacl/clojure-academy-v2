(ns cn.li.ac.terminal.client.install-effect
  "CLIENT-ONLY: TerminalInstallEffect matching original AcademyCraft behavior.

  Original: TerminalInstallEffect.java (AuxGui with 4s progress bar, fade-in/out, key_hint).
  Architecture upgrade: CGui screen instead of AuxGui; same animation parameters and flow.

  All Minecraft interop goes through cn.li.mcmod.client.platform-bridge ops."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.terminal.client.actions :as terminal-actions]
            [cn.li.ac.terminal.messages :as terminal-messages]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Original AcademyCraft parameters (TerminalInstallEffect.java:31-33)
;; ============================================================================
(def ^:private anim-length 4.0)
(def ^:private wait-time 0.7)
(def ^:private blend-in 0.2)
(def ^:private blend-out 0.2)


;; ============================================================================
;; Animation blending (matching TerminalInstallEffect.initBlender)
;; ============================================================================

(defn- now-seconds []
  (/ (double (System/nanoTime)) 1.0e9))

(defn- argb-alpha [argb]
  (bit-and (bit-shift-right (unchecked-int argb) 24) 0xFF))

(defn- argb-set-alpha [argb alpha]
  (let [a (max 0 (min 255 (int alpha)))]
    (unchecked-int (bit-or (bit-shift-left a 24)
                           (bit-and (unchecked-int argb) 0x00FFFFFF)))))

(defn- blend-alpha [dt]
  (cond
    (< dt blend-in)       (/ (double dt) blend-in)
    (> dt anim-length)    (max 0.0 (- 1.0 (/ (- (double dt) anim-length) blend-out)))
    :else                 1.0))

(defn- init-blender!
  "Store original alpha values and register frame listener for alpha blending.
   Matches TerminalInstallEffect.initBlender(w) pattern."
  [widget]
  (let [tex-comp (comp/get-widget-component widget :drawtexture)
        text-comp (comp/get-widget-component widget :textbox)
        bar-comp (comp/get-widget-component widget :progressbar)
        tex-alpha (if tex-comp (argb-alpha (:color @(:state tex-comp))) 255)
        text-alpha (if text-comp (argb-alpha (:color @(:state text-comp))) 255)
        bar-alpha (if bar-comp (argb-alpha (:color-full @(:state bar-comp))) 255)
        start-time (now-seconds)]
    (let [done? (atom false)]
      (events/on-frame widget
        (fn [_]
          (when-not @done?
            (let [dt (- (now-seconds) start-time)
                  alpha (blend-alpha dt)]
              (when tex-comp
                (swap! (:state tex-comp)
                       update :color argb-set-alpha (int (* (double tex-alpha) alpha))))
              (when text-comp
                (let [base (+ (* 255.0 0.1) (* 0.9 (double text-alpha) alpha))]
                  (swap! (:state text-comp)
                         update :color argb-set-alpha (int base))))
              (when bar-comp
                (let [new-alpha (int (* (double bar-alpha) alpha))]
                  (swap! (:state bar-comp)
                         update :color-full argb-set-alpha new-alpha)))
              ;; Stop frame listener after animation completes
              (when (>= dt (+ anim-length blend-out))
                (reset! done? true)
                (events/unlisten! widget :frame)))))))
    [tex-alpha text-alpha bar-alpha start-time]))

;; ============================================================================
;; Main effect widget
;; ============================================================================

(defn create-install-effect-widget
  "Create CGui widget for terminal install progress animation.
   Matching original TerminalInstallEffect.java behavior."
  [player]
  (try
    (let [doc (cgui-doc/read-xml (modid/asset-path "guis" "terminal_installing.xml"))
          main (cgui-doc/get-widget doc "main")]
      (when-not main
        (throw (ex-info "Install effect XML missing main widget" {})))
      ;; Initialize blender on main and all children
      (init-blender! main)
      (doseq [child (cgui-core/get-widgets main)]
        (init-blender! child))
      ;; Progress bar animation: progress = timeActive / ANIM_LENGTH (clamped to 1.0)
      (when-let [progbar-widget (cgui-core/find-widget main "progbar")]
        (when-let [pb (comp/get-widget-component progbar-widget :progressbar)]
          (let [start (atom (now-seconds))
                done? (atom false)]
            (events/on-frame progbar-widget
              (fn [_]
                (when-not @done?
                  (let [dt (- (now-seconds) @start)
                        prog (min 1.0 (/ (double dt) anim-length))]
                    (swap! (:state pb) assoc :progress prog)
                    (when (>= prog 1.0)
                      (reset! done? true)
                      (events/unlisten! progbar-widget :frame)))))))))
      ;; Completion handler: after ANIM_LENGTH + WAIT, show key_hint, open terminal
      (let [start (atom (now-seconds))
            done? (atom false)]
        (events/on-frame main
          (fn [_]
            (when (and (not @done?)
                       (>= (- (now-seconds) @start) (+ anim-length wait-time)))
              (reset! done? true)
              ;; Show key_hint message (matching original PlayerUtils.sendChat)
              (when-let [p (client-bridge/get-client-player)]
                (client-bridge/send-system-message! p "terminal.my_mod.key_hint" "Left Alt"))
              ;; Close effect screen → open terminal (matching original onKeyUp)
              (client-bridge/close-screen!)
              ;; Open terminal via shared entry point (matching original onKeyUp)
              (terminal-actions/open-terminal! player)))))
      main)
    (catch Exception e
      (cgui-core/create-widget :size [640 785]))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn build-overlay-elements
  "Build overlay elements for terminal install effect (non-modal mode)."
  [_player-uuid screen-width screen-height]
  (let [cx (quot screen-width 2) cy (quot screen-height 2)]
    [{:kind :fill :x (- cx 150) :y (- cy 20) :w 300 :h 40 :color 0xC0202020}
     {:kind :text :text "Installing terminal..." :x (- cx 60) :y (- cy 5) :color 0xFFFFFFFF}]))

(defn show!
  "Show terminal install effect for the given player (screen mode)."
  [player]
  (let [widget (create-install-effect-widget player)]
    (client-bridge/open-screen! {:cgui-root widget :title "Installing..." :log-label "terminal-install-effect"})))

(defn show-as-overlay!
  "Show terminal install effect as non-modal overlay."
  [player]
  (log/info "Showing terminal install effect as overlay for" (pr-str player))
  (client-bridge/set-active-overlay-app! :install-fx (str player)))

;; ============================================================================
;; Init — push handler registration (moved from shell.clj to break circular dep)
;; ============================================================================

(defonce-guard install-effect-push-handler-installed?)

(defn install-push-handler! []
  (with-init-guard install-effect-push-handler-installed?
    (net-client/register-push-handler!
      (terminal-messages/msg-id :terminal-install-effect)
      (fn [_payload]
        (when-let [player (client-bridge/get-client-player)]
          (show! player))))
    (log/info "AC terminal install-effect push handler installed"))
  nil)
