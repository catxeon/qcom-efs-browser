package dev.qcom.efs

import org.junit.Assert.assertEquals
import org.junit.Test

class Sha256Test {

    @Test
    fun `empty input matches the known digest`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun `abc matches the known digest`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `a NUL byte hashes as data, not as an empty string`() {
        assertEquals(
            "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d",
            sha256Hex(byteArrayOf(0)),
        )
    }

    @Test
    fun `every byte value hashes as unsigned data`() {
        // Bytes above 0x7F would sign-extend if the conversion missed the mask.
        assertEquals(
            "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880",
            sha256Hex(ByteArray(256) { it.toByte() }),
        )
    }
}
