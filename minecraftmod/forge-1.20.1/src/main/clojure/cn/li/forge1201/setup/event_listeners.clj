(ns cn.li.forge1201.setup.event-listeners
  "Forge common EventBus listener registration for gameplay events."
  (:require [cn.li.forge1201.setup.event-registration :as event-registration]))

(defn register-common-event-listeners!
  []
  (event-registration/register-common-event-listeners!)
  nil)
