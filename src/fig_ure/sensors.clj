(ns fig-ure.sensors
  "Asynchronous I2C sensor reader module for soil moisture, temperature, and humidity."
  (:require [clojure.core.async :as async]
            [integrant.core :as ig]))

(defmethod ig/init-key :fig-ure/sensors [_ config]
  (println "Initializing sensor reader..." config)
  ;; Component state returned to Integrant

  {:status :ready})

(defmethod ig/halt-key! :fig-ure/sensors [_ state]
  (println "Halting sensor reader..." state))

(defn format-reading
  "Formats a raw sensor reading into the internal telemetry map structure."
  [sensor-id raw-val unit]
  {:sensor/id sensor-id
   :sensor/value raw-val
   :sensor/unit unit
   :sensor/timestamp (System/currentTimeMillis)})

(defn valid-reading?
  "Check if a reading is valid (number and within reasonable range)."
  [reading]
  (let [val (:sensor/value reading)]
    (boolean (and (number? val) (<= 0 val 100.0)))))

(comment
  ;; Interactive REPL scratchpad
  (format-reading :soil-ham "asdasd" :percent))