(ns cn.li.ac.ability.client-executor
	"Phase C client-side execution/request facade for ability runtime."
	(:require [cn.li.ac.ability.client.api :as client-api]))

(def req-learn-skill! client-api/req-learn-skill!)
(def req-level-up! client-api/req-level-up!)
(def req-set-activated! client-api/req-set-activated!)
(def req-set-preset-slot! client-api/req-set-preset-slot!)
(def req-switch-preset! client-api/req-switch-preset!)
(def req-location-teleport-query! client-api/req-location-teleport-query!)
(def req-location-teleport-add! client-api/req-location-teleport-add!)
(def req-location-teleport-remove! client-api/req-location-teleport-remove!)
(def req-location-teleport-perform! client-api/req-location-teleport-perform!)