(ns fig-ure.sensors.bme280-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [fig-ure.sensors :as sensors]
            [fig-ure.sensors.bme280 :as bme280]
            [matcher-combinators.test :refer [match?]]))

(deftest parse-i2cdump-chip-id-test
  (testing "parses valid BME280 chip ID (0x60) from real hardware i2cdump fixture"
    (let [hardware-fixture (slurp (io/file "test/fixtures/bme280_i2cdump.txt"))]
      (is (match? {:status         :ok
                   :bme280/chip-id "0x60"
                   :bme280/valid?  true}
                  (bme280/parse-chip-id hardware-fixture)))))

  (testing "returns error status when d0 line is missing or corrupted"
    (is (match? {:status       :error
                 :error/reason :parse-failed}
                (bme280/parse-chip-id "corrupted text without d0 line")))))

(deftest parse-bme280-temperature-test
  (testing "parses raw ADC temperature bytes from real hardware i2cdump fixture"
    (let [hardware-fixture (slurp (io/file "test/fixtures/bme280_i2cdump.txt"))]
      (is (match? {:status :ok
                   :reading {:sensor/id    :bme280-temperature
                             :sensor/value 524288
                             :sensor/unit  :raw-adc}}
                  (bme280/parse-temperature hardware-fixture sensors/format-reading)))))

  (testing "returns error status when f0 line is missing or corrupted"
    (is (match? {:status       :error
                 :error/reason :parse-failed}
                (bme280/parse-temperature "corrupted text without f0 line" sensors/format-reading)))))
