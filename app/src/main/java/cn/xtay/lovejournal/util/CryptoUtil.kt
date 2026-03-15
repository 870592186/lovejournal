package cn.xtay.lovejournal.util

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.SecretKeySpec

object CryptoUtil {
    // 🚀 你指定的唯一通行密码
    private const val FIXED_SECRET = "1998qyf"

    private fun getSecretKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(FIXED_SECRET.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(hashBytes, "AES")
    }

    fun encryptFile(source: File, dest: File, context: Context) {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        FileInputStream(source).use { fis ->
            FileOutputStream(dest).use { fos ->
                CipherOutputStream(fos, cipher).use { cos ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        cos.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    fun decryptFile(source: File, dest: File, context: Context) {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey())
        FileInputStream(source).use { fis ->
            CipherInputStream(fis, cipher).use { cis ->
                FileOutputStream(dest).use { fos ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (cis.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    fun getSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}