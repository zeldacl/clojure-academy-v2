(ns cn.li.combat.damage
  "Deterministic, platform-neutral damage pipeline.

   Providers contribute pure transforms.  The pipeline never applies a
   Minecraft side effect; it returns a final request or a cancellation marker
   for the AC world-effect interpreter.")

(defn damage-request
  [{:keys [source target base type components tags metadata] :as request}]
  (when-not (and source (number? base) type)
    (throw (ex-info "invalid damage request"
                    {:request request :required #{:source :base :type}})))
  {:source source
   :target target
   :base (double base)
   :type type
   :components (or components {:direct (double base)})
   :tags (or tags #{})
   :metadata (or metadata {})})

(defn compile-pipeline
  "Sort pure damage transforms once.  Each entry contains a stable provider,
   ability and node identity plus a function `[request context] -> request`."
  [transforms]
  (->> transforms
       (filter #(ifn? (:run %)))
       (sort-by (juxt #(long (or (:priority %) 0))
                      #(str (:provider-id %))
                      #(str (:ability-id %))
                      #(str (:node-id %))))
       vec))

(defn apply-pipeline
  [pipeline request context]
  (let [result (reduce (fn [result {:keys [run]}]
                         (if (or (:cancelled? result) (nil? result))
                           result
                           (run result context)))
                       (damage-request request)
                       pipeline)
        resource-cost (get-in result [:metadata :resource-cost])
        state-patch (vec (concat (or (:state-patch result) [])
                                 (for [[resource amount] resource-cost]
                                   [:resource resource (double amount)])))]
    ;; Providers remain pure: resource consumption is represented as a
    ;; neutral StatePatch for the AC composition root to commit atomically.
    (assoc result :state-patch state-patch)))
