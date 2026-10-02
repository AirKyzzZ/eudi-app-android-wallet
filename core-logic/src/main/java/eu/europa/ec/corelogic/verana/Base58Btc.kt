package eu.europa.ec.corelogic.verana

import java.math.BigInteger

object Base58Btc {
    fun decode(value: String): ByteArray {
        val radix = BigInteger.valueOf(ALPHABET.length.toLong())
        val number = value.fold(BigInteger.ZERO) { accumulated, character ->
            val digit = ALPHABET.indexOf(character)
            require(digit >= 0) { "invalid base58 character $character" }
            accumulated * radix + BigInteger.valueOf(digit.toLong())
        }
        val magnitude = if (number.signum() == 0) {
            ByteArray(0)
        } else {
            number.toByteArray().let { bytes -> if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes }
        }
        val leadingZeros = value.takeWhile { it == ALPHABET.first() }.length
        return ByteArray(leadingZeros) + magnitude
    }

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
}
