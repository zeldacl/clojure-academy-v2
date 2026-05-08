(ns cn.li.mc1201.integration.event-feedback
  "Shared event feedback helpers (server-side player messages)."
  (:import [net.minecraft.network.chat Component]))

(defn feedback-component
  [{:keys [type key args text]}]
  (case type
    :translatable (Component/translatable (str key) (into-array Object (map str (or args []))))
    :literal (Component/literal (str text))
    nil))

(defn emit-feedback!
  [event-data ret]
  (let [world (:world event-data)
        player (:player event-data)
        messages (when (map? ret) (:messages ret))]
    (when (and world player (not (.isClientSide world)) (seq messages))
      (doseq [m messages]
        (when-let [c (feedback-component m)]
          (.sendSystemMessage player c))))))
