(ns cn.li.ability.editor.hit
  "The canvas drag state machine: pointer events (:down/:move/:up) plus
   an abstract HIT RESULT (what the pointer landed on -- a node body, a
   pin, or empty canvas) drive transitions between idle/dragging-node/
   dragging-wire/panning (on-down's own :canvas branch -- there is no
   separate box-select mode; empty canvas always starts a pan).
   Deliberately decoupled from
   actual pixel hit-testing: cn.li.presentation.core.runtime's own
   HitKernel (see the node-editor plan's §1.4) is what computes the
   real hit result once Phase 3 wires this into a screen; this namespace
   only needs a hit CLASSIFICATION ({:target :node/:pin/:canvas ...}),
   so it is fully testable without any presentation/UI dependency.

   Every fn is pure: state in, state (+ sometimes an :action) out. No
   atoms, no mutation -- the caller (a screen's reactive state) owns
   wherever this state actually lives."
  )

(def idle {:mode :idle})

(defn on-down
  "state, hit ({:target :node :nid} | {:target :pin :nid :pin (:in/:out)
   :key} | {:target :canvas}), x, y -> the new interaction state. A pin
   press starts a wire drag; a node-body press (not on a pin) starts a
   move; empty canvas starts a pan. idle is the only mode on-down is ever
   called from -- pressing again mid-drag is a caller bug, not something
   this state machine needs to defend against (the presentation runtime
   already gives one :down per press via pointer capture, see runtime.clj's
   own :pointer-capture handling)."
  [state hit x y]
  (case (:target hit)
    :pin {:mode :dragging-wire :from-nid (:nid hit) :from-pin (:pin hit) :from-key (:key hit)
         :cur-x x :cur-y y}
    :node {:mode :dragging-node :nid (:nid hit) :start-x x :start-y y :cur-x x :cur-y y}
    :canvas {:mode :panning :start-x x :start-y y :cur-x x :cur-y y}
    (assoc state :mode :idle)))

(defn on-move
  "state, x, y -> state with its tracked cursor position updated. A
   no-op (state returned unchanged) in :idle -- move only matters while
   a drag is active."
  [state x y]
  (if (= :idle (:mode state))
    state
    (assoc state :cur-x x :cur-y y)))

(defn on-up
  "state, hit (the target under the pointer AT RELEASE), x, y -> {:state
   idle :action action-or-nil}. Always returns to idle -- a release
   always ends whatever drag was in progress, successfully or not."
  [state hit x y]
  (let [action
        (case (:mode state)
          :dragging-node
          {:kind :move-node :nid (:nid state)
           :dx (- x (:start-x state)) :dy (- y (:start-y state))}

          :dragging-wire
          (if (and (= :pin (:target hit)) (not= (:from-pin state) (:pin hit)))
            {:kind :connect-wire
             :from-nid (:from-nid state) :from-pin (:from-pin state) :from-key (:from-key state)
             :to-nid (:nid hit) :to-pin (:pin hit) :to-key (:key hit)}
            {:kind :cancel-wire})

          :panning
          {:kind :pan-viewport :dx (- x (:start-x state)) :dy (- y (:start-y state))}

          nil)]
    {:state idle :action action}))

(defn dragging? [state] (not= :idle (:mode state)))
