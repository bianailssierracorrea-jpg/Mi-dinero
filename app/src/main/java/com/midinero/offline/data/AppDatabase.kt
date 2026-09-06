package com.midinero.offline.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val currency: String,
    val balance: Double = 0.0
)

@Entity(tableName = "movements")
data class Movement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val amount: Double,
    val currency: String,
    val accountId: Long?,
    val category: String = "",
    val description: String = "",
    val dateMillis: Long = System.currentTimeMillis(),
    val relatedAccountId: Long? = null
)

@Entity(tableName = "currencies")
data class CurrencyEntity(
    @PrimaryKey val code: String,
    val name: String,
    val isCrypto: Boolean = false
)

@Entity(tableName = "rates")
data class Rate(
    @PrimaryKey val pair: String,
    val value: Double
)

@Entity(tableName = "savings_goals")
data class SavingsGoal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val target: Double,
    val saved: Double = 0.0,
    val currency: String = "CUP"
)

@Entity(tableName = "currency_operations")
data class CurrencyOperation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val currency: String,
    val amount: Double,
    val rate: Double,
    val cupTotal: Double,
    val profit: Double = 0.0,
    val dateMillis: Long = System.currentTimeMillis()
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY id")
    fun observeAll(): Flow<List<Account>>
    @Insert suspend fun insert(a: Account): Long
    @Update suspend fun update(a: Account)
    @Query("DELETE FROM accounts WHERE id=:id") suspend fun delete(id: Long)
}

@Dao
interface MovementDao {
    @Query("SELECT * FROM movements ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<Movement>>
    @Insert suspend fun insert(m: Movement): Long
}

@Dao
interface CurrencyDao {
    @Query("SELECT * FROM currencies ORDER BY code")
    fun observeAll(): Flow<List<CurrencyEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(c: CurrencyEntity)
}

@Dao
interface RateDao {
    @Query("SELECT * FROM rates ORDER BY pair")
    fun observeAll(): Flow<List<Rate>>
    @Query("SELECT value FROM rates WHERE pair=:pair LIMIT 1")
    suspend fun get(pair: String): Double?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(r: Rate)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM savings_goals ORDER BY id")
    fun observeAll(): Flow<List<SavingsGoal>>
    @Insert suspend fun insert(g: SavingsGoal): Long
    @Update suspend fun update(g: SavingsGoal)
}

@Dao
interface CurrencyOperationDao {
    @Query("SELECT * FROM currency_operations ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<CurrencyOperation>>
    @Insert suspend fun insert(o: CurrencyOperation): Long
}

@Database(
    entities = [Account::class, Movement::class, CurrencyEntity::class, Rate::class, SavingsGoal::class, CurrencyOperation::class],
    version = 1, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun movementDao(): MovementDao
    abstract fun currencyDao(): CurrencyDao
    abstract fun rateDao(): RateDao
    abstract fun goalDao(): GoalDao
    abstract fun currencyOperationDao(): CurrencyOperationDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "mi_dinero.db"
                ).build().also { INSTANCE = it }
            }
    }
}
