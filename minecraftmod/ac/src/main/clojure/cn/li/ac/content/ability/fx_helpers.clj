(ns cn.li.ac.content.ability.fx-helpers
  "Shared helpers for server->client ability FX messages.

  Keeps the transport shape consistent so ability namespaces only assemble
  payload-specific fields while this namespace injects the standard :mode tag."
  (:require [cn.li.ac.ability.service.dispatcher :as ctx]))

(defn send-fx!
  [ctx-id event-key mode payload]
  (ctx/ctx-send-to-client! ctx-id event-key (assoc (or payload {}) :mode mode)))

(defn send-start!
  ([ctx-id event-key]
   (send-start! ctx-id event-key nil))
  ([ctx-id event-key payload]
   (send-fx! ctx-id event-key :start payload)))

(defn send-update!
  ([ctx-id event-key]
   (send-update! ctx-id event-key nil))
  ([ctx-id event-key payload]
   (send-fx! ctx-id event-key :update payload)))

(defn send-perform!
  ([ctx-id event-key]
   (send-perform! ctx-id event-key nil))
  ([ctx-id event-key payload]
   (send-fx! ctx-id event-key :perform payload)))

(defn send-end!
  ([ctx-id event-key]
   (send-end! ctx-id event-key nil))
  ([ctx-id event-key payload]
   (send-fx! ctx-id event-key :end payload)))