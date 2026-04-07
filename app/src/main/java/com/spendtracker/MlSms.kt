package com.spendtracker

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

enum class SmsClass {
    SALARY_INCOME, EXPENSE_SPEND, NON_SPEND_CREDIT, IGNORE
}

data class RawSms(val sender: String, val body: String, val timestamp: Long)

data class PredictedSms(val smsClass: SmsClass, val confidence: Double, val amountInr: Double?, val category: String)

data class SalaryProfile(
    val senderKeywords: List<String>,
    val accountTails: List<String>,
    val narrationKeywords: List<String>,
    val salaryThreshold: Double,
    val expenseThreshold: Double
)

data class SmsModel(
    val classCounts: MutableMap<SmsClass, Double> = mutableMapOf(),
    val tokenCounts: MutableMap<SmsClass, MutableMap<String, Double>> = mutableMapOf(),
    var vocabularySize: Int = 0
) {
    fun toJson(): String {
        val root = JSONObject()
        val counts = JSONObject()
        classCounts.forEach { (k, v) -> counts.put(k.name, v) }
        root.put("classCounts", counts)
        val tokenJson = JSONObject()
        tokenCounts.forEach { (clazz, tokens) ->
            val tokenObj = JSONObject()
            tokens.forEach { (t, c) -> tokenObj.put(t, c) }
            tokenJson.put(clazz.name, tokenObj)
        }
        root.put("tokenCounts", tokenJson)
        root.put("vocabularySize", vocabularySize)
        return root.toString()
    }

    companion object {
        fun fromJson(raw: String?): SmsModel? {
            if (raw.isNullOrBlank()) return null
            return try {
                val root = JSONObject(raw)
                val model = SmsModel()
                val counts = root.optJSONObject("classCounts") ?: JSONObject()
                SmsClass.values().forEach { c ->
                    model.classCounts[c] = counts.optDouble(c.name, 1.0)
                }
                val tokenJson = root.optJSONObject("tokenCounts") ?: JSONObject()
                SmsClass.values().forEach { c ->
                    val obj = tokenJson.optJSONObject(c.name) ?: JSONObject()
                    val map = mutableMapOf<String, Double>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = obj.optDouble(k, 0.0)
                    }
                    model.tokenCounts[c] = map
                }
                model.vocabularySize = root.optInt("vocabularySize", 1)
                model
            } catch (_: Exception) {
                null
            }
        }
    }
}

object SmsFeatureExtractor {
    private val tokenRegex = Regex("[a-z0-9]{2,}")

    fun features(sender: String, body: String): List<String> {
        val text = "${sender.lowercase(Locale.getDefault())} ${body.lowercase(Locale.getDefault())}"
            .replace(Regex("[^a-z0-9 ]"), " ")
        val unigrams = tokenRegex.findAll(text).map { it.value }.toList()
        val bigrams = unigrams.zipWithNext().map { "${it.first}_${it.second}" }
        return (unigrams + bigrams).take(220)
    }
}

object WeakLabeler {
    private val expenseHints = listOf("debited", "spent", "purchase", "pos", "upi", "atm", "txn", "paid")
    private val salaryHints = listOf("salary", "payroll", "wages")
    private val nonSpendCreditHints = listOf("pf", "ppf", "epfo", "interest", "refund", "reversal", "cashback")
    private val ignoreHints = listOf("otp", "statement", "due", "credit limit", "available balance", "avl bal")

    fun label(sender: String, body: String): SmsClass? {
        val txt = "$sender $body".lowercase(Locale.getDefault())
        if (ignoreHints.any { txt.contains(it) }) return SmsClass.IGNORE
        if (salaryHints.any { txt.contains(it) }) return SmsClass.SALARY_INCOME
        if (nonSpendCreditHints.any { txt.contains(it) }) return SmsClass.NON_SPEND_CREDIT
        if (expenseHints.any { txt.contains(it) }) return SmsClass.EXPENSE_SPEND
        if (txt.contains("credited")) return SmsClass.NON_SPEND_CREDIT
        return null
    }
}

class SmsClassifier(private val model: SmsModel) {
    fun predict(sender: String, body: String): Pair<SmsClass, Double> {
        val feats = SmsFeatureExtractor.features(sender, body)
        val scores = mutableMapOf<SmsClass, Double>()
        val totalDocs = model.classCounts.values.sum().coerceAtLeast(1.0)
        SmsClass.values().forEach { clazz ->
            val classCount = model.classCounts[clazz] ?: 1.0
            val prior = ln(classCount / totalDocs)
            val tokenMap = model.tokenCounts[clazz] ?: emptyMap()
            val tokenTotal = tokenMap.values.sum().coerceAtLeast(1.0)
            var s = prior
            feats.forEach { f ->
                val count = tokenMap[f] ?: 0.0
                val prob = (count + 1.0) / (tokenTotal + model.vocabularySize.coerceAtLeast(1))
                s += ln(prob)
            }
            scores[clazz] = s
        }
        val max = scores.maxByOrNull { it.value } ?: return SmsClass.IGNORE to 0.0
        val denom = scores.values.sumOf { exp(it - max.value) }.coerceAtLeast(1e-9)
        val confidence = 1.0 / denom
        return max.key to confidence
    }
}

object OnlineTrainer {
    fun trainBootstrap(rawSms: List<RawSms>, feedback: List<Pair<String, SmsClass>>): SmsModel {
        val model = SmsModel()
        SmsClass.values().forEach {
            model.classCounts[it] = 1.0
            model.tokenCounts[it] = mutableMapOf()
        }

        rawSms.forEach { sms ->
            val lbl = WeakLabeler.label(sms.sender, sms.body) ?: return@forEach
            addExample(model, sms.sender, sms.body, lbl, 1.0)
        }
        feedback.forEach { (text, lbl) ->
            addExample(model, "feedback", text, lbl, 3.0)
        }

        model.vocabularySize = model.tokenCounts.values.flatMap { it.keys }.toSet().size.coerceAtLeast(1)
        return model
    }

    fun updateWithFeedback(model: SmsModel, text: String, label: SmsClass): SmsModel {
        addExample(model, "feedback", text, label, 3.0)
        model.vocabularySize = model.tokenCounts.values.flatMap { it.keys }.toSet().size.coerceAtLeast(1)
        return model
    }

    private fun addExample(model: SmsModel, sender: String, body: String, label: SmsClass, weight: Double) {
        model.classCounts[label] = (model.classCounts[label] ?: 1.0) + weight
        val tokenMap = model.tokenCounts[label] ?: mutableMapOf()
        SmsFeatureExtractor.features(sender, body).forEach { token ->
            tokenMap[token] = (tokenMap[token] ?: 0.0) + weight
        }
        model.tokenCounts[label] = tokenMap
    }
}

object SmsDecisionPolicy {
    fun decide(
        sender: String,
        body: String,
        predictedClass: SmsClass,
        confidence: Double,
        profile: SalaryProfile
    ): SmsClass {
        return when (predictedClass) {
            SmsClass.SALARY_INCOME -> {
                if (confidence >= profile.salaryThreshold && passesSalaryGuardrails(sender, body, profile)) SmsClass.SALARY_INCOME
                else SmsClass.NON_SPEND_CREDIT
            }
            SmsClass.EXPENSE_SPEND -> {
                if (confidence >= profile.expenseThreshold && passesExpenseGuardrails(body)) SmsClass.EXPENSE_SPEND
                else SmsClass.IGNORE
            }
            SmsClass.NON_SPEND_CREDIT -> SmsClass.NON_SPEND_CREDIT
            SmsClass.IGNORE -> SmsClass.IGNORE
        }
    }

    private fun passesSalaryGuardrails(sender: String, body: String, profile: SalaryProfile): Boolean {
        val txt = "$sender $body".lowercase(Locale.getDefault())
        val hasNegative = listOf("pf", "ppf", "epfo", "interest", "refund", "cashback").any { txt.contains(it) }
        if (hasNegative) return false
        val senderPass = profile.senderKeywords.isEmpty() || profile.senderKeywords.any { txt.contains(it.lowercase(Locale.getDefault())) }
        val tailPass = profile.accountTails.isEmpty() || profile.accountTails.any { txt.contains(it.lowercase(Locale.getDefault())) }
        val narrationPass = profile.narrationKeywords.isEmpty() || profile.narrationKeywords.any { txt.contains(it.lowercase(Locale.getDefault())) }
        return senderPass && (tailPass || narrationPass)
    }

    private fun passesExpenseGuardrails(body: String): Boolean {
        val txt = body.lowercase(Locale.getDefault())
        val positive = listOf("debited", "spent", "purchase", "pos", "upi", "atm", "paid").any { txt.contains(it) }
        val negative = listOf("credit limit", "available balance", "avl bal", "emi booking", "reversal", "chargeback").any { txt.contains(it) }
        return positive && !negative
    }
}

object SmsAmountParser {
    // Balance/limit words — amounts preceded or followed by these are NOT real transaction amounts
    private val balanceContextWords = listOf(
        "avl bal", "avl limit", "avl lmt", "avl. limit", "avl. bal",
        "available balance", "available limit", "available credit",
        "credit limit", "outstanding", "total due", "min due",
        "minimum due", "total outstanding", "current balance", "ledger balance"
    )

    private val transactionContextWords = listOf(
        "spent", "debited", "credited", "paid", "purchase", "txn of", "transaction of"
    )

    // Foreign currencies checked FIRST — they appear near transaction verbs, not balance lines
    private val foreignPatterns = listOf(
        "THB" to Regex("(?:THB|฿)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "USD" to Regex("(?:USD|US\\$|\\$)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "EUR" to Regex("(?:EUR|€)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "GBP" to Regex("(?:GBP|£)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "AED" to Regex("(?:AED)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "SGD" to Regex("(?:SGD)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "JPY" to Regex("(?:JPY|¥)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        "MYR" to Regex("(?:MYR|RM)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
    )
    private val inrRegex = Regex("(?:INR|Rs\\.?|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)

    fun parseAmount(body: String): Pair<String, Double>? {
        val lower = body.lowercase(Locale.getDefault())

        // 1. Try foreign currencies first — never appears in balance lines
        for ((ccy, regex) in foreignPatterns) {
            for (match in regex.findAll(body)) {
                val amount = match.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
                val ctx = contextAround(lower, match.range.first)
                if (balanceContextWords.any { ctx.contains(it) }) continue
                return ccy to amount
            }
        }

        // 2. Try INR near transaction keywords (debit/credit action amounts)
        for (match in inrRegex.findAll(body)) {
            val amount = match.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
            val ctx = contextAround(lower, match.range.first)
            if (balanceContextWords.any { ctx.contains(it) }) continue
            if (transactionContextWords.any { ctx.contains(it) }) return "INR" to amount
        }

        // 3. Fall back to any non-balance INR amount
        for (match in inrRegex.findAll(body)) {
            val amount = match.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
            val ctx = contextAround(lower, match.range.first)
            if (balanceContextWords.any { ctx.contains(it) }) continue
            return "INR" to amount
        }

        return null
    }

    private fun contextAround(lower: String, pos: Int): String =
        lower.substring((pos - 50).coerceAtLeast(0), (pos + 50).coerceAtMost(lower.length))
}

// Static fallback rates to INR used when network is unavailable
object StaticFxRates {
    private val rates = mapOf(
        "USD" to 83.2, "EUR" to 90.4, "GBP" to 106.0,
        "AED" to 22.65, "SGD" to 61.4, "JPY" to 0.56,
        "THB" to 2.31, "MYR" to 17.8, "HKD" to 10.65
    )

    fun toInr(amount: Double, currency: String): Double {
        val c = currency.uppercase(Locale.getDefault())
        if (c == "INR") return amount
        return amount * (rates[c] ?: 1.0)
    }
}
