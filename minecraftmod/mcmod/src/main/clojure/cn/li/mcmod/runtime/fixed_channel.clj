(ns cn.li.mcmod.runtime.fixed-channel
  "Fixed-schema network packets shared by AC and the Minecraft adapter.

  VFX packets deliberately use the same bounded binary codec as the other
  mcmod-owned payloads, but are wrapped in a fixed packet-type frame. AC
  never sends a raw Clojure signal over the platform bridge: the frame is the
  only wire representation and the adapter validates it before forwarding."
  (:require [cn.li.mcmod.network.binary-codec :as binary-codec]))
(import '(java.nio ByteBuffer ByteOrder))
(import '(java.nio.charset StandardCharsets))
(def ^:const protocol-version 1)
(def packet-types {:catalog-hello 1 :catalog-ack 2 :input-edge 3 :input-ack 4 :combat-feedback 5 :vfx-trigger 6 :vfx-spawn 7 :vfx-update 8 :vfx-destroy 9 :vfx-clear-owner 10 :vfx-snapshot 11 :vfx-release 12})
(def reverse-packet-types (into {} (map (fn [[k v]] [v k]) packet-types)))
(def ^:private vfx-ops #{:trigger :spawn :update :destroy :release :clear-owner :snapshot})
(def ^:private vfx-op->packet-type
  {:trigger :vfx-trigger
   :spawn :vfx-spawn
   :update :vfx-update
   :destroy :vfx-destroy
   :clear-owner :vfx-clear-owner
   :snapshot :vfx-snapshot
   :release :vfx-release})
(def ^:private packet-type->vfx-op
  (into {} (map (fn [[op packet-type]] [packet-type op]) vfx-op->packet-type)))
(declare ensure-remaining! frame)

(defn- validate-vfx-payload! [signal]
  (let [params (:params signal)
        mask (:mask signal)]
    (when (and (some? params) (not (map? params)))
      (throw (ex-info "VFX params must be a map" {:params params})))
    (when (and (map? params) (> (count params) 64))
      (throw (ex-info "VFX params exceed catalog bound" {:max 64 :count (count params)})))
    (when (some? mask)
      (when-not (map? mask)
        (throw (ex-info "VFX dirty mask must be a map" {:mask mask})))
      (let [word-count (long (or (:word-count mask) 0))
            words (:words mask)]
        (when (or (neg? word-count) (> word-count 1) (not (vector? words))
                  (not= word-count (count words)))
          (throw (ex-info "VFX dirty mask exceeds catalog bound"
                          {:max-words 1 :mask mask})))
        (when (some #(not (integer? %)) words)
          (throw (ex-info "VFX dirty mask words must be integers" {:mask mask}))))))
  signal)
(defn- vfx-wire-signal [signal]
  (when-not (map? signal)
    (throw (ex-info "VFX packet must be a map" {:signal signal})))
  (let [op (:op signal)]
    (when-not (contains? vfx-ops op)
      (throw (ex-info "unknown VFX packet operation" {:op op})))
    (validate-vfx-payload! signal)
    ;; Keep only final ABI fields. Dirty updates carry :mask and changed
    ;; :params, while :spawn/:snapshot carry their full baseline.
    (cond-> (select-keys signal
                         [:instance-key :instance-id :effect-id :owner :world-id :anchor
                          :seed :event-seq :state-seq :params :mask :radius :audience])
      true (assoc :op op))))

(defn encode-vfx-signal
  "Encode one final VFX signal as a bounded fixed-channel packet."
  ^bytes [signal]
  (let [wire (vfx-wire-signal signal)
        op (:op wire)]
    (frame (get vfx-op->packet-type op)
           (binary-codec/encode wire))))

(defn- decode-frame [^bytes packet]
  (let [^ByteBuffer buffer (doto (ByteBuffer/wrap packet)
                             (.order ByteOrder/BIG_ENDIAN))
        _ (ensure-remaining! buffer 4 :fixed-frame)
        version (bit-and 0xff (int (.get buffer)))
        type (bit-and 0xff (int (.get buffer)))
        payload-length (bit-and 0xffff (int (.getShort buffer)))]
    (when-not (= protocol-version version)
      (throw (ex-info "protocol version mismatch"
                      {:expected protocol-version :actual version})))
    (when-not (= payload-length (.remaining buffer))
      (throw (ex-info "fixed packet length mismatch"
                      {:declared payload-length :actual (.remaining buffer)})))
    (let [payload (byte-array payload-length)]
      (.get buffer payload)
      {:packet-type (get reverse-packet-types type)
       :payload payload})))

(defn decode-vfx-signal
  "Decode and validate a fixed VFX packet from the mcmod bridge."
  [^bytes packet]
  (let [{:keys [packet-type payload]} (decode-frame packet)
        op (get packet-type->vfx-op packet-type)]
    (when-not op
      (throw (ex-info "packet is not a VFX packet" {:packet-type packet-type})))
    (let [signal (binary-codec/decode payload)]
      (when-not (map? signal)
        (throw (ex-info "decoded VFX packet is not a map" {:value signal})))
      (when-not (= op (:op signal))
        (throw (ex-info "VFX packet operation/type mismatch"
                        {:packet-type packet-type :expected op :actual (:op signal)})))
      (vfx-wire-signal signal))))
(defn- ensure-remaining! [^ByteBuffer buffer amount context]
  (when (< (.remaining buffer) amount)
    (throw (ex-info "fixed packet is truncated"
                    {:required amount :remaining (.remaining buffer) :context context}))))
(defn- bounded-string-bytes [value max-length]
  (let [^String text (str value) ^bytes bytes (.getBytes text StandardCharsets/UTF_8)]
    (when (> (alength bytes) max-length) (throw (ex-info "protocol string exceeds bound" {:max max-length :value value}))) bytes))
(defn- put-string! [^ByteBuffer buffer value max-length]
  (let [^bytes bytes (bounded-string-bytes value max-length)] (.putShort buffer (short (alength bytes))) (.put buffer bytes) buffer))
(defn- get-string! [^ByteBuffer buffer max-length]
  (ensure-remaining! buffer 2 :string-length)
  (let [size (bit-and 0xffff (int (.getShort buffer)))]
    (when (> size max-length) (throw (ex-info "protocol string exceeds bound" {:max max-length :size size})))
    (ensure-remaining! buffer size :string-body)
    (let [^bytes bytes (byte-array size)] (.get buffer bytes) (String. bytes StandardCharsets/UTF_8))))
(defn encode-intent [{:keys [seq control-id edge choice client-tick] :as intent}]
  (when-not (and (integer? seq) (<= 0 seq 2147483647)) (throw (ex-info "invalid input seq" {:intent intent})))
  (when-not (and (integer? control-id) (<= 0 control-id 255)) (throw (ex-info "invalid control id" {:intent intent})))
  (when-not (contains? #{:press :release :abort} edge) (throw (ex-info "invalid input edge" {:intent intent})))
  (let [choice-tag (cond (nil? choice) 0 (integer? choice) 1 (string? choice) 2 :else -1)
        _ (when (= -1 choice-tag) (throw (ex-info "invalid input choice" {:intent intent})))
        ^ByteBuffer buffer (doto (ByteBuffer/allocate 64) (.order ByteOrder/BIG_ENDIAN) (.put (byte protocol-version)) (.put (byte (:input-edge packet-types))) (.putInt (int seq)) (.put (byte control-id)) (.put (byte ({:press 1 :release 2 :abort 3} edge))) (.put (byte choice-tag)) (.putInt (int (or client-tick 0))))]
    (case choice-tag 1 (.putInt buffer (int choice)) 2 (put-string! buffer choice 128) nil)
    (let [length (.position buffer) result (byte-array length)] (.flip buffer) (.get buffer result) result)))
(defn decode-intent [^bytes packet]
  (let [^ByteBuffer buffer (doto (ByteBuffer/wrap packet) (.order ByteOrder/BIG_ENDIAN))
        _ (ensure-remaining! buffer 13 :input-header)
        version (bit-and 0xff (int (.get buffer))) type (bit-and 0xff (int (.get buffer)))]
    (when-not (= protocol-version version) (throw (ex-info "protocol version mismatch" {:expected protocol-version :actual version})))
    (when-not (= (:input-edge packet-types) type) (throw (ex-info "packet is not an input edge" {:type type})))
    (let [seq (Integer/toUnsignedLong (.getInt buffer)) control-id (bit-and 0xff (int (.get buffer))) edge (get {1 :press 2 :release 3 :abort} (bit-and 0xff (int (.get buffer)))) choice-tag (bit-and 0xff (int (.get buffer))) client-tick (Integer/toUnsignedLong (.getInt buffer))]
      (when-not edge (throw (ex-info "invalid input edge tag" {})))
      (let [choice (case choice-tag
                     0 nil
                     1 (do (ensure-remaining! buffer 4 :choice-int) (.getInt buffer))
                     2 (get-string! buffer 128)
                     (throw (ex-info "invalid choice tag" {:tag choice-tag})))]
        (when (.hasRemaining buffer)
          (throw (ex-info "input edge packet has trailing bytes"
                          {:remaining (.remaining buffer)})))
        {:type :input-edge :seq seq :control-id control-id :edge edge
         :choice choice :client-tick client-tick}))))
(defn frame [packet-type payload]
  (when-not (contains? packet-types packet-type) (throw (ex-info "unknown fixed packet type" {:packet-type packet-type})))
  (let [^bytes payload (byte-array payload)]
    (when (> (alength payload) 65535)
      (throw (ex-info "fixed packet payload exceeds bound" {:length (alength payload)})))
    (let [^ByteBuffer buffer (doto (ByteBuffer/allocate (+ 4 (alength payload)))
                               (.order ByteOrder/BIG_ENDIAN)
                               (.put (byte protocol-version))
                               (.put (byte (get packet-types packet-type)))
                               (.putShort (short (alength payload)))
                               (.put payload))
          result (byte-array (.position buffer))]
      (.flip buffer) (.get buffer result) result)))

(defn- encode-catalog-payload [{:keys [schema-version content-hash] :as catalog}]
  (when-not (and (integer? schema-version) (<= 0 schema-version 65535))
    (throw (ex-info "invalid catalog schema version" {:catalog catalog})))
  (let [^bytes hash-bytes (bounded-string-bytes content-hash 128)
        ^ByteBuffer buffer (doto (ByteBuffer/allocate (+ 4 (alength hash-bytes)))
                             (.order ByteOrder/BIG_ENDIAN)
                             (.putShort (short schema-version))
                             (.putShort (short (alength hash-bytes)))
                             (.put hash-bytes))
        result (byte-array (.position buffer))]
    (.flip buffer) (.get buffer result) result))

(defn- decode-catalog-payload [^bytes payload]
  (let [^ByteBuffer buffer (doto (ByteBuffer/wrap payload) (.order ByteOrder/BIG_ENDIAN))
        _ (ensure-remaining! buffer 4 :catalog-header)
        schema-version (bit-and 0xffff (int (.getShort buffer)))
        hash-length (bit-and 0xffff (int (.getShort buffer)))]
    (when (> hash-length 128)
      (throw (ex-info "catalog hash exceeds bound" {:length hash-length})))
    (when-not (= hash-length (.remaining buffer))
      (throw (ex-info "catalog hash length mismatch" {:declared hash-length :actual (.remaining buffer)})))
    (let [^bytes hash-bytes (byte-array hash-length)]
      (.get buffer hash-bytes)
      {:schema-version schema-version :content-hash (String. hash-bytes StandardCharsets/UTF_8)})))

(defn encode-catalog-hello [catalog]
  (frame :catalog-hello (encode-catalog-payload catalog)))

(defn decode-catalog-hello [^bytes packet]
  (let [^ByteBuffer buffer (doto (ByteBuffer/wrap packet) (.order ByteOrder/BIG_ENDIAN))
        _ (ensure-remaining! buffer 4 :catalog-frame)
        version (bit-and 0xff (int (.get buffer)))
        type (bit-and 0xff (int (.get buffer)))
        payload-length (bit-and 0xffff (int (.getShort buffer)))]
    (when-not (= protocol-version version)
      (throw (ex-info "protocol version mismatch" {:expected protocol-version :actual version})))
    (when-not (= (:catalog-hello packet-types) type)
      (throw (ex-info "packet is not a catalog hello" {:type type})))
    (when-not (= payload-length (.remaining buffer))
      (throw (ex-info "catalog hello length mismatch" {:declared payload-length :actual (.remaining buffer)})))
    (let [payload (byte-array payload-length)]
      (.get buffer payload)
      (assoc (decode-catalog-payload payload) :type :catalog-hello))))

(defn encode-catalog-ack [{:keys [accepted? schema-version content-hash]}]
  (let [identity (encode-catalog-payload {:schema-version schema-version :content-hash content-hash})
        payload (byte-array (concat [(byte (if accepted? 1 0))] (seq identity)))]
    (frame :catalog-ack payload)))

(defn decode-catalog-ack [^bytes packet]
  (let [^ByteBuffer buffer (doto (ByteBuffer/wrap packet) (.order ByteOrder/BIG_ENDIAN))
        _ (ensure-remaining! buffer 5 :catalog-ack-frame)
        version (bit-and 0xff (int (.get buffer)))
        type (bit-and 0xff (int (.get buffer)))
        payload-length (bit-and 0xffff (int (.getShort buffer)))
        accepted-tag (bit-and 0xff (int (.get buffer)))]
    (when-not (= protocol-version version)
      (throw (ex-info "protocol version mismatch" {:expected protocol-version :actual version})))
    (when-not (= (:catalog-ack packet-types) type)
      (throw (ex-info "packet is not a catalog ack" {:type type})))
    (when-not (contains? #{0 1} accepted-tag)
      (throw (ex-info "invalid catalog ack tag" {:tag accepted-tag})))
    (when-not (= payload-length (inc (.remaining buffer)))
      (throw (ex-info "catalog ack length mismatch" {:declared payload-length :actual (inc (.remaining buffer))})))
    (let [payload (byte-array (.remaining buffer))]
      (.get buffer payload)
      (assoc (decode-catalog-payload payload)
             :type :catalog-ack
             :accepted? (= 1 accepted-tag)))))

(defn encode-combat-feedback
  "Encode the compact client-facing combat result.

  Transaction/action diagnostics never cross this boundary; only status and
  typed feedback notices are part of the fixed packet ABI."
  ^bytes [{:keys [status feedback] :as result}]
  (when-not (map? result)
    (throw (ex-info "combat feedback must be a map" {:result result})))
  (when-not (or (nil? status) (keyword? status))
    (throw (ex-info "combat feedback status must be a keyword" {:status status})))
  (let [feedback (vec (or feedback []))]
    (frame :combat-feedback
           (binary-codec/encode {:status status :feedback feedback}))))

(defn decode-combat-feedback
  "Decode and validate one fixed combat feedback packet."
  [^bytes packet]
  (let [{:keys [packet-type payload]} (decode-frame packet)]
    (when-not (= :combat-feedback packet-type)
      (throw (ex-info "packet is not combat feedback" {:packet-type packet-type})))
    (let [value (binary-codec/decode payload)]
      (when-not (map? value)
        (throw (ex-info "decoded combat feedback is not a map" {:value value})))
      (when-not (or (nil? (:status value)) (keyword? (:status value)))
        (throw (ex-info "decoded combat feedback has invalid status"
                        {:status (:status value)})))
      (when-not (vector? (:feedback value))
        (throw (ex-info "decoded combat feedback has invalid feedback list"
                        {:feedback (:feedback value)})))
      (assoc value :type :combat-feedback))))
