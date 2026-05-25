(ns cn.li.fabric1201.runtime.named-position-store
  "Fabric thin adapter for the neutral named-position-store protocol.
  Delegates all MC/NBT logic to mc1201 named-position-store-core; obtains server
  reference from Fabric server-context."
  (:require [cn.li.mcmod.platform.named-position-store :as position-store]
            [cn.li.mc1201.runtime.named-position-store-core :as store-core]
            [cn.li.fabric1201.adapter.server-context :as server-ctx]
            [cn.li.mcmod.util.log :as log]))

(defn fabric-named-position-store []
  (store-core/create-named-position-store server-ctx/get-server))

(defn install-named-position-store! []
  (alter-var-root #'position-store/*named-position-store*
                  (constantly (fabric-named-position-store)))
  (log/info "Fabric named position store installed"))
