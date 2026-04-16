(ns cn.li.ac.ability.patterns
  "Skill input patterns (press/hold/toggle) implemented as pure(ish) interpreters.

  Each handler is called server-side by cn.li.ac.ability.skill-runtime with an
  event map (from context-runtime) and the skill spec map."
  (:require [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.config :as cfg]
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

(defn- resolve-cost-val
  [v evt]
  (cond
    (number? v) (double v)
    (fn? v) (double (or (v evt) 0.0))
    :else 0.0))

(defn- runtime-scaled-cost
  [cost-spec]
  (let [cp-speed (double (or (:cp-speed cost-spec) 1.0))
        overload-speed (double (or (:overload-speed cost-spec) 1.0))]
    {:cp (* cfg/*runtime-cp-consume-per-tick* cp-speed)
     :overload (* cfg/*runtime-overload-per-tick* overload-speed)}))

(defn- apply-cost!
  [spec stage evt]
  (let [cost-spec (get-in spec [:cost stage])]
    (if-not (map? cost-spec)
      true
      (let [computed (if (= :runtime-speed (:mode cost-spec))
                       (runtime-scaled-cost cost-spec)
                       cost-spec)
            cp (resolve-cost-val (:cp computed) evt)
            overload (resolve-cost-val (:overload computed) evt)
            creative-raw (:creative? computed)
            creative? (boolean (if (fn? creative-raw) (creative-raw evt) creative-raw))]
        (if (and (zero? cp) (zero? overload))
          true
          (let [{:keys [success?]} (skill-effects/perform-resource! (:player-id evt) overload cp creative?)]
            (boolean success?)))))))

(defn instant-on-down!
  [spec {:keys [ctx-id] :as evt}]
  (try
    (let [cost-ok? (apply-cost! spec :down evt)
          evt* (assoc evt :cost-ok? cost-ok?)]
      (call-action! spec :perform! evt*)
      (when cost-ok?
        (fx/send! ctx-id (:fx spec) :perform evt*))
      (when-not cost-ok?
        (call-action! spec :cost-fail! (assoc evt :cost-stage :down))))
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
  (fx/send! ctx-id (:fx spec) :start evt))

(defn hold-charge-release-on-tick!
  [spec {:keys [ctx-id] :as evt}]
  (if-not (apply-cost! spec :tick evt)
    (do
      (call-action! spec :cost-fail! evt)
      (ctx/terminate-context! ctx-id nil))
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [max-charge (long (or (get-in spec [:state :max-charge]) Long/MAX_VALUE))
            prev (long (or (get-in ctx [:skill-state :charge-ticks]) 0))
            next (min max-charge (inc prev))]
        (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] next)
        (call-action! spec :tick! (assoc evt :charge-ticks next))
        (fx/send! ctx-id (:fx spec) :update (assoc evt :charge-ticks next))))))

(defn hold-charge-release-on-up!
  [spec {:keys [ctx-id] :as evt}]
  (if-not (apply-cost! spec :up evt)
    (call-action! spec :cost-fail! evt)
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [charge (long (or (get-in ctx [:skill-state :charge-ticks]) 0))
            evt* (assoc evt :charge-ticks charge)]
        (call-action! spec :perform! evt*)
        (fx/send! ctx-id (:fx spec) :perform evt*)
        (fx/send! ctx-id (:fx spec) :end evt*)))))

(defn hold-charge-release-on-abort!
  [spec {:keys [ctx-id] :as evt}]
  (try
    (call-action! spec :abort! evt)
    (fx/send! ctx-id (:fx spec) :end evt)
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
        (fx/send! ctx-id (:fx spec) :end evt))
      (let [cost-ok? (apply-cost! spec :down evt)]
        (if-not cost-ok?
          (call-action! spec :cost-fail! (assoc evt :cost-stage :down))
          (do
            (toggle/activate-toggle! ctx-id skill-id)
            (call-action! spec :activate! evt)
            (fx/send! ctx-id (:fx spec) :start evt)))))))

(defn toggle-on-tick!
  [spec {:keys [ctx-id skill-id] :as evt}]
  (when-let [ctx (ctx/get-context ctx-id)]
    (when (toggle/is-toggle-active? ctx skill-id)
      (let [cost-ok? (apply-cost! spec :tick evt)]
        (if-not cost-ok?
          (do
            (toggle/remove-toggle! ctx-id skill-id)
            (call-action! spec :cost-fail! (assoc evt :cost-stage :tick))
            (call-action! spec :deactivate! evt)
            (fx/send! ctx-id (:fx spec) :end evt))
          (do
            (toggle/update-toggle-tick! ctx-id skill-id)
            (call-action! spec :tick! evt)
            (fx/send! ctx-id (:fx spec) :update evt)))))))

(defn toggle-on-up!
  [_spec _evt]
  nil)

(defn toggle-on-abort!
  [spec {:keys [ctx-id skill-id] :as evt}]
  (try
    (toggle/remove-toggle! ctx-id skill-id)
    (call-action! spec :abort! evt)
    (fx/send! ctx-id (:fx spec) :end evt)
    (finally
      (ctx/update-context! ctx-id dissoc :skill-state))))

(defn hold-channel-on-down!
  [spec {:keys [ctx-id] :as evt}]
  (ensure-skill-state! ctx-id)
  (when (apply-cost! spec :down evt)
    (call-action! spec :down! evt))
  (fx/send! ctx-id (:fx spec) :start evt))

(defn hold-channel-on-tick!
  [spec {:keys [ctx-id] :as evt}]
  (if-not (apply-cost! spec :tick evt)
    (ctx/terminate-context! ctx-id nil)
    (do
      (call-action! spec :tick! evt)
      (fx/send! ctx-id (:fx spec) :update evt))))

(defn hold-channel-on-up!
  [spec {:keys [ctx-id] :as evt}]
  (when (apply-cost! spec :up evt)
    (call-action! spec :up! evt))
  (fx/send! ctx-id (:fx spec) :end evt))

(defn hold-channel-on-abort!
  [spec {:keys [ctx-id] :as evt}]
  (call-action! spec :abort! evt)
  (fx/send! ctx-id (:fx spec) :end evt))

(defn multi-stage-on-down!
  [spec evt]
  (let [cost-ok? (apply-cost! spec :down evt)]
    (call-action! spec :down! (assoc evt :cost-ok? cost-ok?))
    (when-not cost-ok?
      (call-action! spec :cost-fail! (assoc evt :cost-stage :down)))))

(defn multi-stage-on-tick!
  [spec evt]
  (let [cost-ok? (apply-cost! spec :tick evt)]
    (call-action! spec :tick! (assoc evt :cost-ok? cost-ok?))
    (when-not cost-ok?
      (call-action! spec :cost-fail! (assoc evt :cost-stage :tick)))))

(defn multi-stage-on-up!
  [spec evt]
  (let [cost-ok? (apply-cost! spec :up evt)]
    (call-action! spec :up! (assoc evt :cost-ok? cost-ok?))
    (when-not cost-ok?
      (call-action! spec :cost-fail! (assoc evt :cost-stage :up)))))

(defn multi-stage-on-abort!
  [spec evt]
  (call-action! spec :abort! evt))

(defn handlers
  "Return pattern handler map for a spec."
  [spec]
  (case (:pattern spec)
    :instant
    {:on-key-down instant-on-down!
     :on-key-tick (fn [_s _e] nil)
     :on-key-up   (fn [_s _e] nil)
     :on-key-abort (fn [_s {:keys [ctx-id] :as evt}]
                     (fx/send! ctx-id (:fx spec) :end evt))}

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

    :multi-stage
    {:on-key-down multi-stage-on-down!
     :on-key-tick multi-stage-on-tick!
     :on-key-up multi-stage-on-up!
     :on-key-abort multi-stage-on-abort!}

    ;; default: no pattern
    nil))

