import java.util.{Properties, UUID}
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import com.google.gson.Gson
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

// Classe représentant une transaction enrichie
case class Transaction(
                        idTransaction: String,
                        typeTransaction: String,
                        montant: Double,
                        devise: String,
                        date: String,
                        lieu: String,
                        moyenPaiement: Option[String],
                        details: Map[String, Any],
                        utilisateur: Map[String, Any]
                      )

object ScalaKafkaProducer {

  def main(args: Array[String]): Unit = {

    // Initialisation d'Akka ActorSystem et Materializer
    implicit val system: ActorSystem = ActorSystem("KafkaProducerSystem")
    implicit val materializer: Materializer = ActorMaterializer()
    implicit val ec: ExecutionContext = system.dispatcher

    // Configuration Kafka
    val bootstrapServers = "localhost:9092"
    val topic = "transaction"

    val producerSettings = new Properties()
    producerSettings.put("bootstrap.servers", bootstrapServers)
    producerSettings.put("key.serializer", classOf[StringSerializer].getName)
    producerSettings.put("value.serializer", classOf[StringSerializer].getName)

    val kafkaProducer = new KafkaProducer[String, String](producerSettings)
    val gson = new Gson()

    // Listes pour générer des données aléatoires
    val paymentMethods = Seq("carte_de_credit", "especes", "virement_bancaire")
    val cities = Seq("Paris", "Marseille", "Lyon", "Toulouse", "Nice", "Nantes")
    val streets = Seq("Rue de la République", "Rue de Paris", "Rue Auguste Delaune")

    // Fonction pour générer une transaction aléatoire
    def generateTransaction(): Transaction = {
      val transactionTypes = Seq("achat", "remboursement", "transfert")
      val currentDateTime = java.time.LocalDateTime.now().toString

      Transaction(
        idTransaction = UUID.randomUUID().toString,
        typeTransaction = Random.shuffle(transactionTypes).head,
        montant = 10.0 + Random.nextDouble() * (1000.0 - 10.0),
        devise = if (Random.nextBoolean()) "USD" else "EUR",
        date = currentDateTime,
        lieu = s"${Random.shuffle(cities).head}, ${Random.shuffle(streets).head}",
        moyenPaiement = Some(Random.shuffle(paymentMethods).head),
        details = Map(
          "produit" -> s"Produit${Random.nextInt(100)}",
          "quantite" -> (1 + Random.nextInt(10)),
          "prixUnitaire" -> (10 + Random.nextInt(200))
        ),
        utilisateur = Map(
          "idUtilisateur" -> s"User${Random.nextInt(1000)}",
          "nom" -> s"Utilisateur${Random.nextInt(1000)}",
          "adresse" -> s"${Random.nextInt(1000)} ${Random.shuffle(streets).head}, ${Random.shuffle(cities).head}",
          "email" -> s"utilisateur${Random.nextInt(1000)}@example.com"
        )
      )
    }

    // Source Akka Streams pour produire une transaction chaque seconde
    val transactionsSource = Source.tick(
      scala.concurrent.duration.Duration.Zero, // Démarre immédiatement
      scala.concurrent.duration.Duration(1, "second"), // Intervalle de 1 seconde
      ()
    ).map { _ =>
      val transaction = generateTransaction()
      println(s"Transaction générée : $transaction") // Debugging pour voir les transactions générées
      transaction
    }

    // Sink pour envoyer les transactions à Kafka
    val stream = transactionsSource.runForeach { transaction =>
      try {
        val jsonTransaction = gson.toJson(transaction) // Sérialisation en JSON
        val record = new ProducerRecord[String, String](topic, transaction.idTransaction, jsonTransaction)
        kafkaProducer.send(record)
        println(s"Transaction envoyée à Kafka : $jsonTransaction") // Debugging pour confirmer l'envoi
      } catch {
        case e: Exception =>
          System.err.println(s"Erreur lors de l'envoi de la transaction : ${e.getMessage}")
      }
    }

    // Garder l'application active
    stream.onComplete { _ =>
      kafkaProducer.close()
      system.terminate()
    }

    // Garder le programme en vie indéfiniment
    scala.io.StdIn.readLine() // Attend une entrée pour terminer manuellement
  }
}

