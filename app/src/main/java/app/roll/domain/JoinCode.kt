package app.roll.domain

import kotlin.random.Random

/** Six characters, uppercase, without the look-alikes 0/O/1/I. */
object JoinCode {
    const val LENGTH = 6
    const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    fun generate(random: Random = Random.Default): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Uppercases and drops anything that can't be in a code. */
    fun normalize(input: String): String =
        input.uppercase().filter { it in ALPHABET }.take(LENGTH)

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }
}
