package com.mainnetwallet.polybot.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Number and time formatting shared by all screens. Mirrors frontend/src/lib/format.ts. */
object Fmt {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val dateTimeFormat = DateTimeFormatter.ofPattern("MMM dd HH:mm:ss", Locale.US).withZone(ZoneId.systemDefault())

    private fun ok(v: Double?): Boolean = v != null && !v.isNaN() && !v.isInfinite()

    fun usd(v: Double?, digits: Int = 2): String {
        if (!ok(v)) return "—"
        val x = v!!
        return (if (x < 0) "-" else "") + "$" + String.format(Locale.US, "%.${digits}f", Math.abs(x))
    }

    fun usdGrouped(v: Double?, digits: Int = 2): String {
        if (!ok(v)) return "—"
        return "$" + String.format(Locale.US, "%,.${digits}f", v!!)
    }

    fun signedUsd(v: Double?, digits: Int = 2): String {
        if (!ok(v)) return "—"
        val x = v!!
        val sign = if (x > 0) "+" else if (x < 0) "-" else ""
        return sign + "$" + String.format(Locale.US, "%.${digits}f", Math.abs(x))
    }

    fun cents(v: Double?, digits: Int = 1): String {
        if (!ok(v)) return "—"
        return String.format(Locale.US, "%.${digits}f", v!! * 100.0) + "¢"
    }

    /** A 0..1 share price in cents without trailing zeros, e.g. 0.6 -> "60¢", 0.875 -> "87.5¢". */
    fun centsShort(v: Double?): String {
        if (!ok(v)) return "—"
        return plain(Math.round(v!! * 1000.0) / 10.0) + "¢"
    }

    /** A 0..1 share price as a decimal, e.g. 0.874. */
    fun price(v: Double?, digits: Int = 3): String {
        if (!ok(v)) return "—"
        return String.format(Locale.US, "%.${digits}f", v!!)
    }

    /** A percentage with an explicit sign, e.g. +2.4% / -0.8%. */
    fun signedPercent(v: Double?, digits: Int = 1): String {
        if (!ok(v)) return "—"
        val x = v!!
        val sign = if (x > 0) "+" else if (x < 0) "-" else ""
        return sign + String.format(Locale.US, "%.${digits}f", Math.abs(x)) + "%"
    }

    fun num(v: Double?, digits: Int = 2): String {
        if (!ok(v)) return "—"
        return String.format(Locale.US, "%.${digits}f", v!!)
    }

    fun percent(v: Double?, digits: Int = 1): String {
        if (!ok(v)) return "—"
        return String.format(Locale.US, "%.${digits}f", v!!) + "%"
    }

    fun time(iso: String?): String = parse(iso)?.let { timeFormat.format(Instant.ofEpochMilli(it)) } ?: "—"

    fun dateTime(iso: String?): String = parse(iso)?.let { dateTimeFormat.format(Instant.ofEpochMilli(it)) } ?: "—"

    fun clock(seconds: Double): String {
        val total = Math.max(0.0, seconds)
        val whole = Math.ceil(total).toInt()
        return String.format(Locale.US, "%d:%02d", whole / 60, whole % 60)
    }

    fun shortId(value: String?, head: Int = 8, tail: Int = 4): String {
        if (value.isNullOrEmpty()) return "—"
        if (value.length <= head + tail + 1) return value
        return value.take(head) + "…" + value.takeLast(tail)
    }

    fun parse(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    java.time.LocalDateTime.parse(iso).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /** Stable text of a settings value: whole numbers without ".0". */
    fun plain(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}
