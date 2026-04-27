package com.example.carpayin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Android Keystore (StrongBox) 기반 ECDSA 키 관리
 *
 * ▸ 앱 최초 설치 시 키쌍 생성 (StrongBox 우선, 미지원 시 TEE fallback)
 * ▸ 개인키는 Keystore 외부로 절대 노출되지 않음 (서명 연산도 Keystore 내부에서 처리)
 * ▸ 공개키(PEM)와 인증서(PEM)는 mTLS 핸드쉐이크에 사용
 *
 * ※ ARQC 서명(signArqc)은 새 흐름에서 제거됨
 *    → 결제는 백엔드가 customer_key로 직접 처리, 앱은 어떤 결제 키도 보유하지 않음
 */
object KeystoreManager {
    private const val TAG = "KeystoreManager"
    private const val KEY_ALIAS = "carpayin_ecdsa_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    // ── 키쌍 생성 ─────────────────────────────────────────────────────────────

    /**
     * 앱 최초 실행 시 1회 호출.
     * 이미 키가 있으면 생성을 건너뜁니다.
     * @return 공개키 PEM 문자열
     */
    fun generateKeyPairIfNeeded(): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            // StrongBox 먼저 시도 → 실패 시(에뮬레이터/미지원 기기) TEE로 자동 fallback
            val generated = tryGenerateKey(useStrongBox = true)
                         || tryGenerateKey(useStrongBox = false)
            if (!generated) throw IllegalStateException("ECDSA 키 생성 실패 (StrongBox + TEE 모두 실패)")
        }

        return getPublicKeyPem()
    }

    private fun tryGenerateKey(useStrongBox: Boolean): Boolean {
        return try {
            val specBuilder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))

            // StrongBox는 API 28+ + 하드웨어 지원 기기에서만
            if (useStrongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                specBuilder.setIsStrongBoxBacked(true)
            }

            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER
            )
            kpg.initialize(specBuilder.build())
            kpg.generateKeyPair()
            Log.d(TAG, "ECDSA P-256 키쌍 생성 완료 (StrongBox=$useStrongBox)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "키 생성 실패 (StrongBox=$useStrongBox): ${e.message}")
            false
        }
    }

    // ── 공개키 PEM ───────────────────────────────────────────────────────────

    /**
     * mTLS 핸드쉐이크 시 백엔드에 전송할 공개키 PEM
     */
    fun getPublicKeyPem(): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
            ?: throw IllegalStateException("키가 없습니다. generateKeyPairIfNeeded()를 먼저 호출하세요.")
        val encoded = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----"
    }

    // ── 인증서 PEM (mTLS 클라이언트 인증서) ──────────────────────────────────

    /**
     * Android Keystore에 저장된 자체 서명 인증서(X.509) PEM 반환.
     * mTLS 연결 시 클라이언트 인증서로 사용합니다.
     * 인증서 지문(SHA-256)은 백엔드 DB에 저장되어 VIN과 매핑됩니다.
     */
    fun getCertificatePem(): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        val cert = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
            ?: throw IllegalStateException("인증서가 없습니다.")
        val encoded = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----"
    }

    /**
     * 인증서 SHA-256 지문 반환 (백엔드 저장 / 검증용)
     */
    fun getCertificateFingerprint(): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        val cert = keyStore.getCertificate(KEY_ALIAS)
            ?: throw IllegalStateException("인증서가 없습니다.")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val fp = digest.digest(cert.encoded)
        return fp.joinToString(":") { "%02X".format(it) }
    }

    // ── 키 존재 여부 확인 ─────────────────────────────────────────────────────

    fun hasKey(): Boolean {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        return keyStore.containsAlias(KEY_ALIAS)
    }

    // ── 키 삭제 (초기화용) ────────────────────────────────────────────────────

    fun deleteKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
            Log.d(TAG, "키 삭제 완료")
        }
    }
}
