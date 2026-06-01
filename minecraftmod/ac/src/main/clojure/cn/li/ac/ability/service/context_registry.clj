(ns cn.li.ac.ability.service.context-registry
  "Business-state facade for context lifecycle/state APIs.

  This namespace is the migration target for context business operations,
  while context-dispatcher keeps transport/router concerns."
  (:require [cn.li.ac.ability.service.context-dispatcher :as dispatcher]))

(def STATUS-CONSTRUCTED dispatcher/STATUS-CONSTRUCTED)
(def STATUS-ALIVE dispatcher/STATUS-ALIVE)
(def STATUS-TERMINATED dispatcher/STATUS-TERMINATED)

(def active-context? dispatcher/active-context?)
(def active-contexts dispatcher/active-contexts)
(def clear-owner-contexts! dispatcher/clear-owner-contexts!)
(def clear-session-contexts! dispatcher/clear-session-contexts!)
(def context-owned-by? dispatcher/context-owned-by?)
(def get-all-contexts dispatcher/get-all-contexts)
(def get-all-contexts-for-player dispatcher/get-all-contexts-for-player)
(def get-context dispatcher/get-context)
(def lifecycle-counters-snapshot (constantly {}))
(def new-context dispatcher/new-context)
(def new-server-context dispatcher/new-server-context)
(def register-context! dispatcher/register-context!)
(def remove-context! dispatcher/remove-context!)
(def reset-contexts-for-test! dispatcher/reset-contexts-for-test!)
(def snapshot-context-registry dispatcher/snapshot-context-registry)
(def start-context! dispatcher/start-context!)
(def start-server-context! dispatcher/start-server-context!)
(def terminate-context! dispatcher/terminate-context!)
(def transition-to-alive! dispatcher/transition-to-alive!)
(def update-context! dispatcher/update-context!)
(def update-context-owned! dispatcher/update-context-owned!)
(def abort-all-contexts-for-player! dispatcher/abort-all-contexts-for-player!)
