(ns fig-ure.domain
  "Pure domain entity constructors and value transformers for telemetry readings."
  (:require [fig-ure.util :refer [round-2]]))

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


