package io.github.odnwks.mimir.update

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Checks an ed25519 signature (RFC 8032). Android 9 to 12 have no built-in one, so this small
 * checker does it; it only verifies, it never signs and never holds a secret.
 */
object Ed25519 {
    private val P = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val L = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
    private val D = (BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val I = BigInteger.valueOf(2).modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)

    private class Pt(val x: BigInteger, val y: BigInteger)

    private val ZERO = Pt(BigInteger.ZERO, BigInteger.ONE)
    private val B: Pt = run {
        val y = (BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P)).mod(P)
        Pt(recoverX(y, false)!!, y)
    }

    private fun recoverX(y: BigInteger, odd: Boolean): BigInteger? {
        val xx = ((y * y - BigInteger.ONE) * (D * y * y + BigInteger.ONE).modInverse(P)).mod(P)
        var x = xx.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
        if ((x * x - xx).mod(P) != BigInteger.ZERO) x = (x * I).mod(P)
        if ((x * x - xx).mod(P) != BigInteger.ZERO) return null
        if (x.testBit(0) != odd) x = P - x
        return x
    }

    private fun add(a: Pt, b: Pt): Pt {
        val t = (D * a.x * b.x * a.y * b.y).mod(P)
        val x = ((a.x * b.y + b.x * a.y) * (BigInteger.ONE + t).mod(P).modInverse(P)).mod(P)
        val y = ((a.y * b.y + a.x * b.x) * (BigInteger.ONE - t).mod(P).modInverse(P)).mod(P)
        return Pt(x, y)
    }

    private fun mul(k: BigInteger, p: Pt): Pt {
        var r = ZERO
        var q = p
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = add(r, q)
            q = add(q, q)
        }
        return r
    }

    private fun le(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun encode(p: Pt): ByteArray {
        val out = ByteArray(32)
        val y = p.y.toByteArray().reversedArray()
        for (i in 0 until minOf(32, y.size)) out[i] = y[i]
        if (p.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun decode(b: ByteArray): Pt? {
        if (b.size != 32) return null
        val odd = (b[31].toInt() and 0x80) != 0
        val c = b.copyOf(); c[31] = (c[31].toInt() and 0x7f).toByte()
        val y = le(c)
        if (y >= P) return null
        val x = recoverX(y, odd) ?: return null
        return Pt(x, y)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decode(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decode(rBytes) ?: return false
        val s = le(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val md = MessageDigest.getInstance("SHA-512")
        md.update(rBytes); md.update(publicKey); md.update(message)
        val k = le(md.digest()).mod(L)
        val left = mul(s, B)
        val right = add(r, mul(k, a))
        return encode(left).contentEquals(encode(right))
    }
}
