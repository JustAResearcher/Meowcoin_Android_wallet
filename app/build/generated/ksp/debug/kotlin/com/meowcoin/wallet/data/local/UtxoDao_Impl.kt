package com.meowcoin.wallet.`data`.local

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.getTotalChangedRows
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Boolean
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
public class UtxoDao_Impl(
  __db: RoomDatabase,
) : UtxoDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfUtxoEntity: EntityInsertAdapter<UtxoEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfUtxoEntity = object : EntityInsertAdapter<UtxoEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `utxos` (`id`,`txHash`,`outputIndex`,`walletAddress`,`value`,`scriptPubKey`,`confirmations`,`isSpent`) VALUES (?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: UtxoEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.txHash)
        statement.bindLong(3, entity.outputIndex.toLong())
        statement.bindText(4, entity.walletAddress)
        statement.bindLong(5, entity.value)
        statement.bindText(6, entity.scriptPubKey)
        statement.bindLong(7, entity.confirmations.toLong())
        val _tmp: Int = if (entity.isSpent) 1 else 0
        statement.bindLong(8, _tmp.toLong())
      }
    }
  }

  public override suspend fun insertUtxo(utxo: UtxoEntity): Long = performSuspending(__db, false,
      true) { _connection ->
    val _result: Long = __insertAdapterOfUtxoEntity.insertAndReturnId(_connection, utxo)
    _result
  }

  public override suspend fun insertUtxos(utxos: List<UtxoEntity>): List<Long> =
      performSuspending(__db, false, true) { _connection ->
    val _result: List<Long> = __insertAdapterOfUtxoEntity.insertAndReturnIdsList(_connection, utxos)
    _result
  }

  public override fun getUnspentUtxos(address: String): Flow<List<UtxoEntity>> {
    val _sql: String = "SELECT * FROM utxos WHERE walletAddress = ? AND isSpent = 0"
    return createFlow(__db, false, arrayOf("utxos")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, address)
        val _cursorIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _cursorIndexOfTxHash: Int = getColumnIndexOrThrow(_stmt, "txHash")
        val _cursorIndexOfOutputIndex: Int = getColumnIndexOrThrow(_stmt, "outputIndex")
        val _cursorIndexOfWalletAddress: Int = getColumnIndexOrThrow(_stmt, "walletAddress")
        val _cursorIndexOfValue: Int = getColumnIndexOrThrow(_stmt, "value")
        val _cursorIndexOfScriptPubKey: Int = getColumnIndexOrThrow(_stmt, "scriptPubKey")
        val _cursorIndexOfConfirmations: Int = getColumnIndexOrThrow(_stmt, "confirmations")
        val _cursorIndexOfIsSpent: Int = getColumnIndexOrThrow(_stmt, "isSpent")
        val _result: MutableList<UtxoEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: UtxoEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_cursorIndexOfId)
          val _tmpTxHash: String
          _tmpTxHash = _stmt.getText(_cursorIndexOfTxHash)
          val _tmpOutputIndex: Int
          _tmpOutputIndex = _stmt.getLong(_cursorIndexOfOutputIndex).toInt()
          val _tmpWalletAddress: String
          _tmpWalletAddress = _stmt.getText(_cursorIndexOfWalletAddress)
          val _tmpValue: Long
          _tmpValue = _stmt.getLong(_cursorIndexOfValue)
          val _tmpScriptPubKey: String
          _tmpScriptPubKey = _stmt.getText(_cursorIndexOfScriptPubKey)
          val _tmpConfirmations: Int
          _tmpConfirmations = _stmt.getLong(_cursorIndexOfConfirmations).toInt()
          val _tmpIsSpent: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_cursorIndexOfIsSpent).toInt()
          _tmpIsSpent = _tmp != 0
          _item =
              UtxoEntity(_tmpId,_tmpTxHash,_tmpOutputIndex,_tmpWalletAddress,_tmpValue,_tmpScriptPubKey,_tmpConfirmations,_tmpIsSpent)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getUnspentUtxosSync(address: String): List<UtxoEntity> {
    val _sql: String = "SELECT * FROM utxos WHERE walletAddress = ? AND isSpent = 0"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, address)
        val _cursorIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _cursorIndexOfTxHash: Int = getColumnIndexOrThrow(_stmt, "txHash")
        val _cursorIndexOfOutputIndex: Int = getColumnIndexOrThrow(_stmt, "outputIndex")
        val _cursorIndexOfWalletAddress: Int = getColumnIndexOrThrow(_stmt, "walletAddress")
        val _cursorIndexOfValue: Int = getColumnIndexOrThrow(_stmt, "value")
        val _cursorIndexOfScriptPubKey: Int = getColumnIndexOrThrow(_stmt, "scriptPubKey")
        val _cursorIndexOfConfirmations: Int = getColumnIndexOrThrow(_stmt, "confirmations")
        val _cursorIndexOfIsSpent: Int = getColumnIndexOrThrow(_stmt, "isSpent")
        val _result: MutableList<UtxoEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: UtxoEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_cursorIndexOfId)
          val _tmpTxHash: String
          _tmpTxHash = _stmt.getText(_cursorIndexOfTxHash)
          val _tmpOutputIndex: Int
          _tmpOutputIndex = _stmt.getLong(_cursorIndexOfOutputIndex).toInt()
          val _tmpWalletAddress: String
          _tmpWalletAddress = _stmt.getText(_cursorIndexOfWalletAddress)
          val _tmpValue: Long
          _tmpValue = _stmt.getLong(_cursorIndexOfValue)
          val _tmpScriptPubKey: String
          _tmpScriptPubKey = _stmt.getText(_cursorIndexOfScriptPubKey)
          val _tmpConfirmations: Int
          _tmpConfirmations = _stmt.getLong(_cursorIndexOfConfirmations).toInt()
          val _tmpIsSpent: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_cursorIndexOfIsSpent).toInt()
          _tmpIsSpent = _tmp != 0
          _item =
              UtxoEntity(_tmpId,_tmpTxHash,_tmpOutputIndex,_tmpWalletAddress,_tmpValue,_tmpScriptPubKey,_tmpConfirmations,_tmpIsSpent)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getBalance(address: String): Flow<Long?> {
    val _sql: String = "SELECT SUM(value) FROM utxos WHERE walletAddress = ? AND isSpent = 0"
    return createFlow(__db, false, arrayOf("utxos")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, address)
        val _result: Long?
        if (_stmt.step()) {
          val _tmp: Long?
          if (_stmt.isNull(0)) {
            _tmp = null
          } else {
            _tmp = _stmt.getLong(0)
          }
          _result = _tmp
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun markSpent(id: String): Int {
    val _sql: String = "UPDATE utxos SET isSpent = 1 WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteAllForWallet(address: String): Int {
    val _sql: String = "DELETE FROM utxos WHERE walletAddress = ?"
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
