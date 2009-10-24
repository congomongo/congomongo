(ns congomongo-test
  (:use clojure.test
        somnium.congomongo
        somnium.congomongo.coerce
        somnium.congomongo.config))

(deftest config
  (mongo! :db "test")
  (is (= [:keywords :query-operators :object-ids]
         (@*mongo-config* :coerce-to)))
  (is (= [:keywords] (@*mongo-config* :coerce-from))))

(deftest coercion-without-save
  (let [test-map {:foo "a" :bar [{:baz "zonk"} :zam]}
        as-obj   (map-to-object test-map)
        and-back (object-to-map test-map)]
    (is (= com.mongodb.BasicDBObject (class as-obj)))
    (is (= and-back test-map))))