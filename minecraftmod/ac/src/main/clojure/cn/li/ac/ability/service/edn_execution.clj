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
            [cn.li.ac.ability.service.edn-catalog :as catalog]))

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
        result (vm/execute! program execution-frame host 0
                            {:owner owner
                             :ability-id ability-id
                             :phase (phase-of intent)
                             :activation-seed (long (or (:activation-seed intent) 0))
                             :server-tick (long (or (:server-tick intent) 0))
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
                             :query-order query-order
                             :results* results*
                             :latches* latches*
                             :slots* slots*})]
    (assoc result
           :schema-version 2
           :ability-id ability-id
           :owner owner
           :vfx-signals (vec (:vfx result))
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
