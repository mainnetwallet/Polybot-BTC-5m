package com.mainnetwallet.polybot

/** Loads JSON captured from the real backend (see src/test/resources/fixtures). */
object Fixtures {
    fun text(name: String): String =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "Missing fixture $name" }
            .bufferedReader().use { it.readText() }
}
