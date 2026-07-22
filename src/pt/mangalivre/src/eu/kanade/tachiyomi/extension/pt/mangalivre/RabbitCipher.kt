package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.util.Base64
import java.security.MessageDigest

/**
 * Decriptador compatível com `CryptoJS.Rabbit.decrypt(cipher, passphrase)` no
 * formato OpenSSL ("Salted__"), que é o esquema que o ToonLivre passou a usar
 * nas respostas de conteúdo (páginas e lista de capítulos paginada).
 *
 * O corpo cifrado vem como base64 de `Salted__` + salt(8 bytes) + ciphertext.
 * A chave/IV são derivados via EVP_BytesToKey (MD5, 1 iteração), com keySize de
 * 128 bits e ivSize de 64 bits — os defaults do CryptoJS para o Rabbit.
 *
 * A porta do algoritmo foi verificada bit-a-bit contra `crypto-js@4.2.0` usando
 * as capturas reais em `.local/21-07-26-toonlivre.net.har`.
 */
object RabbitCipher {

    fun decrypt(cipherTextBase64: String, passphrase: String): String {
        val raw = Base64.decode(cipherTextBase64, Base64.DEFAULT)
        require(raw.size > 16 && String(raw, 0, 8, Charsets.US_ASCII) == "Salted__") {
            "Formato OpenSSL inválido"
        }
        val salt = raw.copyOfRange(8, 16)
        val ct = raw.copyOfRange(16, raw.size)
        val keyAndIv = evpKdf(passphrase.toByteArray(Charsets.UTF_8), salt, KEY_LEN + IV_LEN)
        val key = keyAndIv.copyOfRange(0, KEY_LEN)
        val iv = keyAndIv.copyOfRange(KEY_LEN, KEY_LEN + IV_LEN)
        return String(rabbit(ct, key, iv), Charsets.UTF_8)
    }

    /** EVP_BytesToKey (OpenSSL) com MD5 e 1 iteração. */
    private fun evpKdf(pass: ByteArray, salt: ByteArray, total: Int): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        var derived = ByteArray(0)
        var block = ByteArray(0)
        while (derived.size < total) {
            md.reset()
            md.update(block)
            md.update(pass)
            md.update(salt)
            block = md.digest()
            derived += block
        }
        return derived.copyOf(total)
    }

    private fun beWord(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xff shl 24) or
        (b[off + 1].toInt() and 0xff shl 16) or
        (b[off + 2].toInt() and 0xff shl 8) or
        (b[off + 3].toInt() and 0xff)

    private fun swapEndian(x: Int): Int = ((x shl 8) or (x ushr 24)) and 0x00ff00ff or
        (((x shl 24) or (x ushr 8)) and 0xff00ff00.toInt())

    private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private fun ltu(a: Int, b: Int): Boolean = (a.toLong() and 0xffffffffL) < (b.toLong() and 0xffffffffL)

    private fun rabbit(ct: ByteArray, key16: ByteArray, iv8: ByteArray): ByteArray {
        val k = IntArray(4) { swapEndian(beWord(key16, it * 4)) }
        val x = intArrayOf(
            k[0],
            (k[3] shl 16) or (k[2] ushr 16),
            k[1],
            (k[0] shl 16) or (k[3] ushr 16),
            k[2],
            (k[1] shl 16) or (k[0] ushr 16),
            k[3],
            (k[2] shl 16) or (k[1] ushr 16),
        )
        val c = intArrayOf(
            (k[2] shl 16) or (k[2] ushr 16),
            (k[0] and 0xffff0000.toInt()) or (k[1] and 0x0000ffff),
            (k[3] shl 16) or (k[3] ushr 16),
            (k[1] and 0xffff0000.toInt()) or (k[2] and 0x0000ffff),
            (k[0] shl 16) or (k[0] ushr 16),
            (k[2] and 0xffff0000.toInt()) or (k[3] and 0x0000ffff),
            (k[1] shl 16) or (k[1] ushr 16),
            (k[3] and 0xffff0000.toInt()) or (k[0] and 0x0000ffff),
        )
        val state = RabbitState(x, c)
        repeat(4) { state.next() }
        for (i in 0 until 8) c[i] = c[i] xor x[(i + 4) and 7]

        val iv0 = beWord(iv8, 0)
        val iv1 = beWord(iv8, 4)
        val i0 = swapEndian(iv0)
        val i2 = swapEndian(iv1)
        val i1 = (i0 ushr 16) or (i2 and 0xffff0000.toInt())
        val i3 = (i2 shl 16) or (i0 and 0x0000ffff)
        c[0] = c[0] xor i0
        c[1] = c[1] xor i1
        c[2] = c[2] xor i2
        c[3] = c[3] xor i3
        c[4] = c[4] xor i0
        c[5] = c[5] xor i1
        c[6] = c[6] xor i2
        c[7] = c[7] xor i3
        repeat(4) { state.next() }

        val out = ByteArray(ct.size)
        val s = IntArray(4)
        var off = 0
        while (off < ct.size) {
            state.next()
            s[0] = x[0] xor (x[5] ushr 16) xor (x[3] shl 16)
            s[1] = x[2] xor (x[7] ushr 16) xor (x[5] shl 16)
            s[2] = x[4] xor (x[1] ushr 16) xor (x[7] shl 16)
            s[3] = x[6] xor (x[3] ushr 16) xor (x[1] shl 16)
            for (i in 0 until 4) s[i] = swapEndian(s[i])
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    val idx = off + i * 4 + j
                    if (idx >= ct.size) break
                    val ksByte = (s[i] ushr (24 - 8 * j)) and 0xff
                    out[idx] = (ct[idx].toInt() xor ksByte).toByte()
                }
            }
            off += 16
        }
        return out
    }

    /** Estado mutável do Rabbit (X, C, b) com a função de transição `next()`. */
    private class RabbitState(val x: IntArray, val c: IntArray) {
        private var b = 0
        private val cPrev = IntArray(8)
        private val g = IntArray(8)

        fun next() {
            for (i in 0 until 8) cPrev[i] = c[i]
            c[0] = c[0] + 0x4d34d34d + b
            c[1] = c[1] + 0xd34d34d3.toInt() + (if (ltu(c[0], cPrev[0])) 1 else 0)
            c[2] = c[2] + 0x34d34d34 + (if (ltu(c[1], cPrev[1])) 1 else 0)
            c[3] = c[3] + 0x4d34d34d + (if (ltu(c[2], cPrev[2])) 1 else 0)
            c[4] = c[4] + 0xd34d34d3.toInt() + (if (ltu(c[3], cPrev[3])) 1 else 0)
            c[5] = c[5] + 0x34d34d34 + (if (ltu(c[4], cPrev[4])) 1 else 0)
            c[6] = c[6] + 0x4d34d34d + (if (ltu(c[5], cPrev[5])) 1 else 0)
            c[7] = c[7] + 0xd34d34d3.toInt() + (if (ltu(c[6], cPrev[6])) 1 else 0)
            b = if (ltu(c[7], cPrev[7])) 1 else 0
            for (i in 0 until 8) {
                val gx = x[i] + c[i]
                val ga = (gx and 0xffff).toLong()
                val gb = (gx ushr 16).toLong()
                val gh = ((((ga * ga) ushr 17) + ga * gb) ushr 15) + gb * gb
                val gl = (gx and 0xffff0000.toInt()) * gx + (gx and 0x0000ffff) * gx
                g[i] = gh.toInt() xor gl
            }
            x[0] = g[0] + rotl(g[7], 16) + rotl(g[6], 16)
            x[1] = g[1] + rotl(g[0], 8) + g[7]
            x[2] = g[2] + rotl(g[1], 16) + rotl(g[0], 16)
            x[3] = g[3] + rotl(g[2], 8) + g[1]
            x[4] = g[4] + rotl(g[3], 16) + rotl(g[2], 16)
            x[5] = g[5] + rotl(g[4], 8) + g[3]
            x[6] = g[6] + rotl(g[5], 16) + rotl(g[4], 16)
            x[7] = g[7] + rotl(g[6], 8) + g[5]
        }
    }

    private const val KEY_LEN = 16
    private const val IV_LEN = 8
}
