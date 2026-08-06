(ns cn.li.mc262.integration.event-handlers
  "Thin re-export of cn.li.mcbase.integration.event-handlers."
  (:require [cn.li.mcbase.integration.event-handlers :as shared]))

(def runtime-active-result shared/runtime-active-result)
(def runtime-active-result? shared/runtime-active-result?)
(def runtime-active-event? shared/runtime-active-event?)
(def handle-block-left-click shared/handle-block-left-click)
(def handle-entity-attack shared/handle-entity-attack)
(def handle-entity-interact shared/handle-entity-interact)
(def handle-block-place shared/handle-block-place)
(def handle-block-break shared/handle-block-break)
(def handle-block-right-click shared/handle-block-right-click)
