(ns cn.li.ac.terminal.client.install-effect
  "CLIENT-ONLY: TerminalInstallEffect matching original AcademyCraft behavior.

  Original: TerminalInstallEffect.java (AuxGui with 4s progress bar, fade-in/out, key_hint).
  Architecture upgrade: CGui screen instead of AuxGui; same animation parameters and flow.

  All Minecraft interop goes through cn.li.mcmod.client.platform-bridge ops."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]))

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
    (events/on-frame widget
      (fn [_]
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
                     update :color-full argb-set-alpha new-alpha))))))
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
          (let [start (atom (now-seconds))]
            (events/on-frame progbar-widget
              (fn [_]
                (let [dt (- (now-seconds) @start)
                      prog (min 1.0 (/ (double dt) anim-length))]
                  (swap! (:state pb) assoc :progress prog)))))))
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
              (client-bridge/open-screen! :ac/terminal {:player player})))))
      main)
    (catch Exception e
      (cgui-core/create-widget :size [640 785]))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn show!
  "Show terminal install effect for the given player.
   Called from push handler when server sends :terminal-install-effect message."
  [player]
  (let [widget (create-install-effect-widget player)]
    (client-bridge/open-simple-gui! widget "Installing..." {:log-label "terminal-install-effect"})))
