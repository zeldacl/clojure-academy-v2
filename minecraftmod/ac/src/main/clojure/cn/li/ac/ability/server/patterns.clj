(ns cn.li.ac.ability.server.patterns
  "Skill input patterns (press/hold/toggle) implemented as pure(ish) interpreters.

  Each handler is called server-side by cn.li.ac.ability.server.dispatch with an
  event map (from context-runtime) and the skill spec map."
  (:require [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.mcmod.util.log :as log]))

(defn- ensure-skill-state!
  [ctx-id]
  (ctx/update-context! ctx-id update :skill-state (fnil identity {}))
  (ctx/get-context ctx-id))

(defn- call-action!
  [spec k evt]
  (when-let [f (get-in spec [:actions k])]
    (when (fn? f)
      (f evt))))

(defn- run-stage-ops!
  [spec evt stage]
  (effect/run-stage! spec evt stage))

(defn- emit-fx!
  [spec evt stage]
  (skill-effects/emit-fx! spec evt stage))

(defn- apply-cost!
  [spec stage evt]
  (skill-effects/apply-cost! spec stage evt))

(defn- cost-fail!
  [spec evt stage]
  (call-action! spec :cost-fail! (assoc evt :cost-stage stage)))

(defn instant-on-down!
  [spec {:keys [ctx-id] :as evt}]
  (try
    (let [cost-ok? (apply-cost! spec :down evt)
          evt* (assoc evt :cost-ok? cost-ok?)]
      (call-action! spec :perform! evt*)
      (run-stage-ops! spec evt* :perform)
      (when cost-ok?
        (emit-fx! spec evt* :perform)
        (skill-effects/gain-exp! spec evt*)
        (skill-effects/apply-cooldown! spec evt*))
      (when-not cost-ok?
        (cost-fail! spec evt :down)))
    (catch Exception e
      (log/warn "instant-on-down failed" (ex-message e))))
  ;; end immediately; context-runtime may still call key-up but ctx is terminated.
  (ctx/terminate-context! ctx-id nil))

(defn hold-charge-release-on-down!
  [spec {:keys [ctx-id] :as evt}]
  (ensure-skill-state! ctx-id)
  (let [init (merge {:charge-ticks 0}
                    (or (:state spec) {}))]
    (ctx/update-context! ctx-id assoc :skill-state init))
  (run-stage-ops! spec evt :down)
  (emit-fx! spec evt :start))

(defn hold-charge-release-on-tick!
  [spec {:keys [ctx-id] :as evt}]
  (if-not (apply-cost! spec :tick evt)
    (do
      (cost-fail! spec evt :tick)
      (ctx/terminate-context! ctx-id nil))
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [max-charge (long (or (get-in spec [:state :max-charge]) Long/MAX_VALUE))
            prev (long (or (get-in ctx [:skill-state :charge-ticks]) 0))
            next (min max-charge (inc prev))]
        (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] next)
        (call-action! spec :tick! (assoc evt :charge-ticks next))
        (run-stage-ops! spec (assoc evt :charge-ticks next) :tick)
        (emit-fx! spec (assoc evt :charge-ticks next) :update)))))

(defn hold-charge-release-on-up!
  [spec {:keys [ctx-id] :as evt}]
  (if-not (apply-cost! spec :up evt)
    (cost-fail! spec evt :up)
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [charge (long (or (get-in ctx [:skill-state :charge-ticks]) 0))
            evt* (assoc evt :charge-ticks charge)]
        (call-action! spec :perform! evt*)
        (run-stage-ops! spec evt* :perform)
        (emit-fx! spec evt* :perform)
        (emit-fx! spec evt* :end)
        (skill-effects/gain-exp! spec evt*)
        (skill-effects/apply-cooldown! spec evt*)))))

(defn hold-charge-release-on-abort!
  [spec {:keys [ctx-id] :as evt}]
  (try
    (call-action! spec :abort! evt)
    (run-stage-ops! spec evt :abort)
    (emit-fx! spec evt :end)
    (finally
      (ctx/update-context! ctx-id dissoc :skill-state))))

(defn toggle-on-down!
  [spec {:keys [ctx-id skill-id] :as evt}]
  (ensure-skill-state! ctx-id)
  (let [ctx (ctx/get-context ctx-id)
        active? (toggle/is-toggle-active? ctx skill-id)]
    (if active?
      (do
        (toggle/remove-toggle! ctx-id skill-id)
        (call-action! spec :deactivate! evt)
        (emit-fx! spec evt :end))
      (let [cost-ok? (apply-cost! spec :down evt)]
        (if-not cost-ok?
          (cost-fail! spec evt :down)
          (do
            (toggle/activate-toggle! ctx-id skill-id)
            (call-action! spec :activate! evt)
            (run-stage-ops! spec evt :down)
            (emit-fx! spec evt :start)))))))

(defn toggle-on-tick!
  [spec {:keys [ctx-id skill-id] :as evt}]
  (when-let [ctx (ctx/get-context ctx-id)]
    (when (toggle/is-toggle-active? ctx skill-id)
      (let [cost-ok? (apply-cost! spec :tick evt)]
        (if-not cost-ok?
          (do
            (toggle/remove-toggle! ctx-id skill-id)
            (cost-fail! spec evt :tick)
            (call-action! spec :deactivate! evt)
            (emit-fx! spec evt :end))
          (do
            (toggle/update-toggle-tick! ctx-id skill-id)
            (call-action! spec :tick! evt)
            (run-stage-ops! spec evt :tick)
            (emit-fx! spec evt :update)))))))

(defn toggle-on-up!
  [_spec _evt]
  nil)

(defn toggle-on-abort!
  [spec {:keys [ctx-id skill-id] :as evt}]
  (try
    (toggle/remove-toggle! ctx-id skill-id)
    (call-action! spec :abort! evt)
    (run-stage-ops! spec evt :abort)
    (emit-fx! spec evt :end)
    (finally
      (ctx/update-context! ctx-id dissoc :skill-state))))

(defn hold-channel-on-down!
  [spec {:keys [ctx-id] :as evt}]
  (ensure-skill-state! ctx-id)
  (let [cost-ok? (apply-cost! spec :down evt)]
    (if cost-ok?
      (do
        (call-action! spec :down! evt)
        (run-stage-ops! spec evt :down)
        (emit-fx! spec evt :start))
      (cost-fail! spec evt :down))))

(defn hold-channel-on-tick!
  [spec {:keys [ctx-id] :as evt}]
  (if-not (apply-cost! spec :tick evt)
    (do
      (cost-fail! spec evt :tick)
      (ctx/terminate-context! ctx-id nil))
    (do
      (call-action! spec :tick! evt)
      (run-stage-ops! spec evt :tick)
      (emit-fx! spec evt :update))))

(defn hold-channel-on-up!
  [spec evt]
  (when (apply-cost! spec :up evt)
    (call-action! spec :up! evt)
    (run-stage-ops! spec evt :up))
  (emit-fx! spec evt :end))

(defn hold-channel-on-abort!
  [spec evt]
  (call-action! spec :abort! evt)
  (run-stage-ops! spec evt :abort)
  (emit-fx! spec evt :end))

(defn- init-stage-state!
  [ctx-id]
  (let [base {:hold-ticks 0 :performed? false}
        merged (merge base (or (:skill-state (ctx/get-context ctx-id)) {}))]
    (ctx/update-context! ctx-id assoc :skill-state merged)))

(defn- next-hold-ticks!
  [ctx-id]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (let [current (long (or (get-in ctx-data [:skill-state :hold-ticks]) 0))
          next (inc current)]
      (ctx/update-context! ctx-id assoc-in [:skill-state :hold-ticks] next)
      next)))

(defn- stage-pattern-on-down!
  [spec {:keys [ctx-id] :as evt}]
  (init-stage-state! ctx-id)
  (let [cost-ok? (apply-cost! spec :down evt)]
    (call-action! spec :down! (assoc evt :cost-ok? cost-ok?))
    (when cost-ok?
      (run-stage-ops! spec (assoc evt :cost-ok? cost-ok?) :down))
    (when cost-ok?
      (emit-fx! spec evt :start))
    (when-not cost-ok?
      (cost-fail! spec evt :down))))

(defn- stage-pattern-on-tick!
  [spec {:keys [ctx-id] :as evt}]
  (let [hold-ticks (or (next-hold-ticks! ctx-id) 0)
        evt* (assoc evt :hold-ticks hold-ticks)
        cost-ok? (apply-cost! spec :tick evt*)]
    (call-action! spec :tick! (assoc evt* :cost-ok? cost-ok?))
    (when cost-ok?
      (run-stage-ops! spec (assoc evt* :cost-ok? cost-ok?) :tick))
    (when cost-ok?
      (emit-fx! spec evt* :update))
    (when-not cost-ok?
      (cost-fail! spec evt* :tick))))

(defn- stage-pattern-on-up!
  [spec {:keys [ctx-id] :as evt} perform?]
  (let [hold-ticks (long (or (get-in (ctx/get-context ctx-id) [:skill-state :hold-ticks]) 0))
        evt* (assoc evt :hold-ticks hold-ticks)
        cost-ok? (apply-cost! spec :up evt*)]
    (call-action! spec :up! (assoc evt* :cost-ok? cost-ok?))
    (when (and perform? cost-ok?)
      (run-stage-ops! spec evt* :perform)
      (emit-fx! spec evt* :perform)
      (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] true))
    (emit-fx! spec evt* :end)
    (when cost-ok?
      (skill-effects/gain-exp! spec evt*)
      (skill-effects/apply-cooldown! spec evt*))
    (when-not cost-ok?
      (cost-fail! spec evt* :up))))

(defn- stage-pattern-on-abort!
  [spec evt]
  (call-action! spec :abort! evt)
  (emit-fx! spec evt :end))

(defn release-cast-on-down!
  [spec evt]
  (stage-pattern-on-down! spec evt))

(defn release-cast-on-tick!
  [spec evt]
  (stage-pattern-on-tick! spec evt))

(defn release-cast-on-up!
  [spec evt]
  (stage-pattern-on-up! spec evt true))

(defn release-cast-on-abort!
  [spec evt]
  (stage-pattern-on-abort! spec evt))

(defn charge-window-on-down!
  [spec evt]
  (stage-pattern-on-down! spec evt))

(defn charge-window-on-tick!
  [spec evt]
  (stage-pattern-on-tick! spec evt))

(defn charge-window-on-up!
  [spec evt]
  (stage-pattern-on-up! spec evt true))

(defn charge-window-on-abort!
  [spec evt]
  (stage-pattern-on-abort! spec evt))

(defn handlers
  "Return pattern handler map for a spec."
  [spec]
  (case (:pattern spec)
    :instant
    {:on-key-down instant-on-down!
     :on-key-tick (fn [_s _e] nil)
     :on-key-up   (fn [_s _e] nil)
     :on-key-abort (fn [_s {:keys [ctx-id] :as evt}]
                     (emit-fx! spec (assoc evt :ctx-id ctx-id) :end))}

    :hold-charge-release
    {:on-key-down hold-charge-release-on-down!
     :on-key-tick hold-charge-release-on-tick!
     :on-key-up hold-charge-release-on-up!
     :on-key-abort hold-charge-release-on-abort!}

    :toggle
    {:on-key-down toggle-on-down!
     :on-key-tick toggle-on-tick!
     :on-key-up toggle-on-up!
     :on-key-abort toggle-on-abort!}

    :hold-channel
    {:on-key-down hold-channel-on-down!
     :on-key-tick hold-channel-on-tick!
     :on-key-up hold-channel-on-up!
     :on-key-abort hold-channel-on-abort!}

    :release-cast
    {:on-key-down release-cast-on-down!
     :on-key-tick release-cast-on-tick!
     :on-key-up release-cast-on-up!
     :on-key-abort release-cast-on-abort!}

    :charge-window
    {:on-key-down charge-window-on-down!
     :on-key-tick charge-window-on-tick!
     :on-key-up charge-window-on-up!
     :on-key-abort charge-window-on-abort!}

    :passive
    {:on-key-down (fn [_s _e] nil)
     :on-key-tick (fn [_s _e] nil)
     :on-key-up (fn [_s _e] nil)
     :on-key-abort (fn [_s _e] nil)}

    ;; default: no pattern
    nil))

