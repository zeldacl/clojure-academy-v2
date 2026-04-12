(ns cn.li.ac.ability.client-api
  "Client request API for ability runtime and GUI.

  This namespace stays platform-neutral by using mcmod network client transport."
  (:require [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.ability.catalog :as catalog]))

(defn req-learn-skill!
  "Send learn-skill request. Optional `extra` map may include
  `:pos-x` `:pos-y` `:pos-z` (integers) to charge IF from that Ability Developer."
  ([skill-id callback]
   (req-learn-skill! skill-id nil callback))
  ([skill-id extra callback]
   (net-client/send-to-server catalog/MSG-REQ-LEARN-SKILL
                                (merge {:skill-id skill-id} (or extra {}))
                                callback)))

(defn req-level-up! [callback]
  (net-client/send-to-server catalog/MSG-REQ-LEVEL-UP {} callback))

(defn req-set-activated! [activated callback]
  (net-client/send-to-server catalog/MSG-REQ-SET-ACTIVATED
                             {:activated (boolean activated)}
                             callback))

(defn req-set-preset-slot!
  [preset-idx key-idx cat-id ctrl-id callback]
  (net-client/send-to-server catalog/MSG-REQ-SET-PRESET
                             {:preset-idx preset-idx
                              :key-idx key-idx
                              :cat-id cat-id
                              :ctrl-id ctrl-id}
                             callback))

(defn req-switch-preset! [preset-idx callback]
  (net-client/send-to-server catalog/MSG-REQ-SWITCH-PRESET
                             {:preset-idx preset-idx}
                             callback))
