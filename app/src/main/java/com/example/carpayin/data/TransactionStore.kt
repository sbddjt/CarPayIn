package com.example.carpayin.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * 거래 내역 로컬 저장소
 * 최대 20건 보관 (FIFO)
 */
object TransactionStore {

    private const val PREF_KEY = "transactions"
    private const val MAX_RECORDS = 20
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)

    data class Transaction(
        val transactionId: String,
        val lotId: String,
        val amount: Int,
        val timestamp: Long
    )

    fun save(context: Context, transactionId: String, lotId: String, amount: Int) {
        val prefs = context.getSharedPreferences("carpayin", Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_KEY, "[]")
        val arr = try { JSONArray(existing) } catch (e: Exception) { JSONArray() }

        val entry = JSONObject().apply {
            put("txId", transactionId)
            put("lotId", lotId)
            put("amount", amount)
            put("ts", System.currentTimeMillis())
        }
        arr.put(entry)

        // 최대 20건 유지
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - MAX_RECORDS)
        for (i in start until arr.length()) trimmed.put(arr.get(i))

        prefs.edit().putString(PREF_KEY, trimmed.toString()).apply()
    }

    fun load(context: Context): List<Transaction> {
        val prefs = context.getSharedPreferences("carpayin", Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }

        val list = mutableListOf<Transaction>()
        for (i in arr.length() - 1 downTo 0) {
            val obj = arr.getJSONObject(i)
            list.add(Transaction(
                transactionId = obj.optString("txId", ""),
                lotId = obj.optString("lotId", "주차장"),
                amount = obj.optInt("amount", 0),
                timestamp = obj.optLong("ts", 0L)
            ))
        }
        return list
    }

    fun clear(context: Context) {
        context.getSharedPreferences("carpayin", Context.MODE_PRIVATE)
            .edit().remove(PREF_KEY).apply()
    }

    fun formatDate(timestamp: Long): String = dateFormat.format(Date(timestamp))

    fun formatAmount(amount: Int): String = "%,d원".format(amount)
}
