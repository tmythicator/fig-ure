(ns fig-ure.sensors.bme280
  "Hardware I/O driver and pure text parsers for Bosch BME280 I2C sensor."
  (:require [clojure.string :as string]))

(def i2c-addr "0x77")
(def ^:private chip-id-reg "0xd0")
(def ^:private temperature-reg "0xf0")
(def ^:private expected-chip-id "60")

(defn- strip-0x [s]
  (if (string/starts-with? s "0x")
    (subs s 2)
    s))

(defn parse-chip-id
  "Parses raw i2cdump output text to get the BME280 chip ID."
  [dump-text]
  (let [reg-prefix (strip-0x chip-id-reg)
        regex      (re-pattern (str reg-prefix ":\\s+([0-9a-fA-F]{2})"))
        chip-id    (second (re-find regex dump-text))]
    (if chip-id
      {:status         :ok
       :bme280/chip-id (str "0x" chip-id)
       :bme280/valid?  (= chip-id expected-chip-id)}
      {:status        :error
       :error/reason :parse-failed})))

(defn parse-temperature
  "Parses raw ADC temperature bytes from i2cdump output text."
  [dump-text format-reading-fn]
  (let [reg-prefix (strip-0x temperature-reg)
        regex      (re-pattern (str reg-prefix ":\\s+([0-9a-fA-F\\s]+)\\s{4}"))
        f0-str     (second (re-find regex dump-text))
        bytes      (when f0-str (string/split f0-str #"\s+"))]
    (if (and bytes (>= (count bytes) 13))
      (let [msb          (Integer/parseInt (nth bytes 10) 16)
            lsb          (Integer/parseInt (nth bytes 11) 16)
            xlsb         (Integer/parseInt (nth bytes 12) 16)
            msb-shifted  (bit-shift-left msb 12)
            lsb-shifted  (bit-shift-left lsb 4)
            xlsb-shifted (bit-shift-right xlsb 4)
            raw-val      (bit-or msb-shifted lsb-shifted xlsb-shifted)]
        {:status  :ok
         :reading (format-reading-fn :bme280-temperature raw-val :raw-adc)})
      {:status        :error
       :error/reason :parse-failed})))
