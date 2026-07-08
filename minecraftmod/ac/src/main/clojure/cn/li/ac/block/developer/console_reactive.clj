(ns cn.li.ac.block.developer.console-reactive
  "Reactive Developer Console — native text-node rendering of console.clj's
   state machine. All pure state transitions (boot animation, task queue,
   command execution) are reused verbatim from console.clj; only the CGUI
   textbox rendering is replaced with native node updates."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.ac.block.developer.console :as console])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

(def ^:private max-lines 10)
(def ^:private max-history 50)
(def ^:private line-h 10.0)
(def ^:private font-color 0xFF00CC00)
(def ^:private input-color 0xFFFFFFFF)
(def ^:private dim-color 0xFF008800)
(def ^:private prompt-str "OS >")

(defn- pull-o! [_node source] (.sGet ^cn.li.mcmod.uipojo.signal.ISigO source) nil)

(defn- clamp-history [lines]
  (let [n (count lines)]
    (if (> n max-history) (subvec (vec lines) (- n max-history)) (vec lines))))

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
    :boot (console/tick-boot state dt-sec)
    :task-running (console/tick-task-running state dt-sec)
    :executing
    (let [[s _action] (console/exec-cmd state)] s)
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
            (update :lines conj (console/msg :dev-begin))
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
    (console/tick-idle state dt-sec)))

(defn- input-line-text [state]
  (case (:phase state)
    (:idle :executing)
    (str prompt-str " " (:input state) (if (:cursor-visible state) "_" " "))
    :developing
    (let [grace (int (:dev-grace state 0))]
      (if (< grace 5)
        "Requesting..."
        (console/loc :progress (format "%02d" (int (* 100.0 (double (:dev-progress state))))))))
    :done
    (let [succ? (= :success (:dev-result state))
          mode (:mode state)]
      (if succ?
        (if (= :reset mode) (console/loc :reset_succ) (console/loc :dev_succ))
        (if (= :reset mode) (console/loc :reset_fail) (console/loc :dev_fail))))
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
        state-a (atom (assoc (console/init-state mode player-name has-developer)
                              :container container
                              :on-start-development on-start-development))
        clock (rt/clock-ms-sig rt)
        last-ms (double-array 1 (double (sig/sget-l clock)))
        tick-sig (sig/computed-o [clock]
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
        (swap! state-a console/process-key {:keyCode (:key-code evt) :typedChar (char 0)})))
    (events/on! rt console-id :change-content
      (fn [_ _ evt]
        (let [ch (first (:char evt))]
          (when ch (swap! state-a console/process-key {:keyCode 0 :typedChar ch})))))
    (events/gain-focus! rt (.getIdx root))
    (render-console! rt line-nodes @state-a)
    {:root root :state-a state-a}))
