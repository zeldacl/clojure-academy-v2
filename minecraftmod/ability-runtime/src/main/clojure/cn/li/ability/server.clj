(ns cn.li.ability.server
  "Server-side runtime shell. The implementation intentionally owns no
   global state; callers create one instance per logical server runtime."
  (:require [cn.li.ability.limits :as limits]
            [cn.li.ability.continuation :as continuation]))

(defn create-runtime
  [{:keys [bundle minecraft-ports server-epoch limits continuation-execute!]}]
  (when-not (map? bundle)
    (throw (ex-info "server runtime requires a compiled bundle" {})))
  (when-not (map? minecraft-ports)
    (throw (ex-info "server runtime requires Minecraft ports" {})))
  {:side :server
   :bundle bundle
   :minecraft-ports minecraft-ports
   :server-epoch (long (or server-epoch 0))
   :limits (limits/validate limits)
   ;; WorldShard and PlayerRuntime instances are installed by the bootstrap
   ;; on the server tick thread.  They are intentionally instance-local.
   :world-shards {}})


(defn schedule! [runtime task]
  (continuation/schedule! (:continuations runtime) task))

(defn tick-owner! [runtime owner]
  (continuation/tick-owner! (:continuations runtime) owner))

(defn cancel-owner! [runtime owner]
  (continuation/cancel-owner! (:continuations runtime) owner))

(defn cancel-all! [runtime]
  (continuation/cancel-all! (:continuations runtime)))


