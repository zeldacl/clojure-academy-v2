(ns cn.li.ac.ability.service.edn-execution
  "AC composition boundary for compiled EDN abilities.

   This namespace deliberately contains no skill implementation.  It selects
   a compiled catalog entry, supplies neutral context, executes the generic
   combat VM, and returns the neutral result to the existing AC publication
   seam.  Pending entries never reach this function." 
  (:require [cn.li.combat.frame :as frame]
            [cn.li.combat.host :as host]
            [cn.li.combat.vm :as vm]
            [cn.li.mcmod.runtime.capabilities :as capabilities]
            [cn.li.mcmod.runtime.vfx-contract :as vfx-contract]
            [cn.li.ac.ability.service.edn-catalog :as catalog]))

(defn- normalize-vfx-signal
  "Translate Combat Core's neutral effect request into the network/client VFX
  signal ABI.  The VM deliberately stores only operation/payload data; AC is
  the composition boundary that supplies owner, stable sequence and seed.
  Keeping this here prevents every EDN ability from duplicating that envelope.
  "
  [owner ability-id execution-context signal]
  (let [operation (:operation signal)
        op (case operation
             :spawn :spawn
             :update :signal
             :destroy :destroy
             (throw (ex-info "unsupported EDN VFX operation"
                             {:operation operation :signal signal})))
        event-seq (long (or (:event-seq execution-context)
                            (:server-tick execution-context)
                            1))
        instance-key (or (:instance-key signal)
                         [ability-id :activation (:effect-id signal)])
        world-id (or (get-in execution-context [:from :world/id])
                     (get-in execution-context [:context :world-id]))]
    (vfx-contract/signal
      {:op op
       :effect-id (:effect-id signal)
       :instance-key instance-key
       :owner owner
       :world-id world-id
       :event-seq event-seq
       :seed (long (or (:activation-seed execution-context) 0))
       :event (when (#{:spawn :update} operation) operation)
       :params (or (:payload signal) {})})))

(defn- phase-of [intent]
  (or (:phase intent)
      (case (:action intent)
        :start :start
        :pulse :pulse
        :release :release
        :abort :abort
        :event :events
        :start)))

(defn execute!
  [ability-id owner intent]
  (catalog/require-available ability-id)
  (let [ability (get-in (catalog/catalog) [:combat :abilities ability-id])
        program (:compiled-program ability)
        slot-counts (:slot-counts ability)
        execution-frame (frame/create-frame slot-counts)
        capability-state (capabilities/snapshot)
        host (host/build-host-table-from-capabilities capability-state)
        query-order (vec (sort (keys (:queries capability-state))))
        results* (volatile! {})
        latches* (volatile! (set (or (:latches intent) #{})))
        slots* (volatile! {})
        rng-counter* (volatile! 0)
        execution-context
        {:owner owner
         :ability-id ability-id
         :phase (phase-of intent)
         :activation-seed (long (or (:activation-seed intent) 0))
         :server-tick (long (or (:server-tick intent) 0))
         :event-seq (long (or (:event-seq intent) 1))
         :event (:event intent)
         :intent intent
         :context (merge {:owner owner
                          :ability-id ability-id}
                         (:context intent))
         :session-state (:session-state intent)
         ;; AC supplies an immutable activation snapshot
         ;; on the neutral intent.  The EDN declaration is
         ;; the fallback only for fields with no runtime
         ;; config binding yet; no live config lookup is
         ;; performed from Combat Core.
         :params (or (:parameter-snapshot intent)
                     (:parameters intent)
                     (:parameters ability)
                     {})
         ;; Schema v2 caster facade (design C) and
         ;; materialized tunable curves (design B):
         ;; AC assembles both once per activation and
         ;; hands them through unchanged. Combat Core
         ;; never reads AC's state shape to build them.
         :from (or (:from intent) {})
         :tunables (or (:tunables intent) {})
         :costs (or (:costs intent) (:costs ability))
         :progression (or (:progression intent) (:progression ability))
         :cooldown (or (:cooldown intent) (:cooldown ability))
         :invariants (or (:invariants intent) (:invariants ability))
         :query-order query-order
         :results* results*
         :latches* latches*
         :slots* slots*
         :rng-counter* rng-counter*}
        result (vm/execute! program execution-frame host 0
                            execution-context)]
    (assoc result
           :schema-version 2
           :ability-id ability-id
           :owner owner
           :vfx-signals (mapv #(normalize-vfx-signal owner execution-context %)
                              (:vfx result))
           :actions (cond-> (vec (:actions result))
                      (seq @latches*)
                      (conj {:type :session-latches :latches @latches*}))
           :events (vec (:events result))
           :query-results @results*
           :status (if (= :finished (:status result)) :accepted (:status result)))))

(defn commit-actions!
  "Commit neutral action requests at the AC finalization boundary.

  This is intentionally separate from `execute!`: VM execution only builds
  bounded requests, while platform mutation happens once, after the result is
  accepted and in the same ordering seam as the existing result publication."
  [owner actions action-handlers]
  (mapv (fn [action]
          (let [capability (:capability action)
                handler (get action-handlers capability)
                request (assoc action :owner owner)]
            (if (and (= :block/set capability)
                     (not (and (vector? (:expected-block-ids request))
                               (seq (:expected-block-ids request))
                               (<= (count (:expected-block-ids request)) 8)
                               (string? (:block-id request))
                               (let [position (:position request)
                                     point (if (and (map? position)
                                                    (vector? (:vec3 position)))
                                             (:vec3 position)
                                             position)]
                                 (and (vector? point)
                                      (= 3 (count point))
                                      (every? number? point))))))
              {:status :rejected
               :capability capability
               :reason :invalid-bounded-block-set
               :request request}
              (if (not handler)
                {:status :unhandled :capability capability :request request}
                (try
                  {:status :committed
                   :capability capability
                   :result (handler request)}
                  (catch Throwable throwable
                    {:status :failed
                     :capability capability
                     :message (ex-message throwable)}))))))
        actions))
