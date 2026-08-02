(ns fig-ure.stream-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [fig-ure.camera :as camera]
   [fig-ure.stream]
   [integrant.core :as ig]))

(deftest stream-component-lifecycle-test
  (testing "integrant initializes and halts :fig-ure/stream component cleanly"
    (let [sys (ig/init-key :fig-ure/stream {:auto-timelapse? false})]
      (is (= :ready (:status sys)))
      (is (some? (:stop-chan sys)))
      (is (nil? (ig/halt-key! :fig-ure/stream sys))))))

(deftest start-timelapse-loop-resilience-test
  (testing "timelapse loop handles failing snapshot without throwing uncaught exceptions"
    (with-redefs [camera/take-snapshot! (fn [& _] (throw (RuntimeException. "Camera hardware error")))]
      (let [sys (ig/init-key :fig-ure/stream {:auto-timelapse? true
                                              :timelapse-interval-ms 20})]
        (is (= :ready (:status sys)))
        (Thread/sleep 60)
        (is (nil? (ig/halt-key! :fig-ure/stream sys)))))))
