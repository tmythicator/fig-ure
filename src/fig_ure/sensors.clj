(ns fig-ure.sensors
  "Asynchronous I2C sensor reader module for soil moisture, temperature, and humidity."
  (:require [clojure.java.shell :refer [sh]]
            [integrant.core :as ig]))

(def ^:private bme280-i2c-addr "0x77")
(def ^:private bme280-chip-id-reg "d0")
(def ^:private bme280-expected-chip-id "60")

(defn valid-percent-reading?
  "Check if a percent reading is valid (number and within reasonable range)."
  [reading]
  (let [val (:sensor/value reading)
        unit (:sensor/unit reading)]
    (boolean (and (= unit :percent)
                  (number? val)
                  (<= 0 val 100.0)))))

(defn calculate-average-percent-value
  "Calculates average value over the readings from one sensor (percent unit)."
  [readings]
  (let [values (->> readings
                    (filter valid-percent-reading?)
                    (map :sensor/value))]
    (if (seq values)
      (/ (reduce + values) (count values))
      0.0)))

(defn parse-i2cdump-chip-id
  "Parses raw i2cdump output text to get the BME280 chip ID."
  [dump-text]
  (let [regex   (re-pattern (str bme280-chip-id-reg ":\\s+([0-9a-fA-F]{2})"))
        chip-id (second (re-find regex dump-text))]
    (if chip-id
      {:status         :ok
       :bme280/chip-id (str "0x" chip-id)
       :bme280/valid?  (= chip-id bme280-expected-chip-id)}
      {:status        :error
       :error/reason :parse-failed})))

(defn read-bme280-chip-id
  "Reads the BME280 chip ID register (0xD0) via i2cdump over I2C bus 1."
  []
  (let [result (sh "i2cdump" "-y" "1" bme280-i2c-addr)]
    (if (zero? (:exit result))
      (parse-i2cdump-chip-id (:out result))
      {:status        :error
       :error/reason  :i2c-read-failed
       :error/message (:err result)})))

(defmethod ig/init-key :fig-ure/sensors [_ config]
  (println "Initializing sensor reader..." config)
  ;; Component state returned to Integrant

  {:status :ready})

(defmethod ig/halt-key! :fig-ure/sensors [_ state]
  (println "Halting sensor reader..." state))

(comment
  ;; Interactive REPL scratchpad
  (:exit {:exit 33})
  (format-reading :soil-ham 12.3 :percent))