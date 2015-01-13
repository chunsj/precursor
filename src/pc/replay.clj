(ns pc.replay
  (:require [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]))

(defn get-document-transactions
  "Gets the broadcasted transactions for a document"
  [db doc]
  (map #(d/entity db (first %))
       (d/q '{:find [?t]
              :in [$ ?doc-id]
              :where [[?t :document/id ?doc-id]
                      [?t :db/txInstant]]}
            db (:db/id doc))))

(defn- ->datom
  [[e a v tx added]]
  {:e e :a a :v v :tx tx :added added})

(defn tx-data [transaction]
  (->> (d/q '{:find [?e ?a ?v ?tx ?op]
              :in [?log ?txid]
              :where [[(tx-data ?log ?txid) [[?e ?a ?v ?tx ?op]]]]}
            (d/log (pcd/conn)) (:db/id transaction))
    (map ->datom)
    set))

(defn copy-transactions [db doc new-doc & {:keys [sleep-ms]
                                           :or {sleep-ms 1000}}]
  (let [conn (pcd/conn)
        tx-datas (->> (get-document-transactions db doc)
                   (sort-by :db/txInstant)
                   (map (fn [t]
                          (->> (tx-data t)
                            (remove #(= (:e %) (:db/id t)))
                            (map #(if (= (:v %) (:db/id doc))
                                    (assoc % :v (:db/id new-doc))
                                    %))))))
        eid-translations (-> (apply concat (map #(map :e %) tx-datas))
                           set
                           (disj (:db/id doc))
                           (zipmap (repeatedly #(first (pcd/generate-eids conn 1))))
                           (assoc (:db/id doc) (:db/id new-doc)))]
    (doseq [tx-data tx-datas]
      (def my-tx-data tx-data)
      (let [txid (d/tempid :db.part/tx)]
        @(d/transact conn (conj (map #(-> %
                                        (update-in [:e] eid-translations)
                                        pcd/datom->transaction)
                                     tx-data)
                                {:db/id txid
                                 :document/id (:db/id new-doc)
                                 :transaction/broadcast true}))
        (Thread/sleep sleep-ms)))))