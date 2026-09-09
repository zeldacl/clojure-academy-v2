(ns cn.li.ac.block.developer.console
  "Developer console state machine — boot animation, typed input, command
   execution and development progress, as pure functions over a plain map.

   HISTORY, so this is not mistaken for a new feature: the console shipped as
   `console.clj` (Minecraft widgets) until d81f8bcb7 moved it into
   `console_reactive.clj` (the reactive GUI layer), and 3b734f3ac deleted that
   whole layer -- 12 files, 4162 lines -- when the Presentation Runtime took
   over. The state machine went with it while its i18n keys
   (skill_tree.<modid>.console.*) stayed in ac_content_translations.clj, and the
   Presentation V3 developer screen was left calling into a namespace that no
   longer existed. This is that state machine restored from
   console_reactive.clj's final revision (b29dbc810, i.e. including its three
   later bug fixes: no duplicate prompt echo, no Requesting/Progress flicker, no
   trailing blank line from the localized strings), with every UiRt / node /
   signal concern removed.

   Nothing here touches rendering or the network. The screen
   (cn.li.ac.block.developer.presentation) owns the atom, calls `tick` once per
   frame with the container's development signals, feeds keystrokes through
   `type-char` / `backspace-input` / `set-input` / `submit-input`, and paints
   whatever `display-rows` returns. `:on-start-development` and
   `:reset-precheck` are installed by the screen at init-state time -- this
   namespace never reaches back into the container."
  (:require [clojure.string :as str]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.i18n :as i18n]))

(def max-lines
  "Body lines the console shows at once. Public: the screen clamps its own
   legacy append path to the same window."
  10)

(def ^:private max-history 50)
(def ^:private line-height 10.0)
(def ^:private text-inset 5.0)
(def ^:private prompt-str "OS >")

;; i18n key prefix — matching upstream ac.skill_tree.console.*
(def ^:private console-i18n-prefix (str "skill_tree." modid/MOD-ID ".console."))

(defn loc
  "Resolve a localized console string by key suffix.

   Format args go to i18n/translate, not to clojure.core/format: the platform
   formatter is the one that honours locale and positional (%1$s) specifiers.
   The lang files store newlines as the two characters \\n, which upstream's
   localized() unescapes -- do the same, or every message renders on one line."
  [k & fmt-args]
  (-> (apply i18n/translate (str console-i18n-prefix (name k)) fmt-args)
      str
      (str/replace "\\n" "\n")))

(defn init-state
  "mode (:learn | :reset), player-name, has-developer -> a fresh console.

   :phase is :boot | :task-running | :idle | :executing | :developing | :done.
   The screen adds :panel-mode, :on-start-development and :reset-precheck after
   this returns."
  [mode player-name has-developer]
  {:lines []
   :input ""
   :phase :boot
   :mode mode
   :player-name (or player-name "Player")
   :has-developer (boolean has-developer)
   :dev-progress 0.0
   :dev-result nil
   :dev-grace 0
   :was-developing? false      ;; true once the server actually starts the dev
   :done-timer 0.0
   :cursor-visible true
   :cursor-timer 0.0
   :exec-cmd nil
   :on-start-development nil
   :reset-precheck nil         ;; (fn [] nil | :reset_fail_dev | :reset_fail_other)
   :task-queue []
   :current-task nil
   :task-timer 0.0
   :dirty? true
   :boot-texts-built? false})  ;; lazy init on first tick

;; ---------------------------------------------------------------------------
;; Task queue — the boot sequence's slow-print animation
;; ---------------------------------------------------------------------------

(def ^:private per-char-delay 0.01)  ;; seconds per character

(defn- clamp-lines [lines]
  (if (> (count lines) max-lines)
    (subvec (vec lines) (- (count lines) max-lines))
    (vec lines)))

(defn- clamp-history [lines]
  (let [n (count lines)]
    (if (> n max-history) (subvec (vec lines) (- n max-history)) (vec lines))))

(defn- console-lines
  "Split a console message the way upstream Console.output() does: \\n is the
   line delimiter, and a trailing \\n only starts a fresh line that the next
   output fills -- it must not leave an empty line behind (the localized
   dev_begin / invalid_command strings all end with \\n)."
  [text]
  (let [parts (str/split (str text) #"\n" -1)]
    (loop [parts parts]
      (if (and (seq parts) (= "" (peek parts)))
        (recur (pop parts))
        (vec parts)))))

(defn- build-boot-tasks
  "Boot sequence task queue, matching upstream SkillTree.scala:
     slowPrint(init) -> pause(0.4) -> animSequence(0.3, numSeq*) -> slowPrint(startup)"
  [mode player-name has-developer]
  (let [;; Memory check animation numbers (upstream: 1..6 -> *10 + rand(6) - 3)
        mem-pcts (mapv (fn [i] (str (+ (* (inc i) 10) (rand-int 6) -3) "%"))
                       (range 6))
        ;; Final percentage: 64 + rand(4)
        final-pct (str (+ 64 (rand-int 4)) "%")
        anim-items (concat
                     (mapcat (fn [text]
                               [{:type :print :text text}
                                {:type :pause :delay 0.3}
                                {:type :backspace-clear :count (count text)}])
                             mem-pcts)
                     [{:type :print :text final-pct}
                      {:type :pause :delay 0.3}
                      {:type :backspace-clear :count (count final-pct)}
                      {:type :print :text (loc :boot_failed)}])
        startup-text (case mode
                       ;; emergency = true -> always show override
                       :reset (loc :override)
                       :learn (if has-developer
                                (str (loc :invalid_cat) (loc :learn_hint))
                                (loc :invalid_cat))
                       "")]
    (vec (concat
           [{:type :slow-print :text (loc :init player-name)}
            {:type :pause :delay 0.4}]
           anim-items
           [{:type :slow-print :text startup-text}]))))

(defn- process-current-task
  "Advance the current task. Slow-print treats \\n as a line break, matching
   upstream output()."
  [state dt-sec]
  (let [task (:current-task state)
        timer (+ (:task-timer state 0.0) dt-sec)]
    (case (:type task)
      :slow-print
      (let [text (:text task)
            char-idx (or (:char-idx task) 0)
            ;; Total chars that should be printed by now, monotonically
            ;; increasing. Upstream carries the residual time over rather than
            ;; resetting the timer, so long strings do not drift slower.
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
                (assoc :task-timer timer)
                (assoc-in [:current-task :char-idx] next-idx)))
          (assoc state :current-task nil :task-timer 0.0)))

      :pause
      (if (>= timer (:delay task 0.0))
        (assoc state :current-task nil :task-timer 0.0)
        (assoc state :task-timer timer))

      :print
      (let [parts (str/split (:text task) #"\n" -1)
            state' (if (seq (:lines state))
                     (update-in state [:lines (dec (count (:lines state)))] str (first parts))
                     (update state :lines conj (first parts)))]
        (-> state'
            (update :lines #(reduce conj % (rest parts)))
            (update :lines clamp-lines)
            (assoc :current-task nil :task-timer 0.0)))

      :backspace-clear
      (let [to-clear (:count task)
            current-line (or (last (:lines state)) "")
            new-line (if (>= (count current-line) to-clear)
                       (subs current-line 0 (- (count current-line) to-clear))
                       "")]
        (-> state
            (assoc :lines (vec (butlast (:lines state))))
            (update :lines conj new-line)
            (#(if (= new-line "")
                (update % :lines (fn [ls] (if (empty? ls) ls (vec (butlast ls)))))
                %))
            (update :lines clamp-lines)
            (assoc :current-task nil :task-timer 0.0)))

      ;; no task
      (assoc state :task-timer 0.0))))

(defn- tick-task-running [state dt-sec]
  (if (:current-task state)
    (process-current-task state dt-sec)
    (if-let [next-task (first (:task-queue state))]
      (-> state
          (update :lines clamp-lines)
          (assoc :current-task next-task :task-timer 0.0)
          (update :task-queue subvec 1))
      (assoc state :phase :idle :task-queue [] :task-timer 0.0 :current-task nil))))

(defn- tick-boot [state dt-sec]
  (if (:boot-texts-built? state)
    (tick-task-running state dt-sec)
    (-> state
        (assoc :task-queue (build-boot-tasks (:mode state) (:player-name state)
                                             (:has-developer state))
               :boot-texts-built? true
               :phase :task-running)
        (tick-task-running dt-sec))))

(defn- tick-idle [state dt-sec]
  (let [ct (+ (:cursor-timer state 0.0) dt-sec)]
    (if (>= ct 0.5)
      (-> state (update :cursor-visible not) (assoc :cursor-timer 0.0))
      (assoc state :cursor-timer ct))))

(defn- msg [k]
  (case k
    :dev-begin (loc :dev_begin)
    :invalid-cmd (loc :invalid_command)
    :reset-begin (loc :reset_begin)
    (str "ac.console." (name k))))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn- cmd-help [mode]
  (str "Commands: "
       (str/join ", " (sort (concat (case mode :reset ["reset"] ["learn"])
                                    ["help" "clear"])))))

(defn- invalid-command [state]
  (-> state
      (update :lines into (console-lines (msg :invalid-cmd)))
      (update :lines clamp-lines)
      (assoc :phase :idle :exec-cmd nil)))

(defn- begin-development [state begin-msg]
  ((:on-start-development state))
  ;; Upstream: echo (already printed at Enter) -> printTask(begin). The live
  ;; percentage renders in the input line while :developing, so no static
  ;; "Progress: 00%" body line here.
  (-> state
      (update :lines into (console-lines begin-msg))
      (update :lines clamp-lines)
      (assoc :phase :developing :exec-cmd nil
             :dev-progress 0.0 :done-timer 0.0 :dev-grace 0 :was-developing? false)))

(defn- exec-command
  "Run the command sitting in :exec-cmd. The typed line was already echoed at
   Enter, so no handler prints a second prompt."
  [state]
  (case (:exec-cmd state)
    "help" (-> state
               (update :lines conj (cmd-help (:mode state)))
               (update :lines clamp-lines)
               (assoc :phase :idle :exec-cmd nil))

    "clear" (assoc state :lines [] :phase :idle :exec-cmd nil)

    "learn" (if (and (= :learn (:mode state)) (:on-start-development state))
              (begin-development state (msg :dev-begin))
              ;; Upstream never registers the command in those states, so it
              ;; falls through to "Invalid command."
              (invalid-command state))

    "reset" (if (and (= :reset (:mode state)) (:on-start-development state))
              ;; Client-side canReset pre-check, matching upstream
              ;; SkillTree.scala initReset: a refused reset prints its specific
              ;; error and never enters the progress loop. Server-side
              ;; validation stays authoritative.
              (if-let [fail-key (some-> (:reset-precheck state) (#(%)))]
                (-> state
                    (update :lines into (console-lines (loc fail-key)))
                    (update :lines clamp-lines)
                    (assoc :phase :idle :exec-cmd nil))
                (begin-development state (msg :reset-begin)))
              (invalid-command state))

    (invalid-command state)))

(defn- tick-developing
  [state {:keys [development-progress is-developing? development-complete?]}]
  (let [prog (double (or development-progress 0.0))
        is-dev (boolean is-developing?)
        grace (int (:dev-grace state 0))]
    (cond
      (< grace 5)
      (assoc state :dev-grace (inc grace) :dev-progress prog)

      is-dev
      ;; Keep grace at/above 5 so the progress line renders steadily. Resetting
      ;; it every tick flashed "Requesting..." against "Progress: NN%".
      (assoc state :dev-progress prog :was-developing? true)

      (:was-developing? state)
      ;; Ran and has now ended. This must be tested before the rejection branch
      ;; below, which used to shadow it so completion never showed.
      (assoc state :phase :done :dev-progress prog :dev-grace 0
             :dev-result (if (boolean development-complete?) :success :failure))

      :else
      ;; Never started within the grace window — the server rejected it.
      (-> state
          (update :lines into (console-lines (msg :dev-begin)))
          (update :lines conj "ERROR: Development rejected. Check energy / induction factor.")
          (update :lines clamp-history)
          (assoc :phase :idle :dev-grace 0 :was-developing? false)))))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- visible-lines [state]
  (let [lines (vec (:lines state))
        total (count lines)]
    (if (> total max-lines) (subvec lines (- total max-lines)) lines)))

(defn- input-line-text [state]
  (case (:phase state)
    (:idle :executing)
    (str prompt-str " " (:input state) (if (:cursor-visible state) "_" " "))

    :developing
    (if (< (int (:dev-grace state 0)) 5)
      "Requesting..."
      (loc :progress (format "%02d" (int (* 100.0 (double (:dev-progress state 0.0)))))))

    :done
    ;; The localized *_succ / *_fail strings end with \n; the input line must
    ;; not carry the newline.
    (let [success? (= :success (:dev-result state))
          reset? (= :reset (:mode state))]
      (first (console-lines (if success?
                              (if reset? (loc :reset_succ) (loc :dev_succ))
                              (if reset? (loc :reset_fail) (loc :dev_fail))))))
    ""))

(defn- render-signature
  "What the screen would actually paint. `tick` compares this across a step to
   decide :dirty?, so a tick that only advances an internal timer does not ask
   the screen to repaint."
  [state]
  [(visible-lines state) (input-line-text state)])

(defn display-rows
  "console state, area width -> painted rows [{:x :y :w :h :label} ...] for the
   developer screen's console repeater. Body lines first, the prompt/progress
   line last, laid out from (5, 5) on a 10px grid like upstream."
  [state width]
  (let [w (max 0.0 (- (double width) (* 2.0 text-inset)))
        body (visible-lines state)]
    (-> (into []
              (map-indexed (fn [i line]
                             {:x text-inset :y (+ text-inset (* i line-height))
                              :w w :h line-height :label (str line)}))
              body)
        (conj {:x text-inset :y (+ text-inset (* max-lines line-height))
               :w w :h line-height :label (input-line-text state)}))))

;; ---------------------------------------------------------------------------
;; Input
;; ---------------------------------------------------------------------------

(defn editing?
  "True while the console accepts typing. Everything else (boot animation,
   command execution, an in-flight development) swallows keys, exactly as the
   old process-key did by returning the state unchanged."
  [state]
  (= :idle (:phase state)))

(defn type-char
  "Append typed text. Filters the way upstream's
   ChatAllowedCharacters.isAllowedCharacter does: printable only, no section
   sign, no control characters."
  [state text]
  (if-not (editing? state)
    state
    (let [allowed (->> (str text)
                       (filter (fn [^Character ch]
                                 (and (not= ch \§) (>= (int ch) 32))))
                       (apply str))]
      (if (seq allowed)
        (-> state (update :input str allowed) (assoc :dirty? true))
        state))))

(defn backspace-input [state]
  (if (or (not (editing? state)) (empty? (:input state)))
    state
    (-> state
        (update :input #(subs % 0 (dec (count %))))
        (assoc :dirty? true))))

(defn set-input [state value]
  (if-not (editing? state)
    state
    (assoc state :input (str value) :dirty? true)))

(defn submit-input
  "Enter. Echoes the typed line and hands the command to the next `tick`;
   an empty line is a no-op, matching upstream."
  [state]
  (let [command (str/lower-case (str/trim (str (:input state))))]
    (if (or (not (editing? state)) (empty? command))
      state
      (-> state
          (update :lines conj (str prompt-str " " (:input state)))
          (update :lines clamp-lines)
          (assoc :input "" :phase :executing :exec-cmd command :dirty? true)))))

;; ---------------------------------------------------------------------------
;; Frame step
;; ---------------------------------------------------------------------------

(defn tick
  "Advance one frame. dt-sec is real elapsed seconds; the development signals
   are read from the container by the caller and passed in, so this namespace
   stays free of container/atom knowledge.

   Sets :dirty? to whether anything the screen paints actually changed."
  [state dt-sec & {:as opts}]
  (let [dt (double (or dt-sec 0.0))
        state' (case (:phase state)
                 :boot (tick-boot state dt)
                 :task-running (tick-task-running state dt)
                 :executing (exec-command state)
                 :developing (tick-developing state (or opts {}))
                 :done (let [t (+ (:done-timer state 0.0) dt)]
                         ;; Upstream pauses 0.5s on the result, then returns to
                         ;; the prompt.
                         (if (>= t 0.5)
                           (assoc state :phase :idle :done-timer 0.0
                                  :dev-progress 0.0 :dev-result nil)
                           (assoc state :done-timer t)))
                 (tick-idle state dt))]
    (assoc state' :dirty? (not= (render-signature state) (render-signature state')))))
