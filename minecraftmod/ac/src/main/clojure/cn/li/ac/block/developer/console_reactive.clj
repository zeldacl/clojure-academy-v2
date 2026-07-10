(ns cn.li.ac.block.developer.console-reactive
  "Reactive Developer Console — native text-node rendering of the developer
   console state machine. All pure state transitions (boot animation, task
   queue, command execution — ported verbatim from the deleted console.clj)
   live here; only the CGUI textbox rendering has been replaced with native
   node updates."
  (:require [clojure.string :as str]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

(def ^:private max-lines 10)
(def ^:private max-history 50)
(def ^:private line-h 10.0)
(def ^:private font-color 0xFF00CC00)
(def ^:private input-color 0xFFFFFFFF)
(def ^:private dim-color 0xFF008800)
(def ^:private prompt-str "OS >")

;; i18n key prefix — matching upstream ac.skill_tree.console.*
(def ^:private console-i18n-prefix "skill_tree.my_mod.console.")

;; Key codes matching mc-1.20.1 gui/cgui/input.clj enter-keys/backspace-keys
(def ^:private enter-keys #{257 335 28})
(def ^:private backspace-keys #{259 14})

(defn- pull-o! [_node source] (.sGet ^cn.li.mcmod.uipojo.signal.ISigO source) nil)

(defn- clamp-history [lines]
  (let [n (count lines)]
    (if (> n max-history) (subvec (vec lines) (- n max-history)) (vec lines))))

;; ============================================================================
;; Pure state machine (ported verbatim from console.clj)
;; ============================================================================

(defn loc
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

(defn tick-task-running
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
(defn msg [k]
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

(defn tick-boot
  "Boot phase: build task queue on first tick, then delegate to task runner.
   Matching upstream SkillTree.scala boot sequence with slow-print animation."
  [state dt-sec]
  (if (:boot-texts-built? state)
    (tick-task-running state dt-sec)
    (let [tasks (build-boot-tasks (:mode state) (:player-name state) (:has-developer state))]
      (-> state
          (assoc :task-queue tasks :boot-texts-built? true :phase :task-running)
          (tick-task-running dt-sec)))))

(defn tick-idle
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
(let [command-registry (atom {})]
  ;; Register a console command. callback receives the current state atom
  ;; and the create-console options map, returns [new-state action-kw].
  ;; Matching original SkillTree.scala: console += Command(name, callback)
  (defn register-command! [name callback]
    (swap! command-registry assoc name callback)
    nil)

  (defn- cmd-help [mode]
    (let [cmds (keys @command-registry)
          names (sort (concat (case mode :reset ["reset"] ["learn"])
                              ["help" "clear"]))]
      (str "Commands: " (str/join ", " (take 4 (distinct names))))))

  (defn exec-cmd
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
      (let [cmd (str/trim (:input state))]
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

(defn- console-spec [id w h]
  {:kind :group
   :props {:id id :x 0.0 :y 0.0 :w w :h h :clip? true}
   :children
   (vec (for [i (range (inc max-lines))]
          {:kind :text
           :props {:id (keyword (str (name id) "-line-" i))
                   :x 5.0 :y (* i line-h) :w (- w 10.0) :h line-h
                   :text "" :font-size 8.0 :color font-color}}))})

;; ============================================================================
;; Per-frame state transition — mirrors console.clj's create-console frame
;; handler case-dispatch (the reused parts: tick-boot/tick-task-running/
;; tick-idle/exec-cmd). :developing / :done orchestration is reimplemented
;; here since it reads the OUTER developer container's atoms directly.
;; ============================================================================

(defn- tick-console-state [state dt-sec]
  (case (:phase state)
    :boot (tick-boot state dt-sec)
    :task-running (tick-task-running state dt-sec)
    :executing
    (let [[s _action] (exec-cmd state)] s)
    :developing
    (let [container (:container state)
          prog (double (or (some-> container :development-progress deref) 0.0))
          is-dev (boolean (some-> container :is-developing deref))
          dev-complete (boolean (some-> container :development-complete? deref))
          grace (int (:dev-grace state 0))]
      (cond
        (< grace 5)
        (assoc state :dev-grace (inc grace) :dev-progress prog)

        (and (>= grace 5) (not is-dev))
        (-> state
            (update :lines conj (msg :dev-begin))
            (update :lines conj "ERROR: Development rejected. Check energy / induction factor.")
            (update :lines clamp-history)
            (assoc :phase :idle :dev-grace 0))

        (not is-dev)
        (assoc state :phase :done :dev-progress prog :dev-grace 0
               :dev-result (if dev-complete :success :failure))

        :else
        (assoc state :dev-progress prog :dev-grace 0)))
    :done
    (let [dt (+ (:done-timer state 0.0) dt-sec)]
      (if (>= dt 2.0)
        (assoc state :phase :idle :done-timer 0.0 :dev-progress 0.0 :dev-result nil)
        (assoc state :done-timer dt)))
    (tick-idle state dt-sec)))

(defn- input-line-text [state]
  (case (:phase state)
    (:idle :executing)
    (str prompt-str " " (:input state) (if (:cursor-visible state) "_" " "))
    :developing
    (let [grace (int (:dev-grace state 0))]
      (if (< grace 5)
        "Requesting..."
        (loc :progress (format "%02d" (int (* 100.0 (double (:dev-progress state))))))))
    :done
    (let [succ? (= :success (:dev-result state))
          mode (:mode state)]
      (if succ?
        (if (= :reset mode) (loc :reset_succ) (loc :dev_succ))
        (if (= :reset mode) (loc :reset_fail) (loc :dev_fail))))
    ""))

(defn- input-line-color [phase]
  (case phase (:idle :executing) input-color dim-color))

(defn- render-console! [^UiRt rt line-nodes state]
  (let [lines (:lines state)
        total (count lines)
        visible (if (> total max-lines) (subvec lines (- total max-lines)) lines)
        vc (count visible)
        body-count (dec (count line-nodes))]
    (dotimes [i body-count]
      (when-let [^INode n (nth line-nodes i nil)]
        (ui/set-node-prop! rt n :text (if (< i vc) (nth visible i) ""))))
    (when-let [^INode input-n (nth line-nodes body-count nil)]
      (ui/set-node-prop! rt input-n :text (input-line-text state))
      (ui/set-node-prop! rt input-n :color (input-line-color (:phase state))))))

;; ============================================================================
;; Attach — build console into an existing parent group, wire key input
;; ============================================================================

(defn attach!
  "Build the console into `parent-id` (an existing group node in `rt`).
   opts: {:mode :learn|:reset :container :player-name :has-developer
          :on-start-development (fn [])}
   Returns a zero-arg detach fn (removes handlers; caller still owns
   clearing the parent's children on mode switch)."
  [^UiRt rt parent-id w h {:keys [mode container player-name has-developer on-start-development]}]
  (let [console-id (keyword (str (name parent-id) "-console"))
        spec (console-spec console-id w h)
        ^INode root (rt/build-child! rt spec (rt/node-by-id rt parent-id))
        line-nodes (vec (for [i (range (inc max-lines))]
                           (ui/item-node root (keyword (str (name console-id) "-line-" i)))))
        state-a (atom (assoc (init-state mode player-name has-developer)
                              :container container
                              :on-start-development on-start-development))
        clock (rt/clock-ms-sig rt)
        last-ms (double-array 1 (double (sig/sget-l clock)))
        tick-sig (sig/computed-do [clock]
                   (fn [ms]
                     (let [dt (max 0.001 (/ (- (double ms) (aget last-ms 0)) 1000.0))]
                       (aset last-ms 0 (double ms))
                       (let [st (swap! state-a tick-console-state dt)]
                         (render-console! rt line-nodes st)))
                     nil))]
    ;; ComputedO stored bare via put-user-signal! is never pulled (lazy-pull,
    ;; depMarkDirty only flags dirty) — bind it so rt/flush! actually forces
    ;; the per-frame side effect (state tick + text render) to run.
    (let [b (sig/bind! tick-sig root pull-o! (rt/get-dirty-bindings-q rt))]
      (rt/register-binding! rt (.getIdx root) b))
    (events/on! rt console-id :key
      (fn [_ _ evt]
        (swap! state-a process-key {:keyCode (:key-code evt) :typedChar (char 0)})))
    (events/on! rt console-id :change-content
      (fn [_ _ evt]
        (let [ch (first (:char evt))]
          (when ch (swap! state-a process-key {:keyCode 0 :typedChar ch})))))
    (events/gain-focus! rt (.getIdx root))
    (render-console! rt line-nodes @state-a)
    {:root root :state-a state-a}))
