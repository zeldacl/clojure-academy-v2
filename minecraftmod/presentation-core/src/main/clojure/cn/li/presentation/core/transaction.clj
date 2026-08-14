(ns cn.li.presentation.core.transaction)

(defn scheduler
  "Creates a transaction coordinator. Nested mutations flush exactly once."
  [flush!]
  {:depth (atom 0) :pending (atom false) :flush! flush!})

(defn transact! [coordinator mutation!]
  (swap! (:depth coordinator) inc)
  (try
    (mutation!)
    (reset! (:pending coordinator) true)
    (finally
      (when (zero? (swap! (:depth coordinator) dec))
        (when (compare-and-set! (:pending coordinator) true false)
          ((:flush! coordinator)))))))
