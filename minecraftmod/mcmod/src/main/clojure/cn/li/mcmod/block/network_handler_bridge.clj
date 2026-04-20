(ns cn.li.mcmod.block.network-handler-bridge
  "Platform-neutral helpers used by generated network handlers.")

(defonce ^:private helper-fns
  (atom {:get-world (fn [_] nil)
         :get-tile-at (fn [_ _] nil)}))

(defn register-helper-fns!
  [{:keys [get-world get-tile-at]}]
  (swap! helper-fns merge
         (cond-> {}
           get-world (assoc :get-world get-world)
           get-tile-at (assoc :get-tile-at get-tile-at)))
  nil)

(defn get-world
  [player]
  ((:get-world @helper-fns) player))

(defn get-tile-at
  [world payload]
  ((:get-tile-at @helper-fns) world payload))