package dev.anonymous.ticket_reader.data.analyzers

import android.util.Log
import java.util.LinkedList

class CredentialsAnalyzer(
    private val onCredentialsConfirmed: (String, String) -> Unit
) {

    private val recentUsernames = LinkedList<String>()
    private val recentPasswords = LinkedList<String>()

    // تنظيف القراءات
    private fun clean(text: String): String {
        var cleanedText = text
            .replace("O", "0", true)
            .replace("B", "8", true)
            .replace("i", "1", true)
            .replace("I", "1", true)
            .replace("l", "1", true)
            .replace(" ", "")
            .replace("(", "")
            .replace(")", "")
            .trim()

        if (cleanedText.length > 12) {
            cleanedText = cleanedText.substring(cleanedText.length - 12)
        }
        return cleanedText
    }

    // التحقق من أن القيمة تحتوي أرقام فقط
    private fun isValidNumber(text: String): Boolean {
        return text.all { it.isDigit() }
    }

    fun onDetect(label: String, rawText: String) {
        val text = clean(rawText)

        if (text.isBlank()) return

        // إذا فيها حروف → تجاهل
        if (!isValidNumber(text)) {
            Log.d("CRED", "Ignored non-numeric: $text")
            return
        }

        when (label) {
            "username" -> {
                recentUsernames.add(text)
                if (recentUsernames.size > 10) recentUsernames.removeFirst()
            }

            "password" -> {
                recentPasswords.add(text)
                if (recentPasswords.size > 10) recentPasswords.removeFirst()
            }
        }

        checkIfCredentialsStable()
    }

    private fun checkIfCredentialsStable() {
        val username = getStableValue(recentUsernames)
        val password = getStableValue(recentPasswords)

        if (username != null && password != null) {
            Log.d("CRED", "✅ Confirmed: user=$username pass=$password")
            onCredentialsConfirmed(username, password)
            recentUsernames.clear()
            recentPasswords.clear()
        }
    }

    // الحصول على القيمة الأكثر تكرارًا
    private fun getStableValue(list: LinkedList<String>): String? {
        if (list.isEmpty()) return null

        val mostCommon = list
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }

        return if (mostCommon != null && mostCommon.value >= 4) {
            mostCommon.key
        } else null
    }
}