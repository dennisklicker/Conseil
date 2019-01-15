package tech.cryptonomic.conseil.config

import tech.cryptonomic.conseil.config.Platforms._
import tech.cryptonomic.conseil.util.ConfigUtil.Pureconfig.loadAkkaStreamingClientConfig
import pureconfig.{ConfigFieldMapping, CamelCase, loadConfig}
import pureconfig.generic.auto._
import pureconfig.generic.ProductHint
import pureconfig.error.{ConfigReaderFailures, ConfigReaderFailure}

/** wraps all configuration needed to run Lorre */
trait LorreAppConfig {
  import LorreAppConfig._

  /** Reads all configuration upstart, will print all errors encountered during loading */
  protected def loadApplicationConfiguration(commandLineArgs: Array[String]): Either[ConfigReaderFailures, CombinedConfiguration] = {
    //applies convention to uses CamelCase when reading config fields
    implicit def hint[T] = ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))

    val loadedConf = for {
      network <- readArgs(commandLineArgs)
      lorre <- loadConfig[LorreConfiguration](namespace = "lorre")
      nodeRequests <- loadConfig[NetworkCallsConfiguration]("")
      node <- loadConfig[TezosNodeConfiguration](namespace = s"platforms.tezos.$network.node")
      streamingClient <- loadAkkaStreamingClientConfig(namespace = "akka.tezos-streaming-client")
      fetching <- loadConfig[BatchFetchConfiguration](namespace = "batchedFetches")
    } yield CombinedConfiguration(lorre, TezosConfiguration(network, node), nodeRequests, streamingClient, fetching)

    //something went wrong
    loadedConf.left.foreach {
      failures =>
        printConfigurationError(context = "Lorre application", failures.toList.mkString("\n\n"))
    }
    loadedConf
  }

  /** Use the pureconfig convention to handle configuration from the command line */
  protected def readArgs(args: Array[String]): Either[ConfigReaderFailures, String] =
    if (args.length > 0) Right(args(0))
    else Left(ConfigReaderFailures(
      //custom-made to adapt from a file-parsing-based error to missing cli args
      new ConfigReaderFailure {
        val description = """
          | No tezos network was provided to connect to
          | Please provide a valid network as an argument to the command line""".stripMargin

        val location = None

        override def toString(): String = description
      }
    ))

}

object LorreAppConfig {

  /** Collects all different aspects involved for Lorre */
  final case class CombinedConfiguration(
    lorre: LorreConfiguration,
    tezos: TezosConfiguration,
    nodeRequests: NetworkCallsConfiguration,
    streamingClientPool: HttpStreamingConfiguration,
    batching: BatchFetchConfiguration
  )

}