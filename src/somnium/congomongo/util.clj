(ns somnium.congomongo.util)

(defn named [s]
  "convenience for interchangeably handling keywords and strings"
  (if (keyword? s) (name s) s))

(defn partition-map
  "creates a hash-map of first and rest pairs from a partitioned collection"
  [coll n]
  (apply merge
         (map #(hash-map (first %) (rest %))
              (partition n coll))))

(defn map-keys
  "applies f to each key in h"
  [f h]
  (zipmap (map f (keys h)) (vals h)))

(defmacro case
  "save a few key-strokes with case instead of cond:
   instead of (cond (= x 1) :foo (= x 2) :bar) you get
              (case x
               1 :foo
               2 :bar)"
  [value & clauses]
  (let [with-preds (for [[x y] (apply hash-map clauses)]
                     (if (= :else x)
                       [x y]
                       [(list = value x) y]))
        as-clauses (apply concat with-preds)]
    `(cond ~@as-clauses)))
