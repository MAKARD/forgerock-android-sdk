/*
 * Copyright (c) 2022 ForgeRock. All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */
package org.forgerock.android.auth.devicebind

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CountDownLatch

class DeviceBindAuthenticationTests {

    private val mockBiometricInterface = mock<BiometricHandler>()
    private val keyAware = mock<KeyAware>()

    @Test
    fun testSigningData() {
       val testObject = BiometricAndDeviceCredential(mockBiometricInterface, keyAware)
        val kpg: KeyPairGenerator = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keys = kpg.generateKeyPair()
        val keyPair = KeyPair(keys.public as RSAPublicKey, keys.private, "jeyAlias")
        val output = testObject.sign(keyPair, "1234", "3123123123", "77888")
        assertNotNull(output)
    }

    @Test
    fun testGenerateKeys() {
        val keyBuilder = mock<KeyGenParameterSpec.Builder>()
        whenever(keyAware.keyBuilder()).thenReturn(keyBuilder)
        whenever(keyAware.timeout).thenReturn(30)

        val testObject = BiometricAndDeviceCredential(mockBiometricInterface, keyAware, false)
        testObject.generateKeys()

        verify(keyBuilder).setUserAuthenticationValidityDurationSeconds(30)
        verify(keyBuilder).setUserAuthenticationRequired(true)
        verify(keyAware).createKeyPair(keyBuilder)

        val testObjectBiometric = BiometricOnly(mockBiometricInterface, keyAware, false)
        testObjectBiometric.generateKeys()

        verify(keyBuilder, times(2)).setUserAuthenticationValidityDurationSeconds(30)
        verify(keyBuilder, times(2)).setUserAuthenticationRequired(true)
        verify(keyAware, times(2)).createKeyPair(keyBuilder)
    }

    @Test
    fun testGenerateKeysForAPi30ForDeviceCredential() {
        val keyBuilder = mock<KeyGenParameterSpec.Builder>()
        whenever(keyAware.keyBuilder()).thenReturn(keyBuilder)
        whenever(keyAware.timeout).thenReturn(30)

        val testObject = BiometricAndDeviceCredential(mockBiometricInterface, keyAware, true)
        testObject.generateKeys()

        verify(keyBuilder).setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        verify(keyBuilder).setUserAuthenticationRequired(true)
        verify(keyAware).createKeyPair(keyBuilder)
    }

    @Test
    fun testGenerateKeysForAPi30ForBiometricOnly() {
        val keyBuilder = mock<KeyGenParameterSpec.Builder>()
        whenever(keyAware.keyBuilder()).thenReturn(keyBuilder)
        whenever(keyAware.timeout).thenReturn(30)

        val testObject = BiometricOnly(mockBiometricInterface, keyAware, true)
        testObject.generateKeys()

        verify(keyBuilder).setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG)
        verify(keyBuilder).setUserAuthenticationRequired(true)
        verify(keyAware).createKeyPair(keyBuilder)
    }

    @Test
    fun testGenerateKeysNone() {
        val keyBuilder = mock<KeyGenParameterSpec.Builder>()
        whenever(keyAware.keyBuilder()).thenReturn(keyBuilder)

        val testObject = None(keyAware)
        testObject.generateKeys()

        verify(keyAware).createKeyPair(keyBuilder)
    }

    @Test
    fun testIsNotSupported() {
        whenever(mockBiometricInterface.isSupported()).thenReturn(false)
        val testObject = BiometricAndDeviceCredential(mockBiometricInterface, keyAware)
        assertFalse(testObject.isSupported())
    }

    @Test
    fun testSupportedDeviceCred() {
        whenever(mockBiometricInterface.isSupported(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, BIOMETRIC_WEAK or DEVICE_CREDENTIAL)).thenReturn(true)
        val testObject = BiometricAndDeviceCredential(mockBiometricInterface, keyAware)
        assertTrue(testObject.isSupported())
    }

    @Test
    fun testSupportedBiometricOnly() {
        whenever(mockBiometricInterface.isSupported()).thenReturn(true)
        val testObject = BiometricOnly(mockBiometricInterface, keyAware)
        assertTrue(testObject.isSupported())
    }

    @Test
    fun testSupportedNone() {
        val testObject = None(keyAware)
        assertTrue(testObject.isSupported())
    }

    @Test
    fun testAuthenticateForBiometric() {

        val result: (DeviceBindingStatus) -> (Unit) = {}
        val testObject = BiometricOnly(mockBiometricInterface, keyAware)
        testObject.authenticate(60, result)
        verify(mockBiometricInterface).authenticate(60, result)
    }

    @Test
    fun testAuthenticateForBiometricAndCredential() {
        val result: (DeviceBindingStatus) -> (Unit) = {}
        val testObject = BiometricAndDeviceCredential(mockBiometricInterface, keyAware)
        testObject.authenticate(60, result)
        verify(mockBiometricInterface).authenticate(60, result)
    }

    @Test
    fun testNoneAuthenticate() {
        val countDownLatch = CountDownLatch(1)
        val result: (DeviceBindingStatus) -> (Unit) = {
            assertEquals(it, Success)
            countDownLatch.countDown()
        }
        val testObject = None(keyAware)
        testObject.authenticate(60, result)
        countDownLatch.await()
    }

}