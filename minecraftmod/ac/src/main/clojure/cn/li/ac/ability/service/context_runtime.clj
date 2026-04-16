(ns cn.li.ac.ability.service.context-runtime
  "Server-side handlers for context key input lifecycle.

  This namespace executes skill runtime callbacks behind context messages:
  - MSG-SKILL-KEY-DOWN
  - MSG-SKILL-KEY-TICK
  - MSG-SKILL-KEY-UP
  - MSG-SKILL-KEY-ABORT

  It keeps input state transitions strict to avoid duplicated lifecycle calls."
  (:require [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.skill :as skill]
            [cn.li.ac.ability.skill-runtime :as skill-rt]
            [cn.li.ac.ability.event :as evt]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.service.resource :as res]
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.mcmod.util.log :as log]))

(def INPUT-IDLE :idle)
(def INPUT-ACTIVE :active)
(def INPUT-RELEASED :released)
(def INPUT-ABORTED :aborted)

(defn- event-payload [ctx-map payload]
  {:ctx-id (:id ctx-map)
   :server-id (:server-id ctx-map)
   :player-id (:player-uuid ctx-map)
  :player (:player payload)
   :skill-id (:skill-id ctx-map)
   :payload payload})

(defn- safe-run-callback! [f arg]
  (when (fn? f)
    (try
      (f arg)
      (catch Exception e
        (log/warn "Context runtime callback failed" (ex-message e))))))

(defn- dispatch-skill-callback! [ctx-map cb-key event-type payload]
  (when-let [spec (skill/get-skill (:skill-id ctx-map))]
    (let [evt* (event-payload ctx-map payload)
          f (get spec cb-key)]
      (cond
        (fn? f)
        (safe-run-callback! f evt*)

        (skill-rt/can-handle? spec)
        (skill-rt/dispatch! spec cb-key evt*)

        :else
        nil)))
  (evt/fire-ability-event!
   {:event/type event-type
    :event/side :server
    :ctx-id (:id ctx-map)
    :server-id (:server-id ctx-map)
    :player-id (:player-uuid ctx-map)
    :skill-id (:skill-id ctx-map)
    :payload payload}))

(defn- set-input-state! [ctx-id state]
  (ctx/update-context! ctx-id assoc :input-state state)
  (ctx/get-context ctx-id))

(defn- consume-runtime-resource!
  [ctx-map]
  (let [uuid (:player-uuid ctx-map)
        state (ps/get-player-state uuid)
        spec (skill/get-skill (:skill-id ctx-map))
      cp (* cfg/*runtime-cp-consume-per-tick*
              (double (or (:cp-consume-speed spec) 1.0)))
        overload (* cfg/*runtime-overload-per-tick*
                    (double (or (:overload-consume-speed spec) 1.0)))]
    (if-not state
      false
      (let [{:keys [data success? events]} (res/perform-resource
                                            (:resource-data state)
                                            uuid
                                            overload
                                            cp
                                            false)]
        (when success?
          (ps/update-resource-data! uuid (constantly data))
          (doseq [e events] (evt/fire-ability-event! e)))
        (boolean success?)))))

(defn- apply-main-cooldown!
  [ctx-map]
  (let [uuid (:player-uuid ctx-map)
        spec (skill/get-skill (:skill-id ctx-map))
        ctrl-id (or (:ctrl-id spec) (:skill-id ctx-map))
        cooldown-ticks (max 1 (int (or (:cooldown-ticks spec)
                 cfg/*runtime-main-cooldown-ticks*)))]
    (ps/update-cooldown-data! uuid cd/set-main-cooldown ctrl-id cooldown-ticks)))

(defn- in-main-cooldown?
  [ctx-map]
  (let [uuid (:player-uuid ctx-map)
        state (ps/get-player-state uuid)
        spec (skill/get-skill (:skill-id ctx-map))
        ctrl-id (or (:ctrl-id spec) (:skill-id ctx-map))]
    (and state
         (cd/in-main-cooldown? (:cooldown-data state) ctrl-id))))

(defn- use-default-runtime-consume?
  [ctx-map]
  (let [spec (skill/get-skill (:skill-id ctx-map))]
    (or (nil? (:cost spec))
        (true? (get-in spec [:cost :use-runtime-consume?])))))

(defn handle-key-down!
  ([ctx-id payload]
   (handle-key-down! ctx-id payload nil))
  ([ctx-id payload terminate-fn]
   (when-let [ctx-map (ctx/get-context ctx-id)]
     (when (and (= (:status ctx-map) ctx/STATUS-ALIVE)
                (or (nil? (:input-state ctx-map))
                    (= (:input-state ctx-map) INPUT-IDLE)))
       (if (in-main-cooldown? ctx-map)
         (do
           (ctx/terminate-context! ctx-id terminate-fn)
           false)
         (let [new-ctx (set-input-state! ctx-id INPUT-ACTIVE)]
           (dispatch-skill-callback! new-ctx :on-key-down evt/EVT-CONTEXT-KEY-DOWN payload)
           true))))))

(defn handle-key-tick!
  ([ctx-id payload]
   (handle-key-tick! ctx-id payload nil))
  ([ctx-id payload terminate-fn]
   (when-let [ctx-map (ctx/get-context ctx-id)]
     (when (and (= (:status ctx-map) ctx/STATUS-ALIVE)
                (= (:input-state ctx-map) INPUT-ACTIVE))
       (if (or (not (use-default-runtime-consume? ctx-map))
               (consume-runtime-resource! ctx-map))
         (do
           (dispatch-skill-callback! ctx-map :on-key-tick evt/EVT-CONTEXT-KEY-TICK payload)
           true)
         (do
           (dispatch-skill-callback! ctx-map :on-key-abort evt/EVT-CONTEXT-KEY-ABORT payload)
           (ctx/terminate-context! ctx-id terminate-fn)
           false))))))

(defn handle-key-up!
  ([ctx-id payload]
   (handle-key-up! ctx-id payload nil))
  ([ctx-id payload terminate-fn]
   (when-let [ctx-map (ctx/get-context ctx-id)]
     (when (and (= (:status ctx-map) ctx/STATUS-ALIVE)
                (= (:input-state ctx-map) INPUT-ACTIVE))
       (let [released-ctx (set-input-state! ctx-id INPUT-RELEASED)]
         (dispatch-skill-callback! released-ctx :on-key-up evt/EVT-CONTEXT-KEY-UP payload)
         (let [latest-ctx (ctx/get-context ctx-id)
               spec (skill/get-skill (:skill-id latest-ctx))
               skip-default-cooldown?
               (or (boolean (get-in latest-ctx [:skill-state :skip-default-cooldown]))
                   (= :manual (get-in spec [:cooldown :mode])))]
           (when-not skip-default-cooldown?
             (apply-main-cooldown! released-ctx)))
         (ctx/terminate-context! ctx-id terminate-fn)
         true)))))

(defn handle-key-abort!
  ([ctx-id payload]
   (handle-key-abort! ctx-id payload nil))
  ([ctx-id payload terminate-fn]
   (when-let [ctx-map (ctx/get-context ctx-id)]
     (when (not= (:status ctx-map) ctx/STATUS-TERMINATED)
       (let [aborted-ctx (set-input-state! ctx-id INPUT-ABORTED)]
         (dispatch-skill-callback! aborted-ctx :on-key-abort evt/EVT-CONTEXT-KEY-ABORT payload)
         (ctx/terminate-context! ctx-id terminate-fn)
         true)))))
