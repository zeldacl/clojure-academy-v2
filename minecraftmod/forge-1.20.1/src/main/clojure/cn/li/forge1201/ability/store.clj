(ns cn.li.forge1201.ability.store
  "Forge binding for mcmod.platform.ability/*player-ability-store*."
  (:require [cn.li.mcmod.platform.ability :as pability]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.model.ability-data :as ad]
            [cn.li.ac.ability.model.resource-data :as rd]
            [cn.li.ac.ability.model.cooldown-data :as cd]
            [cn.li.ac.ability.model.preset-data :as pd]))

(defn- ensure-state! [uuid]
  (ps/get-or-create-player-state! uuid))

(defn forge-player-ability-store []
  (reify
    pability/IPlayerAbilityData
    (ability-get-category [_ uuid]
      (get-in (ensure-state! uuid) [:ability-data :category-id]))
    (ability-set-category! [_ uuid cat-id]
      (ps/update-ability-data! uuid ad/set-category cat-id))
    (ability-is-learned? [_ uuid skill-id]
      (boolean (get-in (ensure-state! uuid) [:ability-data :learned-skills skill-id])))
    (ability-learn-skill! [_ uuid skill-id]
      (ps/update-ability-data! uuid ad/learn-skill skill-id))
    (ability-get-skill-exp [_ uuid skill-id]
      (float (get-in (ensure-state! uuid) [:ability-data :skill-exps skill-id] 0.0)))
    (ability-set-skill-exp! [_ uuid skill-id amount]
      (ps/update-ability-data! uuid ad/set-skill-exp skill-id amount))
    (ability-get-level [_ uuid]
      (int (get-in (ensure-state! uuid) [:ability-data :level] 1)))
    (ability-set-level! [_ uuid level]
      (ps/update-ability-data! uuid ad/set-level level))
    (ability-get-level-progress [_ uuid]
      (float (get-in (ensure-state! uuid) [:ability-data :level-progress] 0.0)))
    (ability-set-level-progress! [_ uuid amount]
      (ps/update-ability-data! uuid ad/set-level-progress amount))

    pability/IResourceData
    (res-get-cur-cp [_ uuid]
      (double (get-in (ensure-state! uuid) [:resource-data :cur-cp] 0.0)))
    (res-get-max-cp [_ uuid]
      (double (get-in (ensure-state! uuid) [:resource-data :max-cp] 0.0)))
    (res-set-cur-cp! [_ uuid v]
      (ps/update-resource-data! uuid rd/set-cur-cp v))
    (res-get-cur-overload [_ uuid]
      (double (get-in (ensure-state! uuid) [:resource-data :cur-overload] 0.0)))
    (res-get-max-overload [_ uuid]
      (double (get-in (ensure-state! uuid) [:resource-data :max-overload] 0.0)))
    (res-set-cur-overload! [_ uuid v]
      (ps/update-resource-data! uuid rd/set-cur-overload v))
    (res-is-overload-fine? [_ uuid]
      (boolean (get-in (ensure-state! uuid) [:resource-data :overload-fine] true)))
    (res-is-activated? [_ uuid]
      (boolean (get-in (ensure-state! uuid) [:resource-data :activated] false)))
    (res-set-activated! [_ uuid v]
      (ps/update-resource-data! uuid rd/set-activated v))
    (res-get-until-recover [_ uuid]
      (int (get-in (ensure-state! uuid) [:resource-data :until-recover] 0)))
    (res-set-until-recover! [_ uuid ticks]
      (ps/update-resource-data! uuid rd/set-until-recover ticks))
    (res-get-interferences [_ uuid]
      (set (get-in (ensure-state! uuid) [:resource-data :interferences] #{})))
    (res-add-interference! [_ uuid src-id]
      (ps/update-resource-data! uuid rd/add-interference src-id))
    (res-remove-interference! [_ uuid src-id]
      (ps/update-resource-data! uuid rd/remove-interference src-id))

    pability/ICooldownData
    (cd-is-in-cooldown? [_ uuid ctrl-id sub-id]
      (cd/in-cooldown? (get-in (ensure-state! uuid) [:cooldown-data]) ctrl-id sub-id))
    (cd-set-cooldown! [_ uuid ctrl-id sub-id ticks]
      (ps/update-cooldown-data! uuid cd/set-cooldown ctrl-id sub-id ticks))
    (cd-get-remaining [_ uuid ctrl-id sub-id]
      (cd/get-remaining (get-in (ensure-state! uuid) [:cooldown-data]) ctrl-id sub-id))
    (cd-tick! [_ uuid]
      (ps/update-cooldown-data! uuid cd/tick-cooldowns))

    pability/IPresetData
    (preset-get-active [_ uuid]
      (int (get-in (ensure-state! uuid) [:preset-data :active-preset] 0)))
    (preset-set-active! [_ uuid idx]
      (ps/update-preset-data! uuid pd/set-active-preset idx))
    (preset-get-slot [_ uuid preset-idx key-idx]
      (pd/get-slot (get-in (ensure-state! uuid) [:preset-data]) preset-idx key-idx))
    (preset-set-slot! [_ uuid preset-idx key-idx controllable]
      (ps/update-preset-data! uuid pd/set-slot preset-idx key-idx controllable))
    (preset-get-all [_ uuid]
      (:slots (get-in (ensure-state! uuid) [:preset-data])))))

(defn install-store! []
  (alter-var-root #'pability/*player-ability-store*
                  (constantly (forge-player-ability-store))))
