(ns cn.li.ac.block.developer.console
  "Text console widget for Ability Developer right panel.

  Modes:
  - :learn  — no ability category, offers 'learn' command (starts timed :level-up / awaken session)
  - :reset  — holding magnetic coil, offers 'reset' command (starts timed :reset session)

  Pure state transitions + CGUI rendering via in-place textbox updates.
  Key events use camelCase keys as emitted by input/key-input!."
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.cgui-screen :as cgui-screen]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.i18n :as i18n]
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

;; i18n key prefix — matching upstream ac.skill_tree.console.*
(def ^:private console-i18n-prefix "skill_tree.my_mod.console.")

(defn- loc
  "Resolve a localized console string by key suffix.
   Replaces literal \\n with actual newlines, matching upstream
   localized() → replace(\"\\\\n\", \"\\n\")."
  [k & fmt-args]
  (let [raw (i18n/translate (str console-i18n-prefix (name k)))
        text (if (string? raw) raw (str raw))
        unescaped (str/replace text "\\n" "\n")]
    (if (seq fmt-args)
      (apply format unescaped fmt-args)
      unescaped)))

;; Key codes matching mc-1.20.1 gui/cgui/input.clj enter-keys/backspace-keys
(def ^:private enter-keys #{257 335 28})
(def ^:private backspace-keys #{259 14})

;; ============================================================================
;; Pure state
;; ============================================================================

(defn init-state
  [mode player-name has-developer]
  {:lines []
   :input ""
   :phase :boot              ;; :boot | :task-running | :idle | :executing | :developing | :done
   :mode mode
   :player-name (or player-name "Player")
   :has-developer (boolean has-developer)
   :dev-progress 0.0
   :dev-result nil
   :dev-grace 0
   :cursor-visible true
   :cursor-timer 0.0
   :exec-cmd nil
   :on-start-development nil
   :container nil
   :task-queue []             ;; queued {:type :slow-print/:pause/:backspace-clear/:print :text ... :delay ...}
   :current-task nil
   :task-timer 0.0
   :boot-texts-built? false})  ;; lazy init on first tick

;; ============================================================================
;; Task queue system — matching original SkillTree.scala slowPrintTask
;; ============================================================================

(def ^:private per-char-delay 0.01)  ;; seconds per character

(defn- build-boot-tasks
  "Build task queue for boot sequence with animated memory check.
   Matching upstream SkillTree.scala lines 970-985 exactly:
     slowPrintTask(init) → pause(0.4) → animSequence(0.3, numSeq*) → slowPrintTask(startupText)"
  [mode player-name has-developer]
  (let [;; Memory check animation numbers (upstream: 1..6 → *10 + rand(6) - 3 → "X%")
        mem-pcts (mapv (fn [i]
                         (str (+ (* (inc i) 10) (rand-int 6) -3) "%"))
                       (range 6))
        ;; Final percentage: 64 + rand(4) → "64%".."67%"
        final-pct (str (+ 64 (rand-int 4)) "%")
        ;; Build animSequence items: 6 animated %s + final pct + boot_failed
        anim-items (concat
                     (mapcat (fn [text]
                               [{:type :print :text text}      ;; instant output (matching animSequence begin)
                                {:type :pause :delay 0.3}
                                {:type :backspace-clear :count (count text)}])
                             mem-pcts)
                     [{:type :print :text final-pct}           ;; "65%" — final pct, backspaced
                      {:type :pause :delay 0.3}
                      {:type :backspace-clear :count (count final-pct)}
                      {:type :print :text (loc :boot_failed)}])  ;; "Boot Failed.\n" — instant + newline
        ;; Startup text matching upstream lines 978-985
        startup-text (case mode
                       :reset
                       ;; emergency=true → always show override
                       (loc :override)
                       :learn
                       (if has-developer
                         ;; hasDeveloper=true → invalid_cat + learn_hint
                         (str (loc :invalid_cat) (loc :learn_hint))
                         ;; hasDeveloper=false → invalid_cat only
                         (loc :invalid_cat)))
        boot-tasks
        (concat
          [{:type :slow-print :text (loc :init player-name)}
           {:type :pause :delay 0.4}]
          anim-items
          [{:type :slow-print :text startup-text}])]
    (vec boot-tasks)))

(defn- clamp-lines [lines]
  (if (> (count lines) max-lines)
    (subvec lines (- (count lines) max-lines))
    lines))

(defn- process-current-task
  "Process current task. Returns updated state.
   Slow-print handles \\n by splitting text into separate console lines,
   matching upstream output() which treats \\n as line delimiter."
  [state dt-sec]
  (let [task (:current-task state)
        timer (+ (:task-timer state 0.0) dt-sec)]
    (case (:type task)
      :slow-print
      (let [text (:text task)
            char-idx (or (:char-idx task) 0)
            ;; chars-needed is total chars that should be printed by now (monotonically increasing).
            ;; Upstream: last += n * PerCharTime (carry over residual time, don't reset to 0).
            chars-needed (int (/ timer per-char-delay))
            next-idx (min (count text) chars-needed)]
        (if (< char-idx (count text))
          (let [chunk (subs text char-idx next-idx)
                state' (reduce
                         (fn [st ch]
                           (if (= ch \newline)
                             (update st :lines conj "")
                             (let [lines (:lines st)]
                               (if (seq lines)
                                 (update-in st [:lines (dec (count lines))] str ch)
                                 (update st :lines conj (str ch))))))
                         state
                         chunk)]
            (-> state'
                (assoc :task-timer timer)     ; keep accumulating — upstream: last += n*PerCharTime
                (assoc-in [:current-task :char-idx] next-idx)))
          (-> state
              (assoc :current-task nil :task-timer 0.0))))
      :pause
      (if (>= timer (:delay task 0.0))
        (assoc state :current-task nil :task-timer 0.0)
        (assoc state :task-timer timer))
      :print
      (let [text (:text task)
            ;; Split by \n: first part appends to current last line,
            ;; remaining parts become new lines (matching upstream output())
            parts (str/split text #"\n" -1)
            first-part (first parts)
            rest-parts (rest parts)
            state' (if (seq (:lines state))
                     (update-in state [:lines (dec (count (:lines state)))] str first-part)
                     (update state :lines conj first-part))]
        (-> state'
            (update :lines #(reduce conj % rest-parts))
            (update :lines clamp-lines)
            (assoc :current-task nil :task-timer 0.0)))
      :backspace-clear
      (let [count-to-clear (:count task)
            current-line  (or (last (:lines state)) "")
            new-line (if (>= (count current-line) count-to-clear)
                       (subs current-line 0 (- (count current-line) count-to-clear))
                       "")]
        (-> state
            (assoc :lines (-> (:lines state) (butlast) vec))
            (update :lines conj new-line)
            (#(if (= new-line "")
                (update % :lines (fn [ls] (if (empty? ls) ls (vec (butlast ls)))))
                %))
            (update :lines clamp-lines)
            (assoc :current-task nil :task-timer 0.0)))
      ;; no task
      (assoc state :task-timer 0.0))))

(defn- tick-task-running
  "Process task queue: dequeue next task as needed."
  [state dt-sec]
  (if (:current-task state)
    (process-current-task state dt-sec)
    (if-let [next-task (first (:task-queue state))]
      (-> state
          (update :lines clamp-lines)
          (assoc :current-task next-task :task-timer 0.0)
          (update :task-queue subvec 1))
      ;; Queue exhausted — transition to idle
      (assoc state :phase :idle :task-queue [] :task-timer 0.0 :current-task nil))))

;; Messages — use i18n, matching upstream localized() keys
(defn- msg [k]
  (case k
    :dev-begin    (loc :dev_begin)
    :no-developer "No developer device detected."
    :invalid-cmd  (loc :invalid_command)
    :reset-begin  (loc :reset_begin)
    :done         "Done."
    (str "ac.console." (name k))))

;; ============================================================================
;; State transitions (pure)
;; ============================================================================

(defn- tick-boot
  "Boot phase: build task queue on first tick, then delegate to task runner.
   Matching upstream SkillTree.scala boot sequence with slow-print animation."
  [state dt-sec]
  (if (:boot-texts-built? state)
    (tick-task-running state dt-sec)
    (let [tasks (build-boot-tasks (:mode state) (:player-name state) (:has-developer state))]
      (-> state
          (assoc :task-queue tasks :boot-texts-built? true :phase :task-running)
          (tick-task-running dt-sec)))))

(defn- tick-idle
  [state dt-sec]
  (let [ct (+ (:cursor-timer state) dt-sec)]
    (if (>= ct 0.5)
      (-> state (update :cursor-visible not) (assoc :cursor-timer 0.0))
      (assoc state :cursor-timer ct))))

;; ============================================================================
;; Command registry — extensible, matching original Console += Command(...)
;; ============================================================================

;; Command registry: {command-name-str → (fn [state] [new-state action-kw])}.
;; Register commands via register-command! to add custom console commands.
(let [command-registry (volatile! {})]
  ;; Register a console command. callback receives the current state atom
  ;; and the create-console options map, returns [new-state action-kw].
  ;; Matching original SkillTree.scala: console += Command(name, callback)
  (defn register-command! [name callback]
    (vreset! command-registry (assoc @command-registry name callback))
    nil)

  (defn- cmd-help [mode]
    (let [cmds (keys @command-registry)
          names (sort (concat (case mode :reset ["reset"] ["learn"])
                              ["help" "clear"]))]
      (str "Commands: " (str/join ", " (take 4 (distinct names))))))

  (defn- exec-cmd
    "Execute a pending command via registered handlers.
    Returns [new-state action-kw]."
    [state]
    (let [cmd (:exec-cmd state)
          base (fn [phase]
                 (-> state
                     (update :lines conj (str prompt-str))
                     (update :lines clamp-lines)
                     (assoc :phase phase :exec-cmd nil)))]
      (if-let [handler (get @command-registry cmd)]
        (handler state)
        [(-> (base :idle)
             (update :lines conj (msg :invalid-cmd))
             (update :lines clamp-lines))
         nil]))))

;; ============================================================================
;; Built-in command registrations
;; ============================================================================

(register-command! "help"
  (fn [state]
    [(-> (update state :lines conj (str prompt-str))
         (update :lines conj (cmd-help (:mode state)))
         (update :lines clamp-lines)
         (assoc :phase :idle :exec-cmd nil))
     nil]))

(register-command! "clear"
  (fn [state]
    [(assoc state :lines [] :phase :idle :exec-cmd nil) nil]))

(register-command! "learn"
  (fn [state]
    (if (= :learn (:mode state))
      (if (:on-start-development state)
        (do
          ((:on-start-development state))
          [(-> state
               (update :lines conj (str prompt-str))
               (update :lines conj (msg :dev-begin))
               (update :lines conj (loc :progress "00"))
               (update :lines clamp-lines)
               (assoc :phase :developing :exec-cmd nil :dev-progress 0.0 :done-timer 0.0))
           :developing])
        [(-> (update state :lines conj (str prompt-str))
             (update :lines conj (msg :no-developer))
             (update :lines clamp-lines)
             (assoc :phase :idle :exec-cmd nil))
         nil])
      [(-> (update state :lines conj (str prompt-str))
           (update :lines conj (msg :invalid-cmd))
           (update :lines clamp-lines)
           (assoc :phase :idle :exec-cmd nil))
       nil])))

(register-command! "reset"
  (fn [state]
    (if (= :reset (:mode state))
      (if (:on-start-development state)
        (do
          ((:on-start-development state))
          [(-> state
               (update :lines conj (str prompt-str))
               (update :lines conj (msg :reset-begin))
               (update :lines conj (loc :progress "00"))
               (update :lines clamp-lines)
               (assoc :phase :developing :exec-cmd nil :dev-progress 0.0 :done-timer 0.0))
           :developing])
        [(-> (update state :lines conj (str prompt-str))
             (update :lines conj (msg :no-developer))
             (update :lines clamp-lines)
             (assoc :phase :idle :exec-cmd nil))
         nil])
      [(-> (update state :lines conj (str prompt-str))
           (update :lines conj (msg :invalid-cmd))
           (update :lines clamp-lines)
           (assoc :phase :idle :exec-cmd nil))
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
          ;; Empty enter: no-op (matching upstream)
          state
          (-> state
              (update :lines conj (str prompt-str " " (:input state)))
              (update :lines clamp-lines)
              (assoc :input "" :phase :executing :exec-cmd cmd))))

      (contains? backspace-keys (int (or keyCode 0)))
      (if (empty? (:input state))
        state
        (update state :input #(subs % 0 (dec (count %)))))

      ;; Printable character filter: matching upstream ChatAllowedCharacters.isAllowedCharacter
      ;; — allows standard printable chars, excludes § (section sign) and control chars
      (and typedChar
           (not= typedChar (char 0))
           (not= typedChar \§)
           (>= (int typedChar) 32))
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
  [parent-area {:keys [mode container player-name focus-root on-start-development has-developer]}]
  (let [state-a (atom (assoc (init-state mode player-name has-developer)
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
                    :task-running (tick-task-running st dt-sec)
                    :executing
                    (let [[s _action] (exec-cmd st)]
                      s)
                    :developing
                    (let [container' (:container st)
                          prog (double (or (some-> container' @(:development-progress container')) 0.0))
                          is-dev (boolean (some-> container' @(:is-developing container')))
                          dev-complete (boolean (some-> container' @(:development-complete? container')))
                          grace (int (:dev-grace st 0))]
                      (cond
                        ;; Still in grace period — wait for server to set is-developing
                        (< grace 5)
                        (assoc st :dev-grace (inc grace) :dev-progress prog)

                        ;; Grace expired, server didn't start (rejected or no energy)
                        (and (>= grace 5) (not is-dev))
                        (-> st
                            (update :lines conj (msg :dev-begin))
                            (update :lines conj "ERROR: Development rejected. Check energy / induction factor.")
                            (update :lines clamp-lines)
                            (assoc :phase :idle :dev-grace 0))

                        ;; Server finished (is-dev false) — use definitive server-side state
                        (not is-dev)
                        (assoc st :phase :done :dev-progress prog :dev-grace 0
                               :dev-result (if dev-complete :success :failure))

                        ;; Progressing normally
                        :else
                        (assoc st :dev-progress prog :dev-grace 0)))
                    :done
                    ;; Auto-return to idle after 2 seconds
                    (let [dt (+ (:done-timer st 0.0) dt-sec)]
                      (if (>= dt 2.0)
                        (assoc st :phase :idle :done-timer 0.0 :dev-progress 0.0 :dev-result nil)
                        (assoc st :done-timer dt)))
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
                (let [grace (int (:dev-grace st' 0))
                      ;; Zero-padded progress matching upstream fmt(x)
                      pct-str (format "%02d" (int (* 100.0 (double (:dev-progress st')))))]
                  (if (< grace 5)
                    (comp/set-text! textbox "Requesting...")
                    (comp/set-text! textbox (loc :progress pct-str)))
                  (comp/set-text-color! textbox dim-color))
                :done
                (let [succ? (= :success (:dev-result st'))
                      mode (:mode st')
                      result-text (if succ?
                                    (if (= :reset mode)
                                      (loc :reset_succ)
                                      (loc :dev_succ))
                                    (if (= :reset mode)
                                      (loc :reset_fail)
                                      (loc :dev_fail)))]
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
