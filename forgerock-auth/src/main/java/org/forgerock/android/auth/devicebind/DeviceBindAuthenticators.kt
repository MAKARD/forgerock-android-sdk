/*
 * Copyright (c) 2022 - 2023 ForgeRock. All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */

package org.forgerock.android.auth.devicebind

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Parcelable
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.AuthenticationCallback
import androidx.biometric.BiometricPrompt.ERROR_CANCELED
import androidx.biometric.BiometricPrompt.ERROR_HW_NOT_PRESENT
import androidx.biometric.BiometricPrompt.ERROR_HW_UNAVAILABLE
import androidx.biometric.BiometricPrompt.ERROR_LOCKOUT
import androidx.biometric.BiometricPrompt.ERROR_LOCKOUT_PERMANENT
import androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON
import androidx.biometric.BiometricPrompt.ERROR_NO_BIOMETRICS
import androidx.biometric.BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt.ERROR_NO_SPACE
import androidx.biometric.BiometricPrompt.ERROR_TIMEOUT
import androidx.biometric.BiometricPrompt.ERROR_UNABLE_TO_PROCESS
import androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED
import androidx.biometric.BiometricPrompt.ERROR_VENDOR
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.Base64
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.forgerock.android.auth.CryptoKey
import org.forgerock.android.auth.callback.Attestation
import org.forgerock.android.auth.callback.DeviceBindingAuthenticationType
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val TAG = DeviceAuthenticator::class.java.simpleName

private const val ANDROID_VERSION = "android-version"
private const val CHALLENGE = "challenge"
private const val PLATFORM = "platform"

/**
 * Device Authenticator Interface
 */
interface DeviceAuthenticator {

    /**
     * generate the public and private [KeyPair] with Challenge
     */
    suspend fun generateKeys(context: Context, attestation: Attestation): KeyPair

    /**
     * Authenticate the user to access the
     */
    suspend fun authenticate(context: Context): DeviceBindingStatus

    /**
     * Set the Authentication Prompt
     */
    fun prompt(prompt: Prompt) {
        //Do Nothing
    }

    /**
     * sign the challenge sent from the server and generate signed JWT
     * @param keyPair Public and private key
     * @param kid Generated kid from the Preference
     * @param userId userId received from server
     * @param challenge challenge received from server
     */
    fun sign(context: Context,
             keyPair: KeyPair,
             kid: String,
             userId: String,
             challenge: String,
             expiration: Date,
             attestation: Attestation = Attestation.None): String {
        val builder = RSAKey.Builder(keyPair.publicKey).keyUse(KeyUse.SIGNATURE).keyID(kid)
            .algorithm(JWSAlgorithm.RS512)
        if (attestation !is Attestation.None) {
            builder.x509CertChain(getCertificateChain(userId))
        }
        val jwk = builder.build();
        val signedJWT = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS512).keyID(kid).jwk(jwk).build(),
            JWTClaimsSet.Builder().subject(userId)
                .issuer(context.packageName)
                .expirationTime(expiration)
                .claim(PLATFORM, "android")
                .claim(ANDROID_VERSION, Build.VERSION.SDK_INT)
                .claim(CHALLENGE, challenge).build())
        signedJWT.sign(RSASSASigner(keyPair.privateKey))
        return signedJWT.serialize()
    }

    private fun getCertificateChain(userId: String): List<Base64> {
        val chain = CryptoKey(userId).getCertificateChain()
        return chain.map {
            Base64.encode(it.encoded)
        }.toList()
    }

    /**
     * sign the challenge sent from the server and generate signed JWT
     * @param userKey User Information
     * @param challenge challenge received from server
     */
    fun sign(context: Context,
             userKey: UserKey,
             privateKey: PrivateKey,
             challenge: String,
             expiration: Date): String {
        val signedJWT = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS512).keyID(userKey.kid).build(),
            JWTClaimsSet.Builder().subject(userKey.userId)
                .issuer(context.packageName)
                .claim(CHALLENGE, challenge)
                .expirationTime(expiration).build())
        signedJWT.sign(RSASSASigner(privateKey))
        return signedJWT.serialize()
    }

    /**
     * check if supported device binding
     */
    fun isSupported(context: Context, attestation: Attestation = Attestation.None): Boolean {
        return if (attestation !is Attestation.None) {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        } else {
            true
        }
    }

    fun type(): DeviceBindingAuthenticationType

    fun deleteKeys(context: Context)

}

fun DeviceAuthenticator.initialize(userId: String, prompt: Prompt): DeviceAuthenticator {

    //Inject objects
    if (this is BiometricAuthenticator) {
        this.setBiometricHandler(BiometricBindingHandler(prompt.title,
            prompt.subtitle,
            prompt.description,
            deviceBindAuthenticationType = this.type()))
    }
    initialize(userId)
    this.prompt(prompt)
    return this
}

fun DeviceAuthenticator.initialize(userId: String): DeviceAuthenticator {
    //Inject objects
    if (this is CryptoAware) {
        this.setKey(CryptoKey(userId))
    }
    return this
}

@Parcelize
class Prompt(val title: String, val subtitle: String, var description: String) : Parcelable

/**
 * Create public and private keypair
 * @param publicKey The RSA Public key
 * @param privateKey The RSA Private key
 * @param keyAlias KeyAlias for
 */

data class KeyPair(val publicKey: RSAPublicKey, val privateKey: PrivateKey, var keyAlias: String)

abstract class BiometricAuthenticator : CryptoAware, DeviceAuthenticator {

    @VisibleForTesting
    internal var isApi30OrAbove = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    internal lateinit var cryptoKey: CryptoKey
    internal lateinit var biometricInterface: BiometricHandler

    final override fun setKey(cryptoKey: CryptoKey) {
        this.cryptoKey = cryptoKey
    }

    fun setBiometricHandler(biometricHandler: BiometricHandler) {
        this.biometricInterface = biometricHandler
    }

    override fun deleteKeys(context: Context) {
        cryptoKey.deleteKeys()
    }

    /**
     * Display biometric prompt for authentication type
     * @param timeout Timeout for biometric prompt
     * @param statusResult Listener for receiving Biometric changes
     */
    override suspend fun authenticate(context: Context): DeviceBindingStatus =
        withContext(Dispatchers.Main) {
            suspendCoroutine { continuation ->
                //The keys may be removed due to pin change
                val privateKey = cryptoKey.getPrivateKey()
                if (privateKey == null) {
                    continuation.resume(DeviceBindingErrorStatus.ClientNotRegistered())
                } else {
                    val listener = object : AuthenticationCallback() {

                        override fun onAuthenticationError(errorCode: Int,
                                                           errString: CharSequence) {
                            when (errorCode) {
                                ERROR_CANCELED, ERROR_USER_CANCELED, ERROR_NEGATIVE_BUTTON -> continuation.resume(
                                    DeviceBindingErrorStatus.Abort(errString.toString(),
                                        code = errorCode))

                                ERROR_TIMEOUT -> continuation.resume(DeviceBindingErrorStatus.Timeout(
                                    errString.toString(),
                                    code = errorCode))

                                ERROR_NO_BIOMETRICS, ERROR_NO_DEVICE_CREDENTIAL, ERROR_HW_NOT_PRESENT -> continuation.resume(
                                    DeviceBindingErrorStatus.Unsupported(errString.toString(),
                                        code = errorCode))

                                ERROR_VENDOR -> continuation.resume(DeviceBindingErrorStatus.Unsupported(
                                    errString.toString(),
                                    code = errorCode))

                                ERROR_LOCKOUT_PERMANENT, ERROR_LOCKOUT, ERROR_NO_SPACE, ERROR_HW_UNAVAILABLE, ERROR_UNABLE_TO_PROCESS -> continuation.resume(
                                    DeviceBindingErrorStatus.UnAuthorize(errString.toString(),
                                        code = errorCode))

                                else -> {
                                    continuation.resume(DeviceBindingErrorStatus.Unknown(errString.toString(),
                                        code = errorCode))
                                }
                            }
                        }

                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            continuation.resume(Success(privateKey))
                        }

                        override fun onAuthenticationFailed() {
                            //Ignore with wrong fingerprint
                        }
                    }
                    biometricInterface.authenticate(listener)
                }
            }
        }
}

/**
 * Settings  for all the biometric authentication is configured
 */
open class BiometricOnly : BiometricAuthenticator() {


    /**
     * generate the public and private keypair
     */
    @SuppressLint("NewApi")
    override suspend fun generateKeys(context: Context, attestation: Attestation): KeyPair {
        val builder = cryptoKey.keyBuilder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setAttestationChallenge(attestation.challenge)
        }
        if (isApi30OrAbove) {
            builder.setUserAuthenticationParameters(cryptoKey.timeout,
                KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(cryptoKey.timeout)
        }
        builder.setUserAuthenticationRequired(true)
        val key = cryptoKey.createKeyPair(builder.build())
        return KeyPair(key.public as RSAPublicKey, key.private, cryptoKey.keyAlias)
    }

    /**
     * check biometric is supported
     */
    override fun isSupported(context: Context, attestation: Attestation): Boolean {
        return super.isSupported(context, attestation) &&
                biometricInterface.isSupported(BIOMETRIC_STRONG, BIOMETRIC_WEAK)
    }

    final override fun type(): DeviceBindingAuthenticationType =
        DeviceBindingAuthenticationType.BIOMETRIC_ONLY


}

/**
 * Settings for all the biometric authentication and device credential is configured
 */
open class BiometricAndDeviceCredential : BiometricAuthenticator() {

    /**
     * generate the public and private keypair
     */
    @SuppressLint("NewApi")
    override suspend fun generateKeys(context: Context, attestation: Attestation): KeyPair {
        val builder = cryptoKey.keyBuilder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setAttestationChallenge(attestation.challenge)
        }
        if (isApi30OrAbove) {
            builder.setUserAuthenticationParameters(cryptoKey.timeout,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(cryptoKey.timeout)
        }
        builder.setUserAuthenticationRequired(true)
        val key = cryptoKey.createKeyPair(builder.build())
        return KeyPair(key.public as RSAPublicKey, key.private, cryptoKey.keyAlias)
    }

    /**
     * check biometric is supported
     */
    override fun isSupported(context: Context, attestation: Attestation): Boolean {
        return super.isSupported(context, attestation) &&
                biometricInterface.isSupported(
                    BIOMETRIC_STRONG or DEVICE_CREDENTIAL,
                    BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
    }

    final override fun type(): DeviceBindingAuthenticationType =
        DeviceBindingAuthenticationType.BIOMETRIC_ALLOW_FALLBACK


}

/**
 * Settings for all the none authentication is configured
 */
open class None : CryptoAware, DeviceAuthenticator {

    private lateinit var cryptoKey: CryptoKey

    /**
     * generate the public and private keypair
     */
    override suspend fun generateKeys(context: Context, attestation: Attestation): KeyPair {
        val builder = cryptoKey.keyBuilder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setAttestationChallenge(attestation.challenge)
        }
        val key = cryptoKey.createKeyPair(builder.build())
        return KeyPair(key.public as RSAPublicKey, key.private, cryptoKey.keyAlias)
    }

    override fun deleteKeys(context: Context) {
        cryptoKey.deleteKeys()
    }

    final override fun type(): DeviceBindingAuthenticationType =
        DeviceBindingAuthenticationType.NONE

    /**
     * return success block for None type
     */
    override suspend fun authenticate(context: Context): DeviceBindingStatus {
        cryptoKey.getPrivateKey()?.let {
            return Success(it)
        } ?: return DeviceBindingErrorStatus.ClientNotRegistered()
    }

    final override fun setKey(cryptoKey: CryptoKey) {
        this.cryptoKey = cryptoKey
    }

}