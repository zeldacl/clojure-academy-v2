(ns cn.li.ac.block.developer.console
  "Text console widget for Ability Developer right panel.

  Modes:
  - :learn  — no ability category, offers 'learn' command (starts timed :level-up / awaken session)
  - :reset  — holding magnetic coil, offers 'reset' command (starts timed :reset session)

  Pure state transitions + CGUI rendering via in-place textbox updates.
  Key events use camelCase keys as emitted by input/key-input!."
  (:require             [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.cgui-screen :as cgui-screen]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private max-lines 10)
(def ^:private line-height 10)
(def ^:private prompt-str "OS >")
(def ^:private font-color 0xFF00CC00)
(def ^:private input-color 0xFFFFFFFF)
(def ^:private dim-color 0xFF008800)

;; Key codes matching mc-1.20.1 gui/cgui/input.clj enter-keys/backspace-keys
(def ^:private enter-keys #{257 335 28})
(def ^:private backspace-keys #{259 14})

;; ============================================================================
;; Pure state
;; ============================================================================

(defn init-state
  [mode player-name]
  {:lines []
   :input ""
   :phase :boot              ;; :boot | :idle | :executing | :developing | :done
   :mode mode
   :player-name (or player-name "Player")
   :boot-step 0
   :boot-timer 0.0            ;; seconds
   :dev-progress 0.0
   :dev-result nil
   :cursor-visible true
   :cursor-timer 0.0
   :exec-cmd nil
   :on-start-development nil  ;; set after create-console
   :container nil})           ;; set after create-console

;; Boot text generators
(def ^:private boot-texts
  [[0.2 (fn [n] (str "Welcome, " n "."))]
   [0.3 (fn [_] "Initializing developer firmware...")]
   [0.15 (fn [_] "Memory check: 64%")]
   [0.15 (fn [_] "Memory check: 72%")]
   [0.15 (fn [_] "Memory check: 83%")]
   [0.15 (fn [_] "Memory check: 91%")]
   [0.15 (fn [_] "Memory check: 97%")]
   [0.3 (fn [_] "Boot failed. Override mode active.")]
   [0.4 (fn [_] "Type 'help' for available commands.")]])

(def ^:private reset-boot-texts
  [[0.2 (fn [n] (str "Emergency reset console. User: " n))]
   [0.3 (fn [_] "WARNING: This will reset all ability progress.")]
   [0.2 (fn [_] "Type 'help' for available commands.")]])

(defn- boot-texts-for [mode]
  (case mode :reset reset-boot-texts boot-texts))

(defn- clamp-lines [lines]
  (if (> (count lines) max-lines)
    (subvec lines (- (count lines) max-lines))
    lines))

;; Messages
(defn- msg [k]
  (case k
    :dev-begin    "Starting development..."
    :no-developer "No developer device detected."
    :invalid-cmd  "Unknown command. Type 'help'."
    :reset-begin  "Starting reset..."
    :done         "Done."
    (str "ac.console." (name k))))

;; ============================================================================
;; State transitions (pure)
;; ============================================================================

(defn- tick-boot
  [state dt-sec]
  (let [texts (boot-texts-for (:mode state))
        step (:boot-step state)]
    (if (>= step (count texts))
      (assoc state :phase :idle :boot-step step :boot-timer 0.0)
      (let [timer' (+ (:boot-timer state) dt-sec)
            [delay text-fn] (nth texts step)]
        (if (>= timer' delay)
          (-> state
              (update :lines conj (text-fn (:player-name state)))
              (update :lines clamp-lines)
              (update :boot-step inc)
              (assoc :boot-timer 0.0))
          (assoc state :boot-timer timer'))))))

(defn- tick-idle
  [state dt-sec]
  (let [ct (+ (:cursor-timer state) dt-sec)]
    (if (>= ct 0.5)
      (-> state (update :cursor-visible not) (assoc :cursor-timer 0.0))
      (assoc state :cursor-timer ct))))

;; ============================================================================
;; Command execution
;; ============================================================================

(defn- cmd-help [mode]
  (case mode
    :reset "Commands: reset, help, clear"
    "Commands: learn, help, clear"))

(defn- exec-cmd
  "Execute a pending command. Returns [new-state action-kw]."
  [state]
  (let [cmd (:exec-cmd state)
        base (fn [phase]
               (-> state
                   (update :lines conj (str prompt-str))
                   (update :lines clamp-lines)
                   (assoc :phase phase :exec-cmd nil)))]
    (case cmd
      "help"
      [(-> (base :idle)
           (update :lines conj (cmd-help (:mode state)))
           (update :lines clamp-lines))
       nil]
      "clear"
      [(assoc state :lines [] :phase :idle :exec-cmd nil) nil]
      "learn"
      (if (= :learn (:mode state))
        (if (:on-start-development state)
          (do
            ((:on-start-development state))
            [(-> (base :developing)
                 (update :lines conj (msg :dev-begin))
                 (update :lines clamp-lines)
                 (assoc :dev-progress 0.0))
             :developing])
          [(-> (base :idle)
               (update :lines conj (msg :no-developer))
               (update :lines clamp-lines))
           nil])
        [(-> (base :idle)
             (update :lines conj (msg :invalid-cmd))
             (update :lines clamp-lines))
         nil])
      "reset"
      (if (= :reset (:mode state))
        (if (:on-start-development state)
          (do
            ((:on-start-development state))
            [(-> (base :developing)
                 (update :lines conj (msg :reset-begin))
                 (update :lines clamp-lines)
                 (assoc :dev-progress 0.0))
             :developing])
          [(-> (base :idle)
               (update :lines conj (msg :no-developer))
               (update :lines clamp-lines))
           nil])
        [(-> (base :idle)
             (update :lines conj (msg :invalid-cmd))
             (update :lines clamp-lines))
         nil])
      ;; default
      [(-> (base :idle)
           (update :lines conj (msg :invalid-cmd))
           (update :lines clamp-lines))
       nil])))

(defn process-key
  "Pure transition for key input. Uses camelCase keys matching input/key-input! event format."
  [state {:keys [keyCode typedChar]}]
  (if (not= :idle (:phase state))
    state
    (cond
      (contains? enter-keys (int (or keyCode 0)))
      (let [cmd (clojure.string/trim (:input state))]
        (if (empty? cmd)
          (-> state
              (update :lines conj (str prompt-str " " (:input state)))
              (update :lines clamp-lines)
              (assoc :input ""))
          (-> state
              (update :lines conj (str prompt-str " " (:input state)))
              (update :lines clamp-lines)
              (assoc :input "" :phase :executing :exec-cmd cmd))))

      (contains? backspace-keys (int (or keyCode 0)))
      (if (empty? (:input state))
        state
        (update state :input #(subs % 0 (dec (count %)))))

      (and typedChar (not= typedChar (char 0)) (< (count (:input state)) 50)
           (>= (int typedChar) 32))  ;; printable ASCII only
      (update state :input str typedChar)

      :else
      state)))

;; ============================================================================
;; Rendering
;; ============================================================================

(defn- make-text-widget
  [x y w h text color]
  (let [wgt (cgui-core/create-widget :pos [x y] :size [w h])
        _ (comp/add-component! wgt (comp/text-box :text text :color color :font-size 8))
        tb (comp/get-widget-component wgt :textbox)]
    [wgt tb]))

(defn create-console
  "Attach a console widget into `parent-area`. Returns [state-atom widget].

  opts keys:
  - :mode — :learn or :reset
  - :container — developer GUI container (for reading progress/energy)
  - :player-name — player display name
  - :focus-root — CGUI root widget for keyboard focus
  - :on-start-development — (fn []) called when learn/reset command runs"
  [parent-area {:keys [mode container player-name focus-root on-start-development]}]
  (let [state-a (atom (assoc (init-state mode player-name)
                             :container container
                             :on-start-development on-start-development))
        [p-width p-height] (cgui-core/get-size parent-area)
        ;; Pre-create text widgets for max visual lines + input line
        total-lines (inc max-lines)
        line-widgets (atom [])
        panel (cgui-core/create-widget :pos [0 0] :size [p-width p-height])]

    ;; Create and store text line widgets
    (doseq [i (range total-lines)]
      (let [y (* i line-height)
            [w tb] (make-text-widget 5 y (- p-width 10) line-height "" font-color)]
        (swap! line-widgets conj {:widget w :textbox tb})
        (cgui-core/add-widget! panel w)))

    ;; Add panel to parent area
    (cgui-core/add-widget! parent-area panel)

    ;; deepest-only dispatch: children receive mouse clicks and must forward
    ;; focus to panel so keyboard input reaches the :key handler.
    (let [click-noop (fn [_] nil)
          key-handler (fn [evt]
                        (let [st @state-a
                              st' (process-key st evt)]
                          (when (not= st st')
                            (reset! state-a st'))))]
      (doseq [{:keys [widget]} @line-widgets]
        (events/on-left-click widget click-noop)
        (events/on-key-press widget key-handler)))

    ;; Frame handler — tick state machine + update text widgets
    (events/on-frame panel
      (fn [evt]
        (let [dt-sec (max 0.001 (double (or (:partialTicks evt) 0.016)))
              st @state-a
              st' (case (:phase st)
                    :boot (tick-boot st dt-sec)
                    :executing
                    (let [[s _action] (exec-cmd st)]
                      s)
                    :developing
                    (let [container' (:container st)
                          prog (double (or (some-> container' @(:development-progress container')) 0.0))
                          is-dev (boolean (some-> container' @(:is-developing container')))]
                      (if (and (> prog 0.001) (not is-dev))
                        ;; development completed
                        (-> st
                            (assoc :phase :done :dev-progress prog
                                   :dev-result (if (> prog 0.01) :success :failure)))
                        (assoc st :dev-progress prog)))
                    :done
                    (tick-idle st dt-sec)
                    ;; :idle
                    (tick-idle st dt-sec))]
          (when (not= st st')
            (reset! state-a st'))
          ;; Update text widgets
          (let [lines (:lines st')
                phase (:phase st')
                total (count lines)
                visible (if (> total max-lines)
                          (subvec lines (- total max-lines))
                          lines)
                vc (count visible)
                lws @line-widgets]
            ;; Output lines
            (doseq [i (range (min vc total-lines))]
              (when-let [{:keys [textbox]} (nth lws i nil)]
                (comp/set-text! textbox (if (< i vc) (nth visible i) ""))))
            ;; Clear unused
            (doseq [i (range vc (dec total-lines))]
              (when-let [{:keys [textbox]} (nth lws i nil)]
                (comp/set-text! textbox "")))
            ;; Input/prompt line (last widget)
            (when-let [{:keys [textbox]} (last lws)]
              (case phase
                (:idle :executing)
                (let [cursor (if (:cursor-visible st') "_" " ")
                      full (str prompt-str " " (:input st') cursor)]
                  (comp/set-text! textbox full)
                  (comp/set-text-color! textbox input-color))
                :developing
                (let [pct (int (* 100.0 (double (:dev-progress st'))))]
                  (comp/set-text! textbox (str "Progress: " pct "%"))
                  (comp/set-text-color! textbox dim-color))
                :done
                (let [result-text (if (= :success (:dev-result st'))
                                    (msg :done) (msg :done))]
                  (comp/set-text! textbox result-text)
                  (comp/set-text-color! textbox dim-color))
                ;; :boot - blank
                (comp/set-text! textbox "")))))))

    ;; Key handler
    (events/on-key-press panel
      (fn [evt]
        (let [st @state-a
              st' (process-key st evt)]
          (when (not= st st')
            (reset! state-a st')))))

    ;; Left-click handler: makes the panel "interactive" so hit-test-interactive
    ;; returns the panel (not a child text widget) on mouse click. This keeps
    ;; keyboard focus on the widget that actually handles :key events.
    (events/on-left-click panel (fn [_] nil))

    (when focus-root
      (cgui-screen/gain-focus! focus-root panel))

    [state-a panel]))
