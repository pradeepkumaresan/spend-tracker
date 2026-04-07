package com.spendtracker

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(this)
        setContent {
            MaterialTheme {
                val factory = SpendViewModelFactory(
                    applicationContext,
                    db.spendDao(),
                    db.categoryDao(),
                    db.feedbackDao(),
                    db.appConfigDao(),
                    db.metricDao()
                )
                val vm: SpendViewModel = viewModel(factory = factory)
                SpendTrackerApp(vm)
            }
        }
    }
}

enum class SpendType { INCOME, EXPENSE }

@Entity(tableName = "spends")
data class Spend(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double, // stored in INR only
    val note: String,
    val category: String,
    val type: SpendType,
    val source: String,
    val timestamp: Long,
    val modelClass: SmsClass? = null,
    val modelConfidence: Double? = null,
    val rawBody: String = ""
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(tableName = "model_feedback")
data class ModelFeedback(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageText: String,
    val correctedClass: SmsClass,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_config")
data class AppConfig(
    @PrimaryKey val id: Int = 1,
    val salarySenders: String = "",
    val salaryAccountTails: String = "",
    val salaryNarrations: String = "",
    val salaryThreshold: Double = 0.93,
    val expenseThreshold: Double = 0.88,
    val shadowMode: Boolean = true,
    val modelJson: String = ""
)

@Entity(tableName = "ml_metrics")
data class MlMetric(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val predictedClass: SmsClass,
    val acceptedClass: SmsClass,
    val confidence: Double,
    val createdAt: Long = System.currentTimeMillis()
)

data class MonthSummary(
    val month: String,
    val income: Double,
    val expense: Double,
    val spends: List<Spend>
)

@Dao
interface SpendDao {
    @Query("SELECT * FROM spends ORDER BY timestamp DESC")
    suspend fun all(): List<Spend>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(spend: Spend): Long

    @Update
    suspend fun update(spend: Spend)

    @Query("DELETE FROM spends WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM spends WHERE source = :source")
    suspend fun countBySource(source: String): Int

    @Query("DELETE FROM spends WHERE source = :source")
    suspend fun deleteBySource(source: String)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name ASC")
    suspend fun all(): List<Category>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface FeedbackDao {
    @Query("SELECT * FROM model_feedback ORDER BY createdAt DESC")
    suspend fun all(): List<ModelFeedback>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feedback: ModelFeedback): Long
}

@Dao
interface AppConfigDao {
    @Query("SELECT * FROM app_config WHERE id = 1")
    suspend fun get(): AppConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: AppConfig)
}

@Dao
interface MetricDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metric: MlMetric)

    @Query("SELECT * FROM ml_metrics ORDER BY createdAt DESC LIMIT 500")
    suspend fun recent(): List<MlMetric>
}

class SpendTypeConverters {
    @TypeConverter
    fun fromType(type: SpendType): String = type.name

    @TypeConverter
    fun toType(value: String): SpendType = SpendType.valueOf(value)

    @TypeConverter
    fun fromSmsClass(value: SmsClass?): String? = value?.name

    @TypeConverter
    fun toSmsClass(value: String?): SmsClass? = value?.let { SmsClass.valueOf(it) }
}

@TypeConverters(SpendTypeConverters::class)
@Database(
    entities = [Spend::class, Category::class, ModelFeedback::class, AppConfig::class, MlMetric::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spendDao(): SpendDao
    abstract fun categoryDao(): CategoryDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun appConfigDao(): AppConfigDao
    abstract fun metricDao(): MetricDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "spend_tracker.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}

class SpendRepository(
    private val spendDao: SpendDao,
    private val categoryDao: CategoryDao,
    private val feedbackDao: FeedbackDao,
    private val appConfigDao: AppConfigDao,
    private val metricDao: MetricDao
) {
    suspend fun allSpends() = spendDao.all()
    suspend fun insertSpend(spend: Spend) = spendDao.insert(spend)
    suspend fun updateSpend(spend: Spend) = spendDao.update(spend)
    suspend fun deleteSpend(id: Long) = spendDao.delete(id)
    suspend fun hasImportedSms() = spendDao.countBySource("SMS") > 0
    suspend fun deleteBySource(source: String) = spendDao.deleteBySource(source)

    suspend fun allCategories() = categoryDao.all()
    suspend fun insertCategory(name: String) = categoryDao.insert(Category(name = name.trim()))
    suspend fun updateCategory(category: Category) = categoryDao.update(category)
    suspend fun deleteCategory(id: Long) = categoryDao.delete(id)

    suspend fun allFeedback() = feedbackDao.all()
    suspend fun addFeedback(text: String, correctedClass: SmsClass) =
        feedbackDao.insert(ModelFeedback(messageText = text, correctedClass = correctedClass))

    suspend fun getConfig(): AppConfig = appConfigDao.get() ?: AppConfig()
    suspend fun saveConfig(config: AppConfig) = appConfigDao.upsert(config)

    suspend fun addMetric(predicted: SmsClass, accepted: SmsClass, confidence: Double) =
        metricDao.insert(MlMetric(predictedClass = predicted, acceptedClass = accepted, confidence = confidence))
    suspend fun recentMetrics() = metricDao.recent()
}

data class UiState(
    val spends: List<Spend> = emptyList(),
    val query: String = "",
    val editing: Spend? = null,
    val categories: List<Category> = emptyList(),
    val editingCategory: Category? = null,
    val selectedMonth: String? = null,
    val config: AppConfig = AppConfig(),
    val salaryPrecision: Double = 0.0,
    val expensePrecision: Double = 0.0,
    val correctionRate: Double = 0.0
)

enum class TxnFilter { ALL, EXPENSE, INCOME }
enum class MonthSort { DATE_DESC, DATE_ASC, AMOUNT_DESC, AMOUNT_ASC }

class SpendViewModel(
    private val context: Context,
    private val repository: SpendRepository
) : ViewModel() {
    private val rawSpends = MutableStateFlow<List<Spend>>(emptyList())
    private val categories = MutableStateFlow<List<Category>>(emptyList())
    private val query = MutableStateFlow("")
    private val editing = MutableStateFlow<Spend?>(null)
    private val editingCategory = MutableStateFlow<Category?>(null)
    private val selectedMonth = MutableStateFlow<String?>(null)
    private val config = MutableStateFlow(AppConfig())
    private val salaryPrecision = MutableStateFlow(0.0)
    private val expensePrecision = MutableStateFlow(0.0)
    private val correctionRate = MutableStateFlow(0.0)
    private val monthFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
    private var smsModel: SmsModel? = null

    private val baseState = combine(rawSpends, query, editing, categories) { spends, q, e, cats ->
        val filtered = if (q.isBlank()) spends else spends.filter {
            it.note.contains(q, true) || it.category.contains(q, true) || it.source.contains(q, true)
        }
        UiState(spends = filtered, query = q, editing = e, categories = cats)
    }

    private val mlState = combine(config, salaryPrecision, expensePrecision, correctionRate) { conf, salPrec, expPrec, corrRate ->
        arrayOf(conf, salPrec, expPrec, corrRate)
    }

    val state: StateFlow<UiState> = combine(baseState, editingCategory, selectedMonth, mlState) { base, catEdit, selected, ml ->
        val conf = ml[0] as AppConfig
        val salPrec = ml[1] as Double
        val expPrec = ml[2] as Double
        val corrRate = ml[3] as Double
        base.copy(
            editingCategory = catEdit,
            selectedMonth = selected,
            config = conf,
            salaryPrecision = salPrec,
            expensePrecision = expPrec,
            correctionRate = corrRate
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            ensureDefaultCategories()
            config.value = repository.getConfig()
            smsModel = SmsModel.fromJson(config.value.modelJson)
            refresh()
            refreshMetrics()
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            rawSpends.value = repository.allSpends()
            categories.value = repository.allCategories()
        }
    }

    fun updateConfig(newConfig: AppConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val previousModel = config.value.modelJson
            config.value = newConfig
            repository.saveConfig(newConfig)
            if (newConfig.modelJson != previousModel) {
                smsModel = SmsModel.fromJson(newConfig.modelJson)
            }
        }
    }

    fun retrainModel() {
        viewModelScope.launch(Dispatchers.IO) {
            val feedback = repository.allFeedback().map { it.messageText to it.correctedClass }
            smsModel = OnlineTrainer.trainBootstrap(emptyList(), feedback)
            val nextConfig = config.value.copy(modelJson = smsModel?.toJson().orEmpty())
            config.value = nextConfig
            repository.saveConfig(nextConfig)
        }
    }

    fun resetModel() {
        viewModelScope.launch(Dispatchers.IO) {
            smsModel = null
            val nextConfig = config.value.copy(modelJson = "")
            config.value = nextConfig
            repository.saveConfig(nextConfig)
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun selectMonth(month: String?) {
        selectedMonth.value = month
    }

    fun startEdit(spend: Spend) {
        editing.value = spend
    }

    fun stopEdit() {
        editing.value = null
    }

    fun startCategoryEdit(category: Category) {
        editingCategory.value = category
    }

    fun stopCategoryEdit() {
        editingCategory.value = null
    }

    fun saveCategory(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val existingEdit = editingCategory.value
            if (existingEdit == null) {
                repository.insertCategory(clean)
            } else {
                repository.updateCategory(existingEdit.copy(name = clean))
                editingCategory.value = null
            }
            categories.value = repository.allCategories()
        }
    }

    fun deleteCategory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteCategory(id)
            categories.value = repository.allCategories()
        }
    }

    fun save(
        amountText: String,
        note: String,
        category: String,
        isIncome: Boolean,
        currency: String = "INR",
        dateText: String = "",
        timestamp: Long? = null
    ) {
        val rawAmount = amountText.toDoubleOrNull() ?: return
        val currentEdit = editing.value
        val inputTimestamp = parseDateInput(dateText) ?: timestamp ?: currentEdit?.timestamp ?: System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            val amountInInr = CurrencyConverter.toInr(rawAmount, currency) ?: return@launch
            if (currentEdit == null) {
                repository.insertSpend(
                    Spend(
                        amount = amountInInr,
                        note = note.ifBlank { "Manual entry" },
                        category = category.ifBlank { if (isIncome) "Income" else "General" },
                        type = if (isIncome) SpendType.INCOME else SpendType.EXPENSE,
                        source = "MANUAL",
                        timestamp = inputTimestamp
                    )
                )
            } else {
                repository.updateSpend(
                    currentEdit.copy(
                        amount = amountInInr,
                        note = note,
                        category = category,
                        type = if (isIncome) SpendType.INCOME else SpendType.EXPENSE,
                        timestamp = inputTimestamp
                    )
                )
                editing.value = null
            }
            rawSpends.value = repository.allSpends()
        }
    }

    private fun parseDateInput(text: String): Long? {
        val clean = text.trim()
        if (clean.isBlank()) return null
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        sdf.isLenient = false
        val parsed = sdf.parse(clean) ?: return null
        val cal = Calendar.getInstance()
        cal.time = parsed
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun deleteSpend(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSpend(id)
            rawSpends.value = repository.allSpends()
        }
    }

    fun importSmsIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!repository.hasImportedSms()) {
                importFromSms(context.contentResolver)
                rawSpends.value = repository.allSpends()
            }
        }
    }

    fun reimportSms() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteBySource("SMS")
            importFromSms(context.contentResolver)
            rawSpends.value = repository.allSpends()
            refreshMetrics()
        }
    }

    private suspend fun importFromSms(contentResolver: ContentResolver) {
        val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val cursor = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            cols,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        ) ?: return
        val raws = mutableListOf<RawSms>()
        cursor.use {
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                val sender = if (addressIdx >= 0) it.getString(addressIdx) else ""
                val body = if (bodyIdx >= 0) it.getString(bodyIdx) else ""
                val date = if (dateIdx >= 0) it.getLong(dateIdx) else System.currentTimeMillis()
                raws += RawSms(sender ?: "", body ?: "", date)
            }
        }
        if (raws.isEmpty()) return

        val feedback = repository.allFeedback().map { it.messageText to it.correctedClass }
        if (smsModel == null) {
            smsModel = OnlineTrainer.trainBootstrap(raws, feedback)
            repository.saveConfig(config.value.copy(modelJson = smsModel!!.toJson()))
            config.value = repository.getConfig()
        }
        val classifier = SmsClassifier(smsModel ?: return)
        val profile = currentSalaryProfile(config.value)

        raws.forEach { raw ->
            val parsedAmount = SmsAmountParser.parseAmount(raw.body)
            val (predicted, confidence) = classifier.predict(raw.sender, raw.body)
            val accepted = SmsDecisionPolicy.decide(raw.sender, raw.body, predicted, confidence, profile)
            if (config.value.shadowMode) {
                repository.addMetric(predicted, accepted, confidence)
            }
            if (accepted != SmsClass.SALARY_INCOME && accepted != SmsClass.EXPENSE_SPEND) return@forEach
            val amountInr = parsedAmount?.let { (currency, amount) ->
                CurrencyConverter.toInr(amount, currency, raw.timestamp)
            }
            if (amountInr == null || amountInr <= 0.0) return@forEach

            repository.insertSpend(
                Spend(
                    amount = amountInr,
                    note = "${raw.sender.take(15)}: ${raw.body.take(105)}",
                    category = SmsParser.inferCategory(raw.body, if (accepted == SmsClass.SALARY_INCOME) SpendType.INCOME else SpendType.EXPENSE),
                    type = if (accepted == SmsClass.SALARY_INCOME) SpendType.INCOME else SpendType.EXPENSE,
                    source = "SMS",
                    timestamp = raw.timestamp,
                    modelClass = predicted,
                    modelConfidence = confidence,
                    rawBody = raw.body.take(220)
                )
            )
        }
    }

    fun markSpendAs(spend: Spend, correctedClass: SmsClass) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addFeedback(spend.rawBody.ifBlank { spend.note }, correctedClass)
            smsModel = OnlineTrainer.updateWithFeedback(
                smsModel ?: OnlineTrainer.trainBootstrap(emptyList(), emptyList()),
                spend.rawBody.ifBlank { spend.note },
                correctedClass
            )
            repository.saveConfig(config.value.copy(modelJson = smsModel?.toJson().orEmpty()))
            when (correctedClass) {
                SmsClass.SALARY_INCOME -> repository.updateSpend(spend.copy(type = SpendType.INCOME, category = "Income"))
                SmsClass.EXPENSE_SPEND -> repository.updateSpend(spend.copy(type = SpendType.EXPENSE))
                SmsClass.NON_SPEND_CREDIT, SmsClass.IGNORE -> repository.deleteSpend(spend.id)
            }
            rawSpends.value = repository.allSpends()
            refreshMetrics()
        }
    }

    private suspend fun refreshMetrics() {
        val metrics = repository.recentMetrics()
        if (metrics.isEmpty()) {
            salaryPrecision.value = 0.0
            expensePrecision.value = 0.0
        } else {
            val salaryPred = metrics.filter { it.predictedClass == SmsClass.SALARY_INCOME }
            val expensePred = metrics.filter { it.predictedClass == SmsClass.EXPENSE_SPEND }
            salaryPrecision.value = if (salaryPred.isEmpty()) 0.0 else salaryPred.count { it.acceptedClass == SmsClass.SALARY_INCOME }.toDouble() / salaryPred.size
            expensePrecision.value = if (expensePred.isEmpty()) 0.0 else expensePred.count { it.acceptedClass == SmsClass.EXPENSE_SPEND }.toDouble() / expensePred.size
        }
        val feedbackCount = repository.allFeedback().size
        val spendCount = repository.allSpends().size.coerceAtLeast(1)
        correctionRate.value = feedbackCount.toDouble() / spendCount.toDouble()
    }

    private fun currentSalaryProfile(cfg: AppConfig): SalaryProfile {
        fun split(v: String): List<String> = v.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return SalaryProfile(
            senderKeywords = split(cfg.salarySenders),
            accountTails = split(cfg.salaryAccountTails),
            narrationKeywords = split(cfg.salaryNarrations),
            salaryThreshold = cfg.salaryThreshold,
            expenseThreshold = cfg.expenseThreshold
        )
    }

    fun monthlySummary(spends: List<Spend>): List<MonthSummary> {
        return spends.groupBy { monthFormat.format(Date(it.timestamp)) }
            .map { (month, rows) ->
                val income = rows.filter { it.type == SpendType.INCOME }.sumOf { it.amount }
                val expense = rows.filter { it.type == SpendType.EXPENSE }.sumOf { it.amount }
                MonthSummary(month, income, expense, rows)
            }
            .sortedByDescending { monthFormat.parse(it.month)?.time ?: 0L }
    }

    fun transactionsForMonth(month: String, spends: List<Spend>): List<Spend> {
        return spends.filter { monthFormat.format(Date(it.timestamp)) == month }
    }

    private suspend fun ensureDefaultCategories() {
        val defaults = listOf("General", "Food", "Transport", "Shopping", "Bills", "Income")
        defaults.forEach { repository.insertCategory(it) }
    }
}

class SpendViewModelFactory(
    private val context: Context,
    private val spendDao: SpendDao,
    private val categoryDao: CategoryDao,
    private val feedbackDao: FeedbackDao,
    private val appConfigDao: AppConfigDao,
    private val metricDao: MetricDao
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SpendViewModel(context, SpendRepository(spendDao, categoryDao, feedbackDao, appConfigDao, metricDao)) as T
    }
}

object CurrencyConverter {
    // Frankfurter free API — no key, supports historical rates, ECB data, covers THB
    private const val API_HOST = "https://api.frankfurter.app"
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val rateCache = mutableMapOf<String, Double>() // key: "THB_2026-03-06"
    private val cacheMutex = Mutex()

    // Convert amount from currency to INR using rate at the time of the transaction
    suspend fun toInr(amount: Double, currency: String, timestampMs: Long = System.currentTimeMillis()): Double {
        val c = currency.uppercase(Locale.getDefault())
        if (c == "INR") return amount
        val rate = getRateToInr(c, timestampMs) ?: StaticFxRates.toInr(1.0, c)
        return amount * rate
    }

    private suspend fun getRateToInr(currency: String, timestampMs: Long): Double? {
        val dateStr = dateFmt.format(Date(timestampMs))
        val key = "${currency}_${dateStr}"
        cacheMutex.withLock { rateCache[key]?.let { return it } }
        val fresh = fetchRate(currency, dateStr) ?: return null
        cacheMutex.withLock { rateCache[key] = fresh }
        return fresh
    }

    private suspend fun fetchRate(currency: String, dateStr: String): Double? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$API_HOST/$dateStr?from=$currency&to=INR")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); return@withContext null }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(body).optJSONObject("rates")?.optDouble("INR")?.takeIf { !it.isNaN() && it > 0.0 }
        } catch (_: Exception) {
            null
        }
    }
}

object SmsParser {
    private val currencyPatterns: List<Pair<String, Regex>> = listOf(
        "INR" to Regex("(?:INR|Rs\\.?|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "USD" to Regex("(?:USD|US\\$|\\$)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "EUR" to Regex("(?:EUR|€)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "GBP" to Regex("(?:GBP|£)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "AED" to Regex("(?:AED|د\\.إ)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "SGD" to Regex("(?:SGD)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "JPY" to Regex("(?:JPY|¥)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "THB" to Regex("(?:THB|฿)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
    )
    private val postfixPattern = Regex("([0-9,]+(?:\\.[0-9]{1,2})?)\\s*(INR|USD|EUR|GBP|AED|SGD|JPY|THB)", RegexOption.IGNORE_CASE)
    private val balanceWords = listOf("avl bal", "available balance", "balance", "credit limit", "outstanding", "total due")
    private val debitHints = listOf("debited", "spent", "purchase", "purchased", "paid", "txn")
    private val creditHints = listOf("credited", "deposit", "deposited", "salary", "refund", "received")

    suspend fun parse(sender: String, body: String, timestamp: Long): Spend? {
        val type = classifyType(body) ?: return null
        val extraction = extractCurrencyAndAmount(body, type) ?: return null
        val inrAmount = CurrencyConverter.toInr(extraction.second, extraction.first) ?: return null
        val conversionNote = if (extraction.first == "INR") "" else " (${extraction.second} ${extraction.first} -> INR)"
        return Spend(
            amount = inrAmount,
            note = "${sender.take(15)}: ${body.take(95)}$conversionNote",
            category = inferCategory(body, type),
            type = type,
            source = "SMS",
            timestamp = timestamp
        )
    }

    private fun classifyType(body: String): SpendType? {
        val lower = body.lowercase(Locale.getDefault())
        return when {
            creditHints.any { lower.contains(it) } -> SpendType.INCOME
            debitHints.any { lower.contains(it) } -> SpendType.EXPENSE
            else -> null
        }
    }

    private fun extractCurrencyAndAmount(body: String, type: SpendType): Pair<String, Double>? {
        val lower = body.lowercase(Locale.getDefault())
        val preferredHints = if (type == SpendType.INCOME) creditHints else debitHints

        for ((currency, regex) in currencyPatterns) {
            val matches = regex.findAll(body)
            for (match in matches) {
                val start = match.range.first
                val localStart = (start - 35).coerceAtLeast(0)
                val localEnd = (start + 35).coerceAtMost(lower.length)
                val context = lower.substring(localStart, localEnd)
                val amount = match.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
                if (balanceWords.any { context.contains(it) }) continue
                if (preferredHints.any { context.contains(it) }) return currency to amount
            }
        }

        for ((currency, regex) in currencyPatterns) {
            val matches = regex.findAll(body)
            for (match in matches) {
                val start = match.range.first
                val localStart = (start - 25).coerceAtLeast(0)
                val localEnd = (start + 25).coerceAtMost(lower.length)
                val context = lower.substring(localStart, localEnd)
                val amount = match.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
                if (balanceWords.any { context.contains(it) }) continue
                return currency to amount
            }
        }

        val postfixMatches = postfixPattern.findAll(body)
        for (postfix in postfixMatches) {
            val start = postfix.range.first
            val localStart = (start - 25).coerceAtLeast(0)
            val localEnd = (start + 25).coerceAtMost(lower.length)
            val context = lower.substring(localStart, localEnd)
            if (balanceWords.any { context.contains(it) }) continue
            val amount = postfix.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            val currency = postfix.groupValues[2].uppercase(Locale.getDefault())
            return currency to amount
        }
        return null
    }

    fun inferCategory(text: String, type: SpendType): String {
        val t = text.lowercase(Locale.getDefault())
        if (type == SpendType.INCOME) return "Income"
        return when {
            listOf("uber", "ola", "metro", "fuel", "petrol").any { t.contains(it) } -> "Transport"
            listOf("swiggy", "zomato", "restaurant", "food").any { t.contains(it) } -> "Food"
            listOf("amazon", "flipkart", "shopping", "myntra").any { t.contains(it) } -> "Shopping"
            listOf("electricity", "bill", "water", "recharge").any { t.contains(it) } -> "Bills"
            else -> "General"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendTrackerApp(vm: SpendViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = vm,
                onOpenMonth = { month -> navController.navigate("month/$month") }
            )
        }
        composable("month/{month}") { backStackEntry ->
            val month = backStackEntry.arguments?.getString("month") ?: return@composable
            MonthDetailScreen(
                vm = vm,
                month = month,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: SpendViewModel,
    onOpenMonth: (String) -> Unit
) {
    val state by vm.state.collectAsState()
    val monthSummary = vm.monthlySummary(state.spends)
    var filter by remember { mutableStateOf(TxnFilter.ALL) }
    val filteredSpends = remember(state.spends, filter) {
        state.spends.filterBy(filter)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.importSmsIfNeeded()
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            vm.importSmsIfNeeded()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Spend Tracker (INR)") }) }) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                            vm.importSmsIfNeeded()
                        } else {
                            permissionLauncher.launch(Manifest.permission.READ_SMS)
                        }
                    }) { Text("Import SMS") }
                    TextButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                            vm.reimportSms()
                        } else {
                            permissionLauncher.launch(Manifest.permission.READ_SMS)
                        }
                    }) { Text("Reimport SMS") }
                    TextButton(onClick = vm::refresh) { Text("Refresh") }
                }
            }
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search spends") }
                )
            }
            item { FilterBar(filter = filter, onFilterChange = { filter = it }) }
            item {
                MonthSummarySection(
                    summaries = monthSummary,
                    onOpenMonth = onOpenMonth
                )
            }
            item { SpendEditor(vm = vm, editing = state.editing, categories = state.categories) }
            item { CategoryManager(vm = vm, categories = state.categories, editingCategory = state.editingCategory) }
            item { MlControlPanel(vm = vm, state = state) }
            item {
                HorizontalDivider()
                Text(
                    text = "Transactions (All Months)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(filteredSpends) { spend ->
                SpendItem(
                    spend = spend,
                    onEdit = { vm.startEdit(spend) },
                    onDelete = { vm.deleteSpend(spend.id) },
                    onMark = { cls -> vm.markSpendAs(spend, cls) }
                )
            }
        }
    }
}

@Composable
fun MonthSummarySection(
    summaries: List<MonthSummary>,
    onOpenMonth: (String) -> Unit
) {
    Column {
        Text("Month Summary", style = MaterialTheme.typography.titleMedium)
        summaries.forEach { row ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onOpenMonth(row.month) }
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(row.month, style = MaterialTheme.typography.titleSmall)
                    Text("Income: INR %.2f | Expense: INR %.2f".format(row.income, row.expense))
                    val expenseByCategory = row.spends.filter { it.type == SpendType.EXPENSE }
                        .groupBy { it.category }
                        .mapValues { (_, spends) -> spends.sumOf { it.amount } }
                    CategoryPieChart(expenseByCategory)
                }
            }
        }
    }
}

@Composable
fun CategoryPieChart(categoryTotals: Map<String, Double>) {
    if (categoryTotals.isEmpty()) {
        Text("No expenses in this month", style = MaterialTheme.typography.bodySmall)
        return
    }
    val total = categoryTotals.values.sum()
    val palette = listOf(
        Color(0xFF1D4ED8), Color(0xFF16A34A), Color(0xFFEA580C),
        Color(0xFF9333EA), Color(0xFF0891B2), Color(0xFFDC2626)
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(84.dp)) {
            var start = -90f
            categoryTotals.entries.forEachIndexed { index, entry ->
                val sweep = ((entry.value / total) * 360f).toFloat()
                drawArc(
                    color = palette[index % palette.size],
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = true,
                    size = Size(size.width, size.height)
                )
                start += sweep
            }
            drawCircle(Color.White, style = Stroke(width = 10f))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            categoryTotals.entries.forEachIndexed { index, entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(
                        modifier = Modifier
                            .size(10.dp)
                            .background(palette[index % palette.size], CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${entry.key}: INR %.2f".format(entry.value), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthDetailScreen(vm: SpendViewModel, month: String, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    var filter by remember { mutableStateOf(TxnFilter.ALL) }
    var sort by remember { mutableStateOf(MonthSort.DATE_DESC) }
    val monthSpends = vm.transactionsForMonth(month, state.spends)
    val spends = remember(monthSpends, filter, sort) {
        monthSpends.filterBy(filter).sortedForMonth(sort)
    }
    val expenseByCategory = spends.filter { it.type == SpendType.EXPENSE }
        .groupBy { it.category }
        .mapValues { (_, s) -> s.sumOf { it.amount } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spends – $month") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterBar(filter = filter, onFilterChange = { filter = it })
            }
            item {
                MonthSortBar(sort = sort, onSortChange = { sort = it })
            }
            item {
                if (state.editing != null) {
                    SpendEditor(vm = vm, editing = state.editing, categories = state.categories)
                }
            }
            item {
                Text("Category Breakdown", style = MaterialTheme.typography.titleMedium)
                CategoryPieChart(expenseByCategory)
            }
            item {
                HorizontalDivider()
                Text("Transactions", style = MaterialTheme.typography.titleMedium)
            }
            items(spends) { spend ->
                SpendItem(
                    spend = spend,
                    onEdit = { vm.startEdit(spend) },
                    onDelete = { vm.deleteSpend(spend.id) },
                    onMark = { cls -> vm.markSpendAs(spend, cls) }
                )
            }
        }
    }
}

@Composable
fun SpendEditor(vm: SpendViewModel, editing: Spend?, categories: List<Category>) {
    var amount by remember(editing?.id) { mutableStateOf(editing?.amount?.toString() ?: "") }
    var note by remember(editing?.id) { mutableStateOf(editing?.note ?: "") }
    var category by remember(editing?.id) { mutableStateOf(editing?.category ?: "") }
    var currency by remember(editing?.id) { mutableStateOf("INR") }
    var isIncome by remember(editing?.id) { mutableStateOf(editing?.type == SpendType.INCOME) }
    var dateText by remember(editing?.id) {
        mutableStateOf(
            editing?.let { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it.timestamp)) } ?: ""
        )
    }
    val currencyOptions = listOf("INR", "USD", "EUR", "GBP", "AED", "SGD", "JPY", "THB")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (editing == null) "Add Manual Spend/Income" else "Edit Transaction")
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                currencyOptions.forEach {
                    TextButton(onClick = { currency = it }) { Text(if (currency == it) "$it *" else it) }
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Note") }
            )
            OutlinedTextField(
                value = dateText,
                onValueChange = { dateText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Date (dd/MM/yyyy)") }
            )
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Category") }
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                categories.forEach {
                    TextButton(onClick = { category = it.name }) { Text(it.name) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { isIncome = false }) { Text(if (!isIncome) "Expense *" else "Expense") }
                TextButton(onClick = { isIncome = true }) { Text(if (isIncome) "Income *" else "Income") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save(amount, note, category, isIncome, currency, dateText) }) {
                    Text(if (editing == null) "Add" else "Update")
                }
                if (editing != null) {
                    TextButton(onClick = { vm.stopEdit() }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
fun CategoryManager(vm: SpendViewModel, categories: List<Category>, editingCategory: Category?) {
    var categoryName by remember(editingCategory?.id) { mutableStateOf(editingCategory?.name ?: "") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Category Types", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = categoryName,
                onValueChange = { categoryName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (editingCategory == null) "New category" else "Edit category") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveCategory(categoryName) }) {
                    Text(if (editingCategory == null) "Add Category" else "Update Category")
                }
                if (editingCategory != null) {
                    TextButton(onClick = vm::stopCategoryEdit) { Text("Cancel") }
                }
            }
            categories.forEach { category ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(category.name)
                    Row {
                        TextButton(onClick = { vm.startCategoryEdit(category) }) { Text("Edit") }
                        TextButton(onClick = { vm.deleteCategory(category.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
fun SpendItem(spend: Spend, onEdit: () -> Unit, onDelete: () -> Unit, onMark: (SmsClass) -> Unit) {
    val time = remember(spend.timestamp) {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(spend.timestamp))
    }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("${spend.type} | ${spend.category} | INR ${"%.2f".format(spend.amount)}")
            Text(spend.note, style = MaterialTheme.typography.bodySmall)
            Text("$time • ${spend.source}", style = MaterialTheme.typography.labelSmall)
            if (spend.modelClass != null && spend.modelConfidence != null) {
                Text(
                    "ML: ${spend.modelClass.name} (${String.format(Locale.getDefault(), "%.2f", spend.modelConfidence)})",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onMark(SmsClass.SALARY_INCOME) }) { Text("Mark Salary") }
                TextButton(onClick = { onMark(SmsClass.EXPENSE_SPEND) }) { Text("Mark Expense") }
                TextButton(onClick = { onMark(SmsClass.IGNORE) }) { Text("Mark Ignore") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete transaction?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun FilterBar(filter: TxnFilter, onFilterChange: (TxnFilter) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Filter:")
            TextButton(onClick = { onFilterChange(TxnFilter.ALL) }) { Text(if (filter == TxnFilter.ALL) "All *" else "All") }
            TextButton(onClick = { onFilterChange(TxnFilter.EXPENSE) }) { Text(if (filter == TxnFilter.EXPENSE) "Expenses *" else "Expenses") }
            TextButton(onClick = { onFilterChange(TxnFilter.INCOME) }) { Text(if (filter == TxnFilter.INCOME) "Income *" else "Income") }
        }
    }
}

@Composable
fun MonthSortBar(sort: MonthSort, onSortChange: (MonthSort) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Sort in month view:")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { onSortChange(MonthSort.DATE_DESC) }) {
                    Text(if (sort == MonthSort.DATE_DESC) "Date: Newest *" else "Date: Newest")
                }
                TextButton(onClick = { onSortChange(MonthSort.DATE_ASC) }) {
                    Text(if (sort == MonthSort.DATE_ASC) "Date: Oldest *" else "Date: Oldest")
                }
                TextButton(onClick = { onSortChange(MonthSort.AMOUNT_DESC) }) {
                    Text(if (sort == MonthSort.AMOUNT_DESC) "Amount: High *" else "Amount: High")
                }
                TextButton(onClick = { onSortChange(MonthSort.AMOUNT_ASC) }) {
                    Text(if (sort == MonthSort.AMOUNT_ASC) "Amount: Low *" else "Amount: Low")
                }
            }
        }
    }
}

@Composable
fun MlControlPanel(vm: SpendViewModel, state: UiState) {
    var expanded by remember { mutableStateOf(false) }
    var salarySenders by remember(state.config.id, state.config.salarySenders) { mutableStateOf(state.config.salarySenders) }
    var salaryTails by remember(state.config.id, state.config.salaryAccountTails) { mutableStateOf(state.config.salaryAccountTails) }
    var salaryNarrations by remember(state.config.id, state.config.salaryNarrations) { mutableStateOf(state.config.salaryNarrations) }
    var salaryThreshold by remember(state.config.id, state.config.salaryThreshold) { mutableStateOf(state.config.salaryThreshold.toString()) }
    var expenseThreshold by remember(state.config.id, state.config.expenseThreshold) { mutableStateOf(state.config.expenseThreshold.toString()) }
    var shadowMode by remember(state.config.id, state.config.shadowMode) { mutableStateOf(state.config.shadowMode) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ML Settings", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Salary: %.0f%% | Expense: %.0f%% | Corrections: %.0f%%".format(
                            state.salaryPrecision * 100, state.expensePrecision * 100, state.correctionRate * 100
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(if (expanded) "▲ Collapse" else "▼ Expand", style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) {
                OutlinedTextField(
                    value = salarySenders,
                    onValueChange = { salarySenders = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Salary sender keywords (comma separated)") }
                )
                OutlinedTextField(
                    value = salaryTails,
                    onValueChange = { salaryTails = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Salary account tails (comma separated)") }
                )
                OutlinedTextField(
                    value = salaryNarrations,
                    onValueChange = { salaryNarrations = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Salary narration keywords (comma separated)") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = salaryThreshold,
                        onValueChange = { salaryThreshold = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Salary confidence") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    OutlinedTextField(
                        value = expenseThreshold,
                        onValueChange = { expenseThreshold = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Expense confidence") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { shadowMode = !shadowMode }) {
                        Text(if (shadowMode) "Shadow Mode: ON" else "Shadow Mode: OFF")
                    }
                    Button(onClick = {
                        vm.updateConfig(
                            state.config.copy(
                                salarySenders = salarySenders,
                                salaryAccountTails = salaryTails,
                                salaryNarrations = salaryNarrations,
                                salaryThreshold = salaryThreshold.toDoubleOrNull() ?: state.config.salaryThreshold,
                                expenseThreshold = expenseThreshold.toDoubleOrNull() ?: state.config.expenseThreshold,
                                shadowMode = shadowMode
                            )
                        )
                    }) { Text("Save") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::retrainModel) { Text("Retrain Model") }
                    TextButton(onClick = vm::resetModel) { Text("Reset Model") }
                }
            }
        }
    }
}

fun List<Spend>.filterBy(filter: TxnFilter): List<Spend> = when (filter) {
    TxnFilter.ALL -> this
    TxnFilter.EXPENSE -> this.filter { it.type == SpendType.EXPENSE }
    TxnFilter.INCOME -> this.filter { it.type == SpendType.INCOME }
}

fun List<Spend>.sortedForMonth(sort: MonthSort): List<Spend> = when (sort) {
    MonthSort.DATE_DESC -> this.sortedByDescending { it.timestamp }
    MonthSort.DATE_ASC -> this.sortedBy { it.timestamp }
    MonthSort.AMOUNT_DESC -> this.sortedByDescending { it.amount }
    MonthSort.AMOUNT_ASC -> this.sortedBy { it.amount }
}
