(ns fig-ure.sensors
  "Asynchronous I2C sensor reader module for soil moisture, temperature, and humidity."
  (:require
   [clojure.java.shell :refer [sh]]
   [clojure.string :as string]
   [fig-ure.schema :as schema]
   [fig-ure.sensors.bme280 :as bme280] ;; [snitch.core :refer [defn*]]
   [fig-ure.sensors.seesaw :as seesaw]
   [fig-ure.util :refer [round-2]]
   [integrant.core :as ig]))

(defn- write-i2cset!
  "Executes i2cset command to perform a control write on chip."
  ([chip-addr reg-addr value] (write-i2cset! "1" chip-addr reg-addr value))
  ([bus chip-addr reg-addr value]
   (let [res (sh "i2cset" "-y" bus chip-addr reg-addr value)]
     (if (zero? (:exit res))
       {:status :ok}
       {:status        :error
        :error/reason  :i2c-write-failed
        :error/message (string/trim (:err res))}))))

(defn- fetch-i2cget
  "Executes i2cget command to read register value(s) from a chip."
  ([chip-addr reg-addr] (fetch-i2cget "1" chip-addr reg-addr))
  ([bus chip-addr reg-addr] (fetch-i2cget bus chip-addr reg-addr nil nil))
  ([bus chip-addr reg-addr mode len]
   (let [result (if (and mode len)
                  (sh "i2cget" "-y" bus chip-addr reg-addr mode (str len))
                  (sh "i2cget" "-y" bus chip-addr reg-addr))]
     (if (zero? (:exit result))
       {:status :ok
        :out    (string/trim (:out result))}
       {:status        :error
        :error/reason  :i2c-read-failed
        :error/message (string/trim (:err result))}))))

(defn- fetch-i2cdump
  "Executes i2cdump command for specified address and bus (defaults to bus '1')."
  ([addr] (fetch-i2cdump "1" addr))
  ([bus addr]
   (let [result (sh "i2cdump" "-y" bus addr)]
     (if (zero? (:exit result))
       {:status :ok :out (:out result)}
       {:status        :error
        :error/reason  :i2c-read-failed
        :error/message (string/trim (:err result))}))))

(defmulti format-sensor-value
  "Dispatches formatting based on sensor-id."
  (fn [sensor-id _val] sensor-id))

(defmethod format-sensor-value :default [_ val]
  (if (number? val)
    (round-2 val)
    val))

(defmethod format-sensor-value :seesaw/moisture [_ val]
  val)

(defn format-reading
  "Formats a raw sensor reading into the internal telemetry map structure."
  [sensor-id raw-val unit]
  {:sensor/id        sensor-id
   :sensor/value     (format-sensor-value sensor-id raw-val)
   :sensor/unit      unit
   :sensor/timestamp (System/currentTimeMillis)})

(defn valid-percent-reading?
  "Check if a percent reading is valid (number and within reasonable range)."
  [reading]
  (let [val  (:sensor/value reading)
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

;; =============================================================================
;; 2. BME280 READERS
;; =============================================================================

(declare set-sensor-mode!)

(defn- read-bme280-chip-id
  "Reads and validates the BME280 chip ID via i2cget."
  ([] (read-bme280-chip-id "1"))
  ([bus]
   (let [res (fetch-i2cget bus bme280/i2c-addr (:chip-id bme280/registers))]
     (if (= :ok (:status res))
       (assoc (bme280/decode-chip-id (:out res)) :status :ok)
       res))))

(defn- read-bme280-mode
  "Reads current BME280 sensor mode from ctrl_meas register."
  ([] (read-bme280-mode "1"))
  ([bus]
   (let [res (fetch-i2cget bus bme280/i2c-addr (:ctrl-meas bme280/registers))]
     (if (= :ok (:status res))
       {:status :ok :bme280/mode (bme280/decode-mode (:out res))}
       res))))

(defn- read-bme280-calibration
  "Reads BME280 raw calibration registers via i2cdump and parses coefficients."
  ([] (read-bme280-calibration "1"))
  ([bus]
   (let [dump (fetch-i2cdump bus bme280/i2c-addr)]
     (if (= :ok (:status dump))
       (bme280/parse-calibration (:out dump))
       dump))))

(defn- read-bme280-temperature
  "Reads raw ADC temperature reading from BME280 driver."
  ([] (read-bme280-temperature "1"))
  ([bus]
   (let [dump (fetch-i2cdump bus bme280/i2c-addr)]
     (if (= :ok (:status dump))
       (let [calib  (:calibration (read-bme280-calibration bus))
             parsed (bme280/parse-bme280-readings (:out dump) calib)]
         (if (= :ok (:status parsed))
           {:status  :ok
            :reading (format-reading :bme280/temperature (:temp parsed) :celsius)}
           parsed))
       dump))))

(defn- read-bme280-readings
  "Reads all BME280 metrics (temperature, pressure, humidity) atomically in a single I2C dump.
   Optionally accepts pre-cached calibration map to avoid extra I2C calibration dumps."
  ([] (read-bme280-readings "1" nil))
  ([bus] (read-bme280-readings bus nil))
  ([bus cached-calib]
   (set-sensor-mode! :bme280 :normal bus)
   (let [dump (fetch-i2cdump bus bme280/i2c-addr)]
     (if (= :ok (:status dump))
       (let [calib  (or cached-calib (:calibration (read-bme280-calibration bus)))
             parsed (bme280/parse-bme280-readings (:out dump) calib)]
         (if (= :ok (:status parsed))
           {:status   :ok
            :readings [(format-reading :bme280/temperature (:temp parsed) :celsius)
                       (format-reading :bme280/pressure (:press parsed) :hpa)
                       (format-reading :bme280/humidity (:hum parsed) :percent)]}
           parsed))
       dump))))

(defn- read-seesaw-soil-reading
  "Generic reader for Seesaw Soil metrics (moisture or temperature)."
  ([base-key offset-key bytes-len parse-fn]
   (read-seesaw-soil-reading "1" base-key offset-key bytes-len parse-fn))
  ([bus base-key offset-key bytes-len parse-fn]
   (let [base-hex   (format "0x%02X" (get seesaw/base-registers base-key))
         offset-hex (format "0x%02X" (get seesaw/function-offsets offset-key))
         set-res    (write-i2cset! bus seesaw/i2c-addr base-hex offset-hex)]
     (if (= :ok (:status set-res))
       (let [_       (Thread/sleep ^long (:i2c-read-delay-ms seesaw/config))
             get-res (fetch-i2cget bus seesaw/i2c-addr "0x00" "i" bytes-len)]
         (if (= :ok (:status get-res))
           (parse-fn (string/split (:out get-res) #"\s+"))
           get-res))
       set-res))))

(defn- read-seesaw-soil-moisture
  "Reads soil moisture from Adafruit Seesaw sensor using median filtering over 8 samples."
  ([] (read-seesaw-soil-moisture "1"))
  ([bus]
   (let [samples (repeatedly 8 #(read-seesaw-soil-reading bus :touch :moisture 2 seesaw/parse-soil-moisture))
         valids  (->> samples
                      (filter #(= :ok (:status %)))
                      (map :moisture)
                      (filter seesaw/valid-moisture?))
         med-val (seesaw/median valids)]
     (if med-val
       {:status   :ok
        :readings [(format-reading :seesaw/moisture med-val :capacitive)]}
       (or (first (filter #(= :error (:status %)) samples))
           {:status        :error
            :error/reason  :no-valid-moisture-samples})))))

(defn- read-seesaw-soil-temperature
  "Reads soil temperature reading from Adafruit Seesaw sensor."
  ([] (read-seesaw-soil-temperature "1"))
  ([bus]
   (let [res (read-seesaw-soil-reading bus :status :temperature 4 seesaw/parse-soil-temperature)]
     (if (= :ok (:status res))
       {:status   :ok
        :readings [(format-reading :seesaw/temperature (:temperature res) :celsius)]}
       res))))

(defn- read-seesaw-readings
  "Reads both soil moisture and soil temperature sequentially from Adafruit Seesaw sensor."
  ([] (read-seesaw-readings "1"))
  ([bus]
   (let [m-res (read-seesaw-soil-moisture bus)
         t-res (read-seesaw-soil-temperature bus)]
     (if (and (= :ok (:status m-res)) (= :ok (:status t-res)))
       {:status   :ok
        :readings (into (:readings m-res) (:readings t-res))}
       (or (when-not (= :ok (:status m-res)) m-res)
           t-res)))))

;; =============================================================================
;; 3. MULTIMETHOD DISPATCH
;; =============================================================================
(defmulti set-sensor-mode!
  "Setter for different edge hardware sensors."
  (fn [sensor-id _mode & _args] sensor-id))

(defmethod set-sensor-mode! :default
  [sensor-id _mode & _args]
  {:status  :ok
   :message (str "Sensor " sensor-id " does not require mode configuration.")})

(defmethod set-sensor-mode! :bme280
  ([_ mode] (set-sensor-mode! :bme280 mode "1"))
  ([_ mode bus]
   (let [{:keys [hum meas]} (get bme280/mode-config-map mode)]
     (if (and hum meas)
       (let [hum-res (write-i2cset! bus
                                    bme280/i2c-addr
                                    (:ctrl-hum bme280/registers)
                                    hum)]
         (if (= :ok (:status hum-res))
           (write-i2cset! bus
                          bme280/i2c-addr
                          (:ctrl-meas bme280/registers)
                          meas)
           hum-res))
       {:status       :error
        :error/reason :invalid-mode}))))

(defmulti read-sensor-readings
  "Reader for different hardware sensors."
  (fn [sensor-id & _args] sensor-id))

(defmethod read-sensor-readings :bme280
  ([_] (read-sensor-readings :bme280 "1" nil))
  ([_ bus] (read-sensor-readings :bme280 bus nil))
  ([_ bus cached-calib]
   (schema/validate! schema/SensorResponse (read-bme280-readings bus cached-calib))))

(defmethod read-sensor-readings :seesaw
  ([_] (read-sensor-readings :seesaw "1"))
  ([_ bus]
   (schema/validate!
    schema/SensorResponse
    (read-seesaw-readings bus))))

(defmethod read-sensor-readings :seesaw/moisture
  ([_] (read-sensor-readings :seesaw/moisture "1"))
  ([_ bus]
   (schema/validate!
    schema/SensorResponse
    (read-seesaw-soil-moisture bus))))

(defmethod read-sensor-readings :seesaw/temperature
  ([_] (read-sensor-readings :seesaw/temperature "1"))
  ([_ bus]
   (schema/validate!
    schema/SensorResponse
    (read-seesaw-soil-temperature bus))))
;; -----------------------------------------------------------------------------
;; Integrant Lifecycle Methods
;; -----------------------------------------------------------------------------

(defmethod ig/init-key :fig-ure/sensors [_ config]
  (println "[Sensors] Initializing hardware I2C sensors..." config)
  (let [bus            (or (:i2c-bus config) "1")
        bme-handshake  (read-bme280-chip-id bus)
        seesaw-id-res  (read-seesaw-soil-reading bus :status :hardware-id 1 identity)
        seesaw-decoded (seesaw/decode-hardware-id (first seesaw-id-res))
        _              (set-sensor-mode! :bme280 :normal bus)
        calib-res      (read-bme280-calibration bus)]
    (println "[Sensors] BME280 Handshake:" bme-handshake)
    (println "[Sensors] Seesaw Handshake:" seesaw-decoded)
    {:status       :ready
     :i2c-bus      bus
     :bme280/id    bme-handshake
     :calibration  (:calibration calib-res)
     :seesaw/id    seesaw-decoded}))

(defmethod ig/halt-key! :fig-ure/sensors [_ state]
  (println "[Sensors] Halting sensor reader..." state))

(comment
  ;; Interactive REPL scratchpad
  (write-i2cset! bme280/i2c-addr
                 (:ctrl-meas bme280/registers)
                 (:mode-normal-x1 bme280/config))

  ;; to snapshot
  (let [dump (:out (fetch-i2cdump "1" bme280/i2c-addr))
        calib (:calibration (bme280/parse-calibration dump))
        raw   (bme280/parse-raw-adc dump)
        readings (bme280/parse-bme280-readings dump calib)]
    {:dump dump
     :raw-adc raw
     :calibration calib
     :readings {:temp (round-2 (:temp readings))
                :press (round-2 (:press readings))
                :hum (round-2 (:hum readings))}})

  (set-sensor-mode! :bme280 :normal)
  (time (fetch-i2cdump "1" bme280/i2c-addr))
  (:out (fetch-i2cdump "1" bme280/i2c-addr))
  (time (read-bme280-readings))

  (read-sensor-readings :bme280)
  (read-sensor-readings :seesaw)
  (read-sensor-readings :seesaw/moisture)
  (read-sensor-readings :seesaw/temperature)

  (read-bme280-calibration)
  (read-bme280-temperature)
  (read-bme280-mode)
  (read-bme280-chip-id))