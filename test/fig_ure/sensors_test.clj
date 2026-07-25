(ns fig-ure.sensors-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.test :refer [are deftest is testing]]
            [fig-ure.sensors :as sensors]
            [fig-ure.sensors.bme280 :as bme280]
            [matcher-combinators.test :refer [match?]]))

(deftest format-reading-test
  (testing "formats sensor reading into telemetry map structure"
    (is (match? {:sensor/id    :soil-moisture
                 :sensor/value 42.5
                 :sensor/unit  :percent}
                (sensors/format-reading :soil-moisture 42.5 :percent)))))

(deftest valid-percent-reading-test
  (testing "validates sensor readings (percent-unit)"
    (let [mock-reading {:sensor/id        :soil-moisture
                        :sensor/unit      :percent
                        :sensor/timestamp (System/currentTimeMillis)}]
      (are [expected value]
           (= expected (sensors/valid-percent-reading?
                        (assoc mock-reading :sensor/value value)))
        true 33.1
        true 0.0
        true 100.0
        true 3
        false -1.0
        false 101.0
        false ""
        false :test)))

  (testing "validates sensor readings (non-percent-unit)"
    (let [mock-reading {:sensor/id        :soil-moisture
                        :sensor/unit      :temperature
                        :sensor/value     28.3
                        :sensor/timestamp (System/currentTimeMillis)}]
      (is (not (sensors/valid-percent-reading? mock-reading))))))

(deftest calculate-average-percent-value-test
  (testing "calculates average over valid percent readings, ignoring invalid ones"
    (let [mock-readings [{:sensor/id :soil-moisture :sensor/unit :percent :sensor/value 100}
                         {:sensor/id :soil-moisture :sensor/unit :percent :sensor/value 120} ;; ignore
                         {:sensor/id :soil-moisture :sensor/unit :percent :sensor/value 50}
                         {:sensor/id :soil-moisture :sensor/unit :celcius :sensor/value 30} ;; ignore
                         {:sensor/id :soil-moisture :sensor/unit :percent :sensor/value 75}]]
      (is (= 75 (sensors/calculate-average-percent-value mock-readings))))))

(deftest read-bme280-chip-id-test
  (testing "reads BME280 chip ID successfully using mocked i2cget shell call"
    (with-redefs [sh (fn [& _] {:exit 0 :out "0x60" :err ""})]
      (is (match? {:status         :ok
                   :bme280/chip-id (:chip-val bme280/config)
                   :bme280/valid?  true}
                  (sensors/read-bme280-chip-id)))))

  (testing "handles hardware I2C read failure gracefully"
    (with-redefs [sh (fn [& _] {:exit 1 :out "" :err "Read failed"})]
      (is (match? {:status        :error
                   :error/reason  :i2c-read-failed
                   :error/message "Read failed"}
                  (sensors/read-bme280-chip-id))))))

(deftest read-bme280-mode-test
  (testing "reads current operating mode successfully for different modes"
    (are [expected-mode hex-out]
         (with-redefs [sh (fn [& _] {:exit 0 :out hex-out :err ""})]
           (is (match? {:status      :ok
                        :bme280/mode expected-mode}
                       (sensors/read-bme280-mode))))
      :normal "0x27"
      :sleep "0x00"
      :forced "0x25"
      :unknown "0x99"))

  (testing "handles hardware I2C read failure when getting mode"
    (with-redefs [sh (fn [& _] {:exit 1 :out "" :err "Read mode failed"})]
      (is (match? {:status        :error
                   :error/reason  :i2c-read-failed
                   :error/message "Read mode failed"}
                  (sensors/read-bme280-mode))))))

(deftest set-sensor-mode!-test
  (testing "sets BME280 mode successfully by writing to ctrl-hum and ctrl-meas"
    (with-redefs [sh (fn [& _] {:exit 0 :out "" :err ""})]
      (is (match? {:status :ok}
                  (sensors/set-sensor-mode! :bme280 :normal)))))

  (testing "returns error status when invalid mode is supplied"
    (is (match? {:status       :error
                 :error/reason :invalid-mode}
                (sensors/set-sensor-mode! :bme280 :invalid-mode))))

  (testing "handles write failure gracefully"
    (with-redefs [sh (fn [& _] {:exit 1 :out "" :err "Write failed"})]
      (is (match? {:status       :error
                   :error/reason :i2c-write-failed}
                  (sensors/set-sensor-mode! :bme280 :normal))))))

(deftest read-bme280-readings-test
  (testing "reads all telemetry metrics (temp, pressure, humidity) atomically via i2cdump mock"
    (let [fixture (:dump (edn/read-string (slurp (io/file "test/fixtures/bme280_fixture.edn"))))]
      (with-redefs [sh (fn [& _] {:exit 0 :out fixture :err ""})]
        (let [res (sensors/read-bme280-readings)]
          (is (= :ok (:status res)))
          (is (= 3 (count (:readings res))))
          (is (= [:bme280-temperature :bme280-pressure :bme280-humidity]
                 (map :sensor/id (:readings res))))))))

  (testing "handles I2C read failure gracefully when reading all metrics"
    (with-redefs [sh (fn [& _] {:exit 1 :out "" :err "Dump failed"})]
      (is (match? {:status        :error
                   :error/reason  :i2c-read-failed
                   :error/message "Dump failed"}
                  (sensors/read-bme280-readings))))))
