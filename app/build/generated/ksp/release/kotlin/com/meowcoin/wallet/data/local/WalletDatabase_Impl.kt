package com.meowcoin.wallet.`data`.local

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import javax.`annotation`.processing.Generated
import kotlin.Any
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class WalletDatabase_Impl : WalletDatabase() {
  private val _walletDao: Lazy<WalletDao> = lazy {
    WalletDao_Impl(this)
  }


  private val _transactionDao: Lazy<TransactionDao> = lazy {
    TransactionDao_Impl(this)
  }


  private val _utxoDao: Lazy<UtxoDao> = lazy {
    UtxoDao_Impl(this)
  }


  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(1,
        "c761db79a751dbf0b6f69fe0e5101775", "09c4d9302bb9efea23043690f519f17e") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `wallets` (`address` TEXT NOT NULL, `label` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, PRIMARY KEY(`address`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`txId` TEXT NOT NULL, `walletAddress` TEXT NOT NULL, `amount` INTEGER NOT NULL, `fee` INTEGER NOT NULL, `toAddress` TEXT NOT NULL, `fromAddress` TEXT NOT NULL, `confirmations` INTEGER NOT NULL, `blockHeight` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`txId`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `utxos` (`id` TEXT NOT NULL, `txHash` TEXT NOT NULL, `outputIndex` INTEGER NOT NULL, `walletAddress` TEXT NOT NULL, `value` INTEGER NOT NULL, `scriptPubKey` TEXT NOT NULL, `confirmations` INTEGER NOT NULL, `isSpent` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'c761db79a751dbf0b6f69fe0e5101775')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `wallets`")
        connection.execSQL("DROP TABLE IF EXISTS `transactions`")
        connection.execSQL("DROP TABLE IF EXISTS `utxos`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection):
          RoomOpenDelegate.ValidationResult {
        val _columnsWallets: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsWallets.put("address", TableInfo.Column("address", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsWallets.put("label", TableInfo.Column("label", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsWallets.put("createdAt", TableInfo.Column("createdAt", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsWallets.put("isActive", TableInfo.Column("isActive", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysWallets: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesWallets: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoWallets: TableInfo = TableInfo("wallets", _columnsWallets, _foreignKeysWallets,
            _indicesWallets)
        val _existingWallets: TableInfo = read(connection, "wallets")
        if (!_infoWallets.equals(_existingWallets)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |wallets(com.meowcoin.wallet.data.local.WalletEntity).
              | Expected:
              |""".trimMargin() + _infoWallets + """
              |
              | Found:
              |""".trimMargin() + _existingWallets)
        }
        val _columnsTransactions: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsTransactions.put("txId", TableInfo.Column("txId", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTransactions.put("walletAddress", TableInfo.Column("walletAddress", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTransactions.put("amount", TableInfo.Column("amount", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTransactions.put("fee", TableInfo.Column("fee", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTransactions.put("toAddress", TableInfo.Column("toAddress", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTransactions.put("fromAddress", TableInfo.Column("fromAddress", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTransactions.put("confirmations", TableInfo.Column("confirmations", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTransactions.put("blockHeight", TableInfo.Column("blockHeight", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTransactions.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTransactions.put("status", TableInfo.Column("status", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysTransactions: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesTransactions: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoTransactions: TableInfo = TableInfo("transactions", _columnsTransactions,
            _foreignKeysTransactions, _indicesTransactions)
        val _existingTransactions: TableInfo = read(connection, "transactions")
        if (!_infoTransactions.equals(_existingTransactions)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |transactions(com.meowcoin.wallet.data.local.TransactionEntity).
              | Expected:
              |""".trimMargin() + _infoTransactions + """
              |
              | Found:
              |""".trimMargin() + _existingTransactions)
        }
        val _columnsUtxos: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsUtxos.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsUtxos.put("txHash", TableInfo.Column("txHash", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsUtxos.put("outputIndex", TableInfo.Column("outputIndex", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsUtxos.put("walletAddress", TableInfo.Column("walletAddress", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsUtxos.put("value", TableInfo.Column("value", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsUtxos.put("scriptPubKey", TableInfo.Column("scriptPubKey", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsUtxos.put("confirmations", TableInfo.Column("confirmations", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsUtxos.put("isSpent", TableInfo.Column("isSpent", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysUtxos: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesUtxos: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoUtxos: TableInfo = TableInfo("utxos", _columnsUtxos, _foreignKeysUtxos,
            _indicesUtxos)
        val _existingUtxos: TableInfo = read(connection, "utxos")
        if (!_infoUtxos.equals(_existingUtxos)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |utxos(com.meowcoin.wallet.data.local.UtxoEntity).
              | Expected:
              |""".trimMargin() + _infoUtxos + """
              |
              | Found:
              |""".trimMargin() + _existingUtxos)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "wallets", "transactions",
        "utxos")
  }

  public override fun clearAllTables() {
    super.performClear(false, "wallets", "transactions", "utxos")
  }

  protected override fun getRequiredTypeConverterClasses():
      Map<KClass<out Any>, List<KClass<out Any>>> {
    val _typeConvertersMap: MutableMap<KClass<out Any>, List<KClass<out Any>>> = mutableMapOf()
    _typeConvertersMap.put(WalletDao::class, WalletDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(TransactionDao::class, TransactionDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(UtxoDao::class, UtxoDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override
      fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>):
      List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun walletDao(): WalletDao = _walletDao.value

  public override fun transactionDao(): TransactionDao = _transactionDao.value

  public override fun utxoDao(): UtxoDao = _utxoDao.value
}
