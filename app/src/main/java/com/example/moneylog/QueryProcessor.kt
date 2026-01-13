package com.example.moneylog

import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class QueryProcessor(private val transactions: List<Transaction>) {

    fun process(query: String): String {
        val lowerQuery = query.lowercase(Locale.getDefault()).trim()

        // 1. Debt Logic ("owe", "lent", "borrow")
        if (lowerQuery.contains("owe") || lowerQuery.contains("lent") || lowerQuery.contains("borrow")) {
            return processDebtQuery(lowerQuery)
        }

        // 2. Spending/Income Analysis ("spent", "earned", "cost", etc.)
        if (lowerQuery.contains("spent") || lowerQuery.contains("cost") || lowerQuery.contains("expense") ||
            lowerQuery.contains("earned") || lowerQuery.contains("income") || lowerQuery.contains("made")) {
            val isExpense = !lowerQuery.contains("earned") && !lowerQuery.contains("income") && !lowerQuery.contains("made")
            return processSpendingQuery(lowerQuery, isExpense)
        }

        // 3. FALLBACK: General Keyword Summary
        // If the user types just "mom", "bus", "salary" -> Show net total for that word.
        return processGeneralSummary(lowerQuery)
    }

    private fun processGeneralSummary(keyword: String): String {
        // Filter transactions that contain the keyword in the description
        val matches = transactions.filter { it.description.lowercase().contains(keyword) }

        if (matches.isEmpty()) {
            // New text with HTML tags for styling (<small>)
            return "No transactions found matching that description.<br><br><small>Try using keywords like 'Food', 'Salary', or 'Rent'.</small>"
        }

        val total = matches.sumOf { it.amount }
        val absTotal = abs(total)

        // Return a smart summary based on the net result
        return if (total < 0) {
            "Total spent on '$keyword': $absTotal"
        } else if (total > 0) {
            "Total received from '$keyword': $absTotal"
        } else {
            "Break-even for '$keyword' (Total: 0)"
        }
    }

    private fun processSpendingQuery(query: String, isExpense: Boolean): String {
        // Filter by Date
        val filteredByDate = filterByTime(transactions, query)

        // Filter by Keyword (remove common stopwords)
        val stopWords = listOf("how", "much", "did", "i", "spend", "on", "this", "month", "week", "today", "money", "earned", "made", "total", "cost", "is", "the", "for")
        val keywords = query.split(" ").filter { !stopWords.contains(it) }

        var matchedList = filteredByDate
        if (keywords.isNotEmpty()) {
            matchedList = matchedList.filter { t ->
                keywords.any { k -> t.description.lowercase().contains(k) }
            }
        }

        // Calculate
        val sum = matchedList.filter { if(isExpense) it.amount < 0 else it.amount > 0 }
            .sumOf { it.amount }

        val absSum = abs(sum)
        val timeFrame = if(query.contains("month")) "this month" else if(query.contains("week")) "this week" else if(query.contains("today")) "today" else "in total"

        return if (isExpense) "You spent $absSum on '${keywords.joinToString(" ")}' $timeFrame."
        else "You earned $absSum from '${keywords.joinToString(" ")}' $timeFrame."
    }

    private fun processDebtQuery(query: String): String {
        // Extract person name
        val stopWords = listOf("how", "much", "does", "do", "i", "owe", "me", "money", "to", "from", "is", "my", "mother", "mom", "dad", "father", "friend")

        var person = ""
        val potentialNames = query.split(" ")

        // Find a word in the query that also exists in our transaction history descriptions
        for (word in potentialNames) {
            if (word.length > 2 && !listOf("how", "much", "owe").contains(word)) {
                if (transactions.any { it.description.lowercase().contains(word) }) {
                    person = word
                    break
                }
            }
        }

        if (person.isEmpty()) return "Could not find a person name from your history in that query."

        // Logic:
        // "Lent to X" (-500) -> Net -500. Means X owes me 500.
        // "Borrowed from X" (+500) -> Net +500. Means I owe X 500.
        val relevant = transactions.filter { it.description.lowercase().contains(person) }
        val net = relevant.sumOf { it.amount }

        return if (net < 0) {
            "${person.replaceFirstChar { it.uppercase() }} owes you ${abs(net)}"
        } else if (net > 0) {
            "You owe ${person.replaceFirstChar { it.uppercase() }} $net"
        } else {
            "You and $person are even."
        }
    }

    private fun filterByTime(list: List<Transaction>, query: String): List<Transaction> {
        val cal = Calendar.getInstance()

        if (query.contains("today")) {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            return list.filter { it.timestamp >= cal.timeInMillis }
        }
        if (query.contains("week")) {
            cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            return list.filter { it.timestamp >= cal.timeInMillis }
        }
        if (query.contains("month")) {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            return list.filter { it.timestamp >= cal.timeInMillis }
        }
        return list
    }
}