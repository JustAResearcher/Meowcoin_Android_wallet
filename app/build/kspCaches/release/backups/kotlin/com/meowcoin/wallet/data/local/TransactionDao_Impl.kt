package com.meowcoin.wallet.`data`.local

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.getTotalChangedRows
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class TransactionDao_Impl(
  __db: RoomDatabase,
) : TransactionDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfTransactionEntity: EntityInsertAdapter<TransactionEntity>

  private val __updateAdapterOfTransactionEntity: EntityDeleteOrUpdateAdapter<TransactionEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfTransactionEntity = object : EntityInsertAdapter<TransactionEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `transactions` (`txId`,`walletAddress`,`amount`,`fee`,`toAddress`,`fromAddress`,`confirmations`,`blockHeight`,`timestamp`,`status`) VALUES (?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: TransactionEntity) {
        statement.bindText(1, entity.txId)
        statement.bindText(2, entity.walletAddress)
        statement.bindLong(3, entity.amount)
        statement.bindLong(4, entity.fee)
        statement.bindText(5, entity.toAddress)
        statement.bindText(6, entity.fromAddress)
        statement.bindLong(7, entity.confirmations.toLong())
        statement.bindLong(8, entity.blockHeight.toLong())
        statement.bindLong(9, entity.timestamp)
        statement.bindText(10, entity.status)
      }
    }
    this.__updateAdapterOfTransactionEntity = object :
        EntityDeleteOrUpdateAdapter<TransactionEntity>() {
      protected override fun createQuery(): String =
          "UPDATE OR ABORT `transactions` SET `txId` = ?,`walletAddress` = ?,`amount` = ?,`fee` = ?,`toAddress` = ?,`fromAddress` = ?,`confirmations` = ?,`blockHeight` = ?,`timestamp` = ?,`status` = ? WHERE `txId` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: TransactionEntity) {
        statement.bindText(1, entity.txId)
        statement.bindText(2, entity.walletAddress)
        statement.bindLong(3, entity.amount)
        statement.bindLong(4, entity.fee)
        statement.bindText(5, entity.toAddress)
        statement.bindText(6, entity.fromAddress)
        statement.bindLong(7, entity.confirmations.toLong())
        statement.bindLong(8, entity.blockHeight.toLong())
        statement.bindLong(9, entity.timestamp)
        statement.bindText(10, entity.status)
        statement.bindText(11, entity.txId)
      }
    }
  }

  public override suspend fun insertTransaction(transaction: TransactionEntity): Long =
      performSuspending(__db, false, true) { _connection ->
    val _result: Long = __insertAdapterOfTransactionEntity.insertAndReturnId(_connection,
        transaction)
    _result
  }

  public override suspend fun insertTransactions(transactions: List<TransactionEntity>): List<Long>
      = performSuspending(__db, false, true) { _connection ->
    val _result: List<Long> = __insertAdapterOfTransactionEntity.insertAndReturnIdsList(_connection,
        transactions)
    _result
  }

  public override suspend fun updateTransaction(transaction: TransactionEntity): Int =
      performSuspending(__db, false, true) { _connection ->
    var _result: Int = 0
    _result += __updateAdapterOfTransactionEntity.handle(_connection, transaction)
    _result
  }

  public override fun getTransactionsForWallet(address: String): Flow<List<TransactionEntity>> {
    val _sql: String = "SELECT * FROM transactions WHERE walletAddress = ? ORDER BY timestamp DESC"
    return createFlow(__db, false, arrayOf("transactions")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, address)
        val _cursorIndexOfTxId: Int = getColumnIndexOrThrow(_stmt, "txId")
        val _cursorIndexOfWalletAddress: Int = getColumnIndexOrThrow(_stmt, "walletAddress")
        val _cursorIndexOfAmount: Int = getColumnIndexOrThrow(_stmt, "amount")
        val _cursorIndexOfFee: Int = getColumnIndexOrThrow(_stmt, "fee")
        val _cursorIndexOfToAddress: Int = getColumnIndexOrThrow(_stmt, "toAddress")
        val _cursorIndexOfFromAddress: Int = getColumnIndexOrThrow(_stmt, "fromAddress")
        val _cursorIndexOfConfirmations: Int = getColumnIndexOrThrow(_stmt, "confirmations")
        val _cursorIndexOfBlockHeight: Int = getColumnIndexOrThrow(_stmt, "blockHeight")
        val _cursorIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _cursorIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _result: MutableList<TransactionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: TransactionEntity
          val _tmpTxId: String
          _tmpTxId = _stmt.getText(_cursorIndexOfTxId)
          val _tmpWalletAddress: String
          _tmpWalletAddress = _stmt.getText(_cursorIndexOfWalletAddress)
          val _tmpAmount: Long
          _tmpAmount = _stmt.getLong(_cursorIndexOfAmount)
          val _tmpFee: Long
          _tmpFee = _stmt.getLong(_cursorIndexOfFee)
          val _tmpToAddress: String
          _tmpToAddress = _stmt.getText(_cursorIndexOfToAddress)
          val _tmpFromAddress: String
          _tmpFromAddress = _stmt.getText(_cursorIndexOfFromAddress)
          val _tmpConfirmations: Int
          _tmpConfirmations = _stmt.getLong(_cursorIndexOfConfirmations).toInt()
          val _tmpBlockHeight: Int
          _tmpBlockHeight = _stmt.getLong(_cursorIndexOfBlockHeight).toInt()
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_cursorIndexOfTimestamp)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_cursorIndexOfStatus)
          _item =
              TransactionEntity(_tmpTxId,_tmpWalletAddress,_tmpAmount,_tmpFee,_tmpToAddress,_tmpFromAddress,_tmpConfirmations,_tmpBlockHeight,_tmpTimestamp,_tmpStatus)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getRecentTransactions(address: String, limit: Int):
      Flow<List<TransactionEntity>> {
    val _sql: String =
        "SELECT * FROM transactions WHERE walletAddress = ? ORDER BY timestamp DESC LIMIT ?"
    return createFlow(__db, false, arrayOf("transactions")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, address)
        _argIndex = 2
        _stmt.bindLong(_argIndex, limit.toLong())
        val _cursorIndexOfTxId: Int = getColumnIndexOrThrow(_stmt, "txId")
        val _cursorIndexOfWalletAddress: Int = getColumnIndexOrThrow(_stmt, "walletAddress")
        val _cursorIndexOfAmount: Int = getColumnIndexOrThrow(_stmt, "amount")
        val _cursorIndexOfFee: Int = getColumnIndexOrThrow(_stmt, "fee")
        val _cursorIndexOfToAddress: Int = getColumnIndexOrThrow(_stmt, "toAddress")
        val _cursorIndexOfFromAddress: Int = getColumnIndexOrThrow(_stmt, "fromAddress")
        val _cursorIndexOfConfirmations: Int = getColumnIndexOrThrow(_stmt, "confirmations")
        val _cursorIndexOfBlockHeight: Int = getColumnIndexOrThrow(_stmt, "blockHeight")
        val _cursorIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _cursorIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _result: MutableList<TransactionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: TransactionEntity
          val _tmpTxId: String
          _tmpTxId = _stmt.getText(_cursorIndexOfTxId)
          val _tmpWalletAddress: String
          _tmpWalletAddress = _stmt.getText(_cursorIndexOfWalletAddress)
          val _tmpAmount: Long
          _tmpAmount = _stmt.getLong(_cursorIndexOfAmount)
          val _tmpFee: Long
          _tmpFee = _stmt.getLong(_cursorIndexOfFee)
          val _tmpToAddress: String
          _tmpToAddress = _stmt.getText(_cursorIndexOfToAddress)
          val _tmpFromAddress: String
          _tmpFromAddress = _stmt.getText(_cursorIndexOfFromAddress)
          val _tmpConfirmations: Int
          _tmpConfirmations = _stmt.getLong(_cursorIndexOfConfirmations).toInt()
          val _tmpBlockHeight: Int
          _tmpBlockHeight = _stmt.getLong(_cursorIndexOfBlockHeight).toInt()
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_cursorIndexOfTimestamp)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_cursorIndexOfStatus)
          _item =
              TransactionEntity(_tmpTxId,_tmpWalletAddress,_tmpAmount,_tmpFee,_tmpToAddress,_tmpFromAddress,_tmpConfirmations,_tmpBlockHeight,_tmpTimestamp,_tmpStatus)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTransaction(txId: String): TransactionEntity? {
    val _sql: String = "SELECT * FROM transactions WHERE txId = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, txId)
        val _cursorIndexOfTxId: Int = getColumnIndexOrThrow(_stmt, "txId")
        val _cursorIndexOfWalletAddress: Int = getColumnIndexOrThrow(_stmt, "walletAddress")
        val _cursorIndexOfAmount: Int = getColumnIndexOrThrow(_stmt, "amount")
        val _cursorIndexOfFee: Int = getColumnIndexOrThrow(_stmt, "fee")
        val _cursorIndexOfToAddress: Int = getColumnIndexOrThrow(_stmt, "toAddress")
        val _cursorIndexOfFromAddress: Int = getColumnIndexOrThrow(_stmt, "fromAddress")
        val _cursorIndexOfConfirmations: Int = getColumnIndexOrThrow(_stmt, "confirmations")
        val _cursorIndexOfBlockHeight: Int = getColumnIndexOrThrow(_stmt, "blockHeight")
        val _cursorIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _cursorIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _result: TransactionEntity?
        if (_stmt.step()) {
          val _tmpTxId: String
          _tmpTxId = _stmt.getText(_cursorIndexOfTxId)
          val _tmpWalletAddress: String
          _tmpWalletAddress = _stmt.getText(_cursorIndexOfWalletAddress)
          val _tmpAmount: Long
          _tmpAmount = _stmt.getLong(_cursorIndexOfAmount)
          val _tmpFee: Long
          _tmpFee = _stmt.getLong(_cursorIndexOfFee)
          val _tmpToAddress: String
          _tmpToAddress = _stmt.getText(_cursorIndexOfToAddress)
          val _tmpFromAddress: String
          _tmpFromAddress = _stmt.getText(_cursorIndexOfFromAddress)
          val _tmpConfirmations: Int
          _tmpConfirmations = _stmt.getLong(_cursorIndexOfConfirmations).toInt()
          val _tmpBlockHeight: Int
          _tmpBlockHeight = _stmt.getLong(_cursorIndexOfBlockHeight).toInt()
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_cursorIndexOfTimestamp)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_cursorIndexOfStatus)
          _result =
              TransactionEntity(_tmpTxId,_tmpWalletAddress,_tmpAmount,_tmpFee,_tmpToAddress,_tmpFromAddress,_tmpConfirmations,_tmpBlockHeight,_tmpTimestamp,_tmpStatus)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteAllForWallet(address: String): Int {
    val _sql: String = "DELETE FROM transactions WHERE walletAddress = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, address)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
