(ns cn.li.ac.block.machine.inventory-stack
  "Shared item stack helpers for machine inventory logic."
  (:require [cn.li.mcmod.platform.item :as pitem]))

(defn stack-empty?
  [stack]
  (or (nil? stack)
      (try (boolean (pitem/empty? stack)) (catch Exception _ false))))

(defn stack-count
  [stack]
  (if (stack-empty? stack)
    0
    (try (int (pitem/stack-count stack)) (catch Exception _ 0))))

(defn stack-id
  [stack]
  (when-not (stack-empty? stack)
    (try (some-> stack pitem/object pitem/registry-name str)
         (catch Exception _ nil))))

(defn rebuild-stack
  [stack new-count]
  (when (and stack (pos? (int new-count)))
    (when-let [item-id (stack-id stack)]
      (pitem/stack-by-id item-id (int new-count)))))

(defn consume-stack
  [stack amount]
  (let [left (- (stack-count stack) (int amount))]
    (when (pos? left)
      (rebuild-stack stack left))))

(defn merge-stack-count
  [existing produced]
  (cond
    (stack-empty? produced) existing
    (stack-empty? existing) produced
    :else (rebuild-stack existing (+ (stack-count existing) (stack-count produced)))))
