package tech.cryptonomic.conseil.common.ethereum

import java.sql.Timestamp
import java.time.Instant
import cats.Id
import cats.effect.{Concurrent, Resource}
import slick.jdbc.PostgresProfile.api._
import tech.cryptonomic.conseil.common.io.Logging.ConseilLogSupport
import tech.cryptonomic.conseil.common.util.Conversion
import tech.cryptonomic.conseil.common.util.Conversion.Syntax._
import tech.cryptonomic.conseil.common.ethereum.rpc.json.{Block, Log, Transaction}
import tech.cryptonomic.conseil.common.ethereum.EthereumPersistence._
import tech.cryptonomic.conseil.common.ethereum.rpc.json.TransactionReceipt
import tech.cryptonomic.conseil.common.ethereum.domain.{Account, TokenBalance, TokenTransfer}

import scala.concurrent.ExecutionContext

/**
  * Ethereum persistence into the database using Slick.
  */
class EthereumPersistence[F[_]: Concurrent] extends ConseilLogSupport {

  /**
    * Create [[DBIO]] seq with blocks and transactions that can be wrap into one transaction.
    *
    * @param block        JSON_RPC block
    * @param transactions JSON_RPC block's transactions
    * @param receipts     JSON_RPC block's transactions receipts
    */
  def createBlock(
      block: Block,
      transactions: List[Transaction],
      receipts: List[TransactionReceipt]
  ): DBIOAction[Unit, NoStream, Effect.Write] = {
    val timestamp = Timestamp.from(Instant.ofEpochSecond(Integer.decode(block.timestamp).toLong))
    DBIO.seq(
      Tables.Blocks += block.convertTo[Tables.BlocksRow],
      Tables.Transactions ++= transactions
        .map(_.convertTo[Tables.TransactionsRow])
        .map(_.copy(timestamp = Some(timestamp))),
      Tables.Receipts ++= receipts.map(_.convertTo[Tables.ReceiptsRow]).map(_.copy(timestamp = Some(timestamp))),
      Tables.Logs ++= receipts.flatMap(_.logs).map(_.convertTo[Tables.LogsRow]).map(_.copy(timestamp = Some(timestamp)))
    )
  }

  /**
    * Create [[DBIO]] seq with token transfers.
    *
    * @param tokenTransfers token transfer events data
    */
  def createTokenTransfers(tokenTransfers: List[TokenTransfer]): DBIO[Option[Int]] =
    Tables.TokenTransfers ++= tokenTransfers.map(_.convertTo[Tables.TokenTransfersRow])

  /**
    * Create [[DBIO]] seq with token balances.
    *
    * @param tokenBalances token balanceOf() data
    */
  def createTokenBalances(tokenBalances: List[TokenBalance]): DBIO[Option[Int]] =
    Tables.TokensHistory ++= tokenBalances.map(_.convertTo[Tables.TokensHistoryRow])

  /**
    * Create [[DBIO]] seq with contract account balances.
    *
    * @param contractAccounts contract account eth_getBalance data
    */
  def createContractAccounts(contractAccounts: List[Account]): DBIO[Option[Int]] = {
    import tech.cryptonomic.conseil.common.sql.CustomProfileExtension.api._
    Tables.Accounts.insertOrUpdateAll(contractAccounts.map(_.convertTo[Tables.AccountsRow]))
  }

  /**
    * Create [[DBIO]] seq with account balances.
    *
    * @param accounts account eth_getBalance data
    */
  def createAccountBalances(accounts: List[Account]): DBIO[Option[Int]] =
    Tables.AccountsHistory ++= accounts.map(_.convertTo[Tables.AccountsHistoryRow])

  /**
    * Create [[DBIO]] seq with updated account balances.
    *
    * @param accounts account eth_getBalance data
    */
  def upsertAccounts(accounts: List[Account])(implicit
      ec: ExecutionContext
  ): DBIOAction[List[Unit], NoStream, Effect.Read with Effect.Write with Effect.Transactional] = {
    import tech.cryptonomic.conseil.common.sql.CustomProfileExtension.api._
    DBIO.sequence(
      accounts.map { account =>
        (for {
          existing <- Tables.Accounts.filter(_.address === account.address).result.headOption
          row = existing.map(
            _.copy(
              blockHash = account.blockHash,
              blockLevel = Integer.decode(account.blockNumber),
              timestamp = Some(Timestamp.from(Instant.ofEpochSecond(Integer.decode(account.timestamp).toLong))),
              balance = account.balance
            )
          ) getOrElse account.convertTo[Tables.AccountsRow]
          _ <- Tables.Accounts.insertOrUpdate(row)
        } yield ()).transactionally
      }
    )
  }

  /**
    * Get sequence of existing blocks heights from the database.
    *
    * @param range Inclusive range of the block's height
    */
  def getIndexedBlockHeights(range: Range.Inclusive): DBIO[Seq[Int]] =
    Tables.Blocks.filter(_.level between (range.start, range.end)).map(_.level).result

  /**
    * Get the latest block from the database.
    */
  def getLatestIndexedBlock: DBIO[Option[Tables.BlocksRow]] =
    Tables.Blocks.sortBy(_.level.desc).take(1).result.headOption

}

object EthereumPersistence {

  /**
    * Create [[cats.Resource]] with [[EthereumPersistence]].
    */
  def resource[F[_]: Concurrent]: Resource[F, EthereumPersistence[F]] =
    Resource.pure(new EthereumPersistence[F])

  /**
    * Convert form [[Block]] to [[Tables.BlocksRow]]
    * TODO: This conversion should be done with the Chimney,
    *       but it's blocked due to the https://github.com/scala/bug/issues/11157
    */
  implicit val blockToBlocksRow: Conversion[Id, Block, Tables.BlocksRow] =
    new Conversion[Id, Block, Tables.BlocksRow] {
      override def convert(from: Block) =
        Tables.BlocksRow(
          hash = from.hash,
          level = Integer.decode(from.number),
          difficulty = Utils.hexStringToBigDecimal(from.difficulty),
          extraData = from.extraData,
          gasLimit = Utils.hexStringToBigDecimal(from.gasLimit),
          gasUsed = Utils.hexStringToBigDecimal(from.gasUsed),
          logsBloom = from.logsBloom,
          miner = from.miner,
          mixHash = from.mixHash,
          nonce = from.nonce,
          parentHash = from.parentHash,
          receiptsRoot = from.receiptsRoot,
          sha3Uncles = from.sha3Uncles,
          size = Integer.decode(from.size),
          stateRoot = from.stateRoot,
          totalDifficulty = Utils.hexStringToBigDecimal(from.totalDifficulty),
          transactionsRoot = from.transactionsRoot,
          uncles = Option(from.uncles).filter(_.nonEmpty).map(_.mkString(",")),
          timestamp = Timestamp.from(Instant.ofEpochSecond(Integer.decode(from.timestamp).toLong))
        )
    }

  /**
    * Convert form [[Transaction]] to [[Tables.TransactionsRow]]
    * TODO: This conversion should be done with the Chimney,
    *       but it's blocked due to the https://github.com/scala/bug/issues/11157
    */
  implicit val transactionToTransactionsRow: Conversion[Id, Transaction, Tables.TransactionsRow] =
    new Conversion[Id, Transaction, Tables.TransactionsRow] {
      override def convert(from: Transaction) =
        Tables.TransactionsRow(
          hash = from.hash,
          blockHash = from.blockHash,
          blockLevel = Integer.decode(from.blockNumber),
          source = from.from,
          gas = Utils.hexStringToBigDecimal(from.gas),
          gasPrice = Utils.hexStringToBigDecimal(from.gasPrice),
          input = Utils.truncateEmptyHexString(from.input),
          nonce = from.nonce,
          destination = from.to,
          transactionIndex = Integer.decode(from.transactionIndex),
          amount = Utils.hexStringToBigDecimal(from.value),
          v = from.v,
          r = from.r,
          s = from.s
        )
    }

  /**
    * Convert form [[TransactionReceipt]] to [[Tables.ReceiptsRow]]
    * TODO: This conversion should be done with the Chimney,
    *       but it's blocked due to the https://github.com/scala/bug/issues/11157
    */
  implicit val transactionReceiptToReceiptsRow: Conversion[Id, TransactionReceipt, Tables.ReceiptsRow] =
    new Conversion[Id, TransactionReceipt, Tables.ReceiptsRow] {
      override def convert(from: TransactionReceipt) =
        Tables.ReceiptsRow(
          blockHash = from.blockHash,
          blockLevel = Integer.decode(from.blockNumber),
          transactionHash = from.transactionHash,
          transactionIndex = Integer.decode(from.transactionIndex),
          contractAddress = from.contractAddress,
          cumulativeGasUsed = Utils.hexStringToBigDecimal(from.cumulativeGasUsed),
          gasUsed = Utils.hexStringToBigDecimal(from.gasUsed),
          logsBloom = from.logsBloom,
          status = from.status,
          root = from.root
        )
    }

  /**
    * Convert form [[Log]] to [[Tables.LogsRow]]
    * TODO: This conversion should be done with the Chimney,
    *       but it's blocked due to the https://github.com/scala/bug/issues/11157
    */
  implicit val logToLogsRow: Conversion[Id, Log, Tables.LogsRow] =
    new Conversion[Id, Log, Tables.LogsRow] {
      override def convert(from: Log) =
        Tables.LogsRow(
          address = from.address,
          blockHash = from.blockHash,
          blockLevel = Integer.decode(from.blockNumber),
          data = from.data,
          logIndex = Integer.decode(from.logIndex),
          removed = from.removed,
          topics = from.topics.mkString(","),
          transactionHash = from.transactionHash,
          transactionIndex = Integer.decode(from.transactionIndex)
        )
    }

  /**
    * Convert form [[Log]] to [[Tables.TokenTransfersRow]]
    * TODO: This conversion should be done with the Chimney,
    *       but it's blocked due to the https://github.com/scala/bug/issues/11157
    */
  implicit val logToTokenTransfersRow: Conversion[Id, Log, Tables.TokenTransfersRow] =
    new Conversion[Id, Log, Tables.TokenTransfersRow] {
      override def convert(from: Log) =
        Tables.TokenTransfersRow(
          tokenAddress = from.address,
          blockHash = from.blockHash,
          blockLevel = Integer.decode(from.blockNumber),
          transactionHash = from.transactionHash,
          logIndex = Integer.decode(from.logIndex),
          fromAddress = from.topics(1),
          toAddress = from.topics(2),
          value = Utils.hexStringToBigDecimal(from.data)
        )
    }

  /**
    * Convert form [[TokenTransfer]] to [[Tables.TokenTransfersRow]]
    * TODO: This conversion should be done with the Chimney,
    *       but it's blocked due to the https://github.com/scala/bug/issues/11157
    */
  implicit val tokenTransferToTokenTransfersRow: Conversion[Id, TokenTransfer, Tables.TokenTransfersRow] =
    new Conversion[Id, TokenTransfer, Tables.TokenTransfersRow] {
      override def convert(from: TokenTransfer) =
        Tables.TokenTransfersRow(
          tokenAddress = from.tokenAddress,
          blockHash = from.blockHash,
          blockLevel = from.blockNumber,
          timestamp = Some(Timestamp.from(Instant.ofEpochSecond(Integer.decode(from.timestamp).toLong))),
          transactionHash = from.transactionHash,
          logIndex = Integer.decode(from.logIndex),
          fromAddress = from.fromAddress,
          toAddress = from.toAddress,
          value = from.value
        )
    }

  /**
    * Convert form [[TokenBalance]] to [[Tables.TokensHistoryRow]]
    * TODO: This conversion should be done with the Chimney,
    *       but it's blocked due to the https://github.com/scala/bug/issues/11157
    */
  implicit val tokenBalanceToTokensHistoryRow: Conversion[Id, TokenBalance, Tables.TokensHistoryRow] =
    new Conversion[Id, TokenBalance, Tables.TokensHistoryRow] {
      override def convert(from: TokenBalance) =
        Tables.TokensHistoryRow(
          accountAddress = from.accountAddress,
          blockHash = from.blockHash,
          blockLevel = from.blockNumber,
          transactionHash = from.transactionHash,
          tokenAddress = from.tokenAddress,
          value = from.value,
          asof = from.asof
        )
    }

  /**
    * Convert form [[Account]] to [[Tables.AccountsRow]]
    * TODO: This conversion should be done with the Chimney,
    *       but it's blocked due to the https://github.com/scala/bug/issues/11157
    */
  implicit val accountToAccountsRow: Conversion[Id, Account, Tables.AccountsRow] =
    new Conversion[Id, Account, Tables.AccountsRow] {
      override def convert(from: Account) =
        Tables.AccountsRow(
          address = from.address,
          blockHash = from.blockHash,
          blockLevel = Integer.decode(from.blockNumber),
          timestamp = Some(Timestamp.from(Instant.ofEpochSecond(Integer.decode(from.timestamp).toLong))),
          balance = from.balance,
          bytecode = from.bytecode.map(_.value),
          bytecodeHash = from.bytecode.map(_.hash),
          tokenStandard = from.tokenStandard.map(_.value),
          name = from.name.map(Utils.truncateEmptyHexString),
          symbol = from.symbol.map(Utils.truncateEmptyHexString),
          decimals = from.decimals,
          totalSupply = from.totalSupply
        )
    }

  /**
    * Convert form [[Account]] to [[Tables.AccountsHistoryRow]]
    * TODO: This conversion should be done with the Chimney,
    *       but it's blocked due to the https://github.com/scala/bug/issues/11157
    */
  implicit val accountToAccountsHistoryRow: Conversion[Id, Account, Tables.AccountsHistoryRow] =
    new Conversion[Id, Account, Tables.AccountsHistoryRow] {
      override def convert(from: Account) =
        Tables.AccountsHistoryRow(
          address = from.address,
          blockHash = from.blockHash,
          blockLevel = Integer.decode(from.blockNumber),
          balance = from.balance,
          asof = Timestamp.from(Instant.ofEpochSecond(Integer.decode(from.timestamp).toLong))
        )
    }
}
