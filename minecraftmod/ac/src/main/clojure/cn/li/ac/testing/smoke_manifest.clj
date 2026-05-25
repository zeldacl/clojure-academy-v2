(ns cn.li.ac.testing.smoke-manifest
  "AC-owned smoke manifest data registered through mcmod's neutral registry."
  (:require [cn.li.mcmod.content.registry :as content-registry]))

(def ^:private manifest-id :cn.li.ac/content-smoke)

(defn register!
  []
  (content-registry/register-smoke-manifest!
   {:id manifest-id
    :content-id "ac"
    :checks [{:id :registry-present
              :kind :registry}
             {:id :client-surface-present
              :kind :client}
             {:id :persistence-present
              :kind :persistence}]
    :fixtures {:content-owned? true}})
  nil)