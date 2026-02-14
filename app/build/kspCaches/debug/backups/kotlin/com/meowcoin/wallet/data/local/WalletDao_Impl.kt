package com.meowcoin.wallet.`data`.local

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
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
public class WalletDao_Impl(
  __db: RoomDatabase,
) : WalletDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfWalletEntity: EntityInsertAdapter<WalletEntity>

  private val __deleteAdapterOfWalletEntity: EntityDeleteOrUpdateAdapter<WalletEntity>

  private val __updateAdapterOfWalletEntity: EntityDeleteOrUpdateAdapter<WalletEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfWalletEntity = object : EntityInsertAdapter<WalletEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `wallets` (`address`,`label`,`createdAt`,`isActive`) VALUES (?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: WalletEntity) {
        statement.bindText(1, entity.address)
        statement.bindText(2, entity.label)
        statement.bindLong(3, entity.createdAt)
        val _tmp: Int = if (entity.isActive) 1 else 0
        statement.bindLong(4, _tmp.toLong())
      }
    }
    this.__deleteAdapterOfWalletEntity = object : EntityDeleteOrUpdateAdapter<WalletEntity>() {
      protected override fun createQuery(): String = "DELETE FROM `wallets` WHERE `address` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: WalletEntity) {
        statement.bindText(1, entity.address)
      }
    }
    this.__updateAdapterOfWalletEntity = object : EntityDeleteOrUpdateAdapter<WalletEntity>() {
      protected override fun createQuery(): String =
          "UPDATE OR ABORT `wallets` SET `address` = ?,`label` = ?,`createdAt` = ?,`isActive` = ? WHERE `address` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: WalletEntity) {
        statement.bindText(1, entity.address)
        statement.bindText(2, entity.label)
        statement.bindLong(3, entity.createdAt)
        val _tmp: Int = if (entity.isActive) 1 else 0
        statement.bindLong(4, _tmp.toLong())
        statement.bindText(5, entity.address)
      }
    }
  }

  public override suspend fun insertWallet(wallet: WalletEntity): Long = performSuspending(__db,
      false, true) { _connection ->
    val _result: Long = __insertAdapterOfWalletEntity.insertAndReturnId(_connection, wallet)
    _result
  }

  public override suspend fun deleteWallet(wallet: WalletEntity): Int = performSuspending(__db,
      false, true) { _connection ->
    var _result: Int = 0
    _result += __deleteAdapterOfWalletEntity.handle(_connection, wallet)
    _result
  }

  public override suspend fun updateWallet(wallet: WalletEntity): Int = performSuspending(__db,
      false, true) { _connection ->
    var _result: Int = 0
    _result += __updateAdapterOfWalletEntity.handle(_connection, wallet)
    _result
  }

  public override fun getActiveWallet(): Flow<WalletEntity?> {
    val _sql: String = "SELECT * FROM wallets WHERE isActive = 1 LIMIT 1"
    return createFlow(__db, false, arrayOf("wallets")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _cursorIndexOfAddress: Int = getColumnIndexOrThrow(_stmt, "address")
        val _cursorIndexOfLabel: Int = getColumnIndexOrThrow(_stmt, "label")
        val _cursorIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _cursorIndexOfIsActive: Int = getColumnIndexOrThrow(_stmt, "isActive")
        val _result: WalletEntity?
        if (_stmt.step()) {
          val _tmpAddress: String
          _tmpAddress = _stmt.getText(_cursorIndexOfAddress)
          val _tmpLabel: String
          _tmpLabel = _stmt.getText(_cursorIndexOfLabel)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_cursorIndexOfCreatedAt)
          val _tmpIsActive: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_cursorIndexOfIsActive).toInt()
          _tmpIsActive = _tmp != 0
          _result = WalletEntity(_tmpAddress,_tmpLabel,_tmpCreatedAt,_tmpIsActive)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getActiveWalletSync(): WalletEntity? {
    val _sql: String = "SELECT * FROM wallets WHERE isActive = 1 LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _cursorIndexOfAddress: Int = getColumnIndexOrThrow(_stmt, "address")
        val _cursorIndexOfLabel: Int = getColumnIndexOrThrow(_stmt, "label")
        val _cursorIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _cursorIndexOfIsActive: Int = getColumnIndexOrThrow(_stmt, "isActive")
        val _result: WalletEntity?
        if (_stmt.step()) {
          val _tmpAddress: String
          _tmpAddress = _stmt.getText(_cursorIndexOfAddress)
          val _tmpLabel: String
          _tmpLabel = _stmt.getText(_cursorIndexOfLabel)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_cursorIndexOfCreatedAt)
          val _tmpIsActive: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_cursorIndexOfIsActive).toInt()
          _tmpIsActive = _tmp != 0
          _result = WalletEntity(_tmpAddress,_tmpLabel,_tmpCreatedAt,_tmpIsActive)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getAllWallets(): Flow<List<WalletEntity>> {
    val _sql: String = "SELECT * FROM wallets"
    return createFlow(__db, false, arrayOf("wallets")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _cursorIndexOfAddress: Int = getColumnIndexOrThrow(_stmt, "address")
        val _cursorIndexOfLabel: Int = getColumnIndexOrThrow(_stmt, "label")
        val _cursorIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _cursorIndexOfIsActive: Int = getColumnIndexOrThrow(_stmt, "isActive")
        val _result: MutableList<WalletEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: WalletEntity
          val _tmpAddress: String
          _tmpAddress = _stmt.getText(_cursorIndexOfAddress)
          val _tmpLabel: String
          _tmpLabel = _stmt.getText(_cursorIndexOfLabel)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_cursorIndexOfCreatedAt)
          val _tmpIsActive: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_cursorIndexOfIsActive).toInt()
          _tmpIsActive = _tmp != 0
          _item = WalletEntity(_tmpAddress,_tmpLabel,_tmpCreatedAt,_tmpIsActive)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
