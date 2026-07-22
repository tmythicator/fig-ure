(ns fig-ure.sensors-test
  (:require [clojure.test :refer [deftest is testing]]
            [fig-ure.sensors :as sensors]
            [matcher-combinators.test :refer [match?]]))

(deftest format-reading-test
  (testing "formats sensor reading into telemetry map structure"
    (is (match? {:sensor/id    :soil-moisture
                 :sensor/value 42.5
                 :sensor/unit  :percent}
                (sensors/format-reading :soil-moisture 42.5 :percent)))))

(deftest valid-reading-test
  (testing "validates sensor readings"
    (let [mock-reading {:sensor/id :soil-moisture
                        :sensor/unit :percent
                        :sensor/timestamp (System/currentTimeMillis)}]

      (clojure.test/are [expected value]
                        (= expected (sensors/valid-reading?
                                     (assoc mock-reading :sensor/value value)))
        true 33.1
        true 0.0
        true 100.0
        true 3
        false -1.0
        false 101.0
        false ""
        false :test))))
