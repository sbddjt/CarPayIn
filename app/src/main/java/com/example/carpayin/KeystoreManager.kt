package com.example.carpayin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature

object KeystoreManager {
    private const val TAG = "KeystoreManager"
    private const val KEY_ALIAS = "carpayin_ecdsa_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    fun generateKeyPairIfNeeded(): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER
            )
            keyPairGenerator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .build()
            )
            keyPairGenerator.generateKeyPair()
            Log.d(TAG, "ECDSA 키쌍 생성 완료")
        }

        return getPublicKeyPem()
    }

    fun getPublicKeyPem(): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
            ?: throw IllegalStateException("키가 없습니다")
        val encoded = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----"
    }

    // ARQC 생성: 명세서 §9.2 서명 대상 페이로드
    fun signArqc(vin: String, amount: Int, nonce: String, correlationId: String, atc: Int): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey

        val vinBytes = vin.toByteArray(Charsets.US_ASCII).copyOf(17)
        val amountBytes = java.nio.ByteBuffer.allocate(8).putLong(amount.toLong()).array()
        val nonceBytes = nonce.toByteArray(Charsets.US_ASCII)
        val correlationBytes = correlationId.toByteArray(Charsets.US_ASCII)
        val atcBytes = java.nio.ByteBuffer.allocate(4).putInt(atc).array()

        val payload = vinBytes + amountBytes + nonceBytes + correlationBytes + atcBytes

        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(payload)
        val signed = signature.sign()

        return signed.joinToString("") { "%02x".format(it) }
    }

    fun hasKey(): Boolean {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        return keyStore.containsAlias(KEY_ALIAS)
    }
}
