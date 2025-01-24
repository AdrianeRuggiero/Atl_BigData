import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

object SparkStreamingProcessor {
  def main(args: Array[String]): Unit = {

    // 1. Créer une session Spark
    val spark = SparkSession.builder()
      .appName("SparkStreamingKafkaProcessor")
      .master("local[*]") // Mode local
      .getOrCreate()

    // 2. Configuration de Kafka
    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "localhost:9092") // Serveur Kafka
      .option("subscribe", "transaction") // Nom du topic Kafka
      .load()

    // 3. Définir le schéma des messages JSON
    val schema = new StructType()
      .add("idTransaction", StringType)
      .add("typeTransaction", StringType)
      .add("montant", DoubleType)
      .add("devise", StringType)
      .add("date", StringType)
      .add("lieu", StringType)
      .add("moyenPaiement", StringType)
      .add("details", MapType(StringType, StringType))
      .add("utilisateur", MapType(StringType, StringType))

    // 4. Extraire et transformer les données JSON
    val transactionsDF = kafkaDF.selectExpr("CAST(value AS STRING) as json")
      .select(from_json(col("json"), schema).as("data"))
      .select("data.*")

    // 5. Transformation des données

    // a) Conversion USD -> EUR
    val exchangeRate = 0.85 // Taux de conversion
    val transformedDF = transactionsDF
      .withColumn("montant_en_eur",
        when(col("devise") === "USD", col("montant") * exchangeRate)
          .otherwise(col("montant"))
      )
      .withColumn("devise", lit("EUR")) // Mettre à jour la devise

      // b) Ajouter le fuseau horaire
      .withColumn("date_utc", to_utc_timestamp(col("date"), "Europe/Paris"))

      // c) Remplacer la date en string par une valeur Date
      .withColumn("date_parsed", to_date(col("date"), "yyyy-MM-dd"))

      // d) Supprimer les transactions en erreur (montant <= 0 ou null)
      .filter(col("montant_en_eur").isNotNull && col("montant_en_eur") > 0)

      // e) Supprimer les valeurs en None (adresse manquante)
      .filter(col("utilisateur")("adresse").isNotNull)

    // 6. Écrire les résultats transformés
    val query = transformedDF.writeStream
      .outputMode("append") // Écriture des nouveaux résultats uniquement
      .format("console") // Affichage dans la console (remplacez par "parquet" pour écrire dans MinIO)
      .start()

    // 7. Garder le streaming actif
    query.awaitTermination()
  }
}
