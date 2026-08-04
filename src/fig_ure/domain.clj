(ns fig-ure.domain
  "Pure domain entity constructors and value transformers for telemetry readings."
  (:require
   [fig-ure.util :refer [round-2]]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defmulti format-sensor-value
  "Dispatches value formatting based on sensor-id."
  (fn [sensor-id _val] sensor-id))

(defmethod format-sensor-value :default [_ val]
  (if (number? val)
    (round-2 val)
    val))

(defmethod format-sensor-value :seesaw/moisture [_ val]
  val)

(defn make-reading
  "Domain factory function to create a standardized telemetry reading map."
  [sensor-id raw-val unit]
  {:sensor/id        sensor-id
   :sensor/value     (format-sensor-value sensor-id raw-val)
   :sensor/unit      unit
   :sensor/timestamp (System/currentTimeMillis)})

(defn make-telemetry-data-event
  "Domain factory for successful telemetry producer event."
  [producer-name data]
  {:event/type    :producer-data
   :producer/name producer-name
   :data          data})

(defn make-telemetry-error-event
  "Domain factory for telemetry producer error event."
  [producer-name error]
  {:event/type    :producer-error
   :producer/name producer-name
   :error         error})

(defn make-telemetry-stopped-event
  "Domain factory for stopped telemetry producer event."
  [producer-name]
  {:event/type    :producer-stopped
   :producer/name producer-name})

(defn- format-line-protocol-values
  "Formats field numeric value according to InfluxDB Line Protocol type rules:
   - Integers get 'i' suffix
   - Floats/doubles remain unquoted"
  [value]
  (cond
    (integer? value) (str value "i")
    (number? value) (str value)
    :else nil))

(defn reading->line-protocol
  "Transforms a SensorReading into a single Line Protocol line string.
   Returns formatted string, or nil if reading is invalid or non-numeric."
  [{:keys [sensor/id sensor/value sensor/unit sensor/timestamp] :as _reading}]
  (when (and id value unit timestamp)
    (when-let [val-str (format-line-protocol-values value)]
      (let [id-str (if (keyword? id) (subs (str id) 1) (str id))
            unit-str (if (keyword? unit) (name unit) (str unit))]
        (format "telemetry,sensor_id=%s,unit=%s value=%s %d"
                id-str
                unit-str
                val-str
                timestamp)))))

(defn readings->payload
  "Transforms a SensorReading vector into a HTTP request payload."
  [readings]
  (->> readings
       (keep reading->line-protocol)
       (str/join "\n")))

(comment
  (name :elol/test)
  (reading->line-protocol
   {:sensor/id        :bme280/temperature
    :sensor/value     "asf"
    :sensor/unit      :celsius
    :sensor/timestamp 1785658576925})
  (format-line-protocol-values nil))