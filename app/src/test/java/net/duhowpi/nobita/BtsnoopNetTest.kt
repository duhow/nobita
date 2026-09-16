package net.duhowpi.nobita

import net.duhowpi.nobita.btsnoop.BtsnoopFileRecovery
import net.duhowpi.nobita.btsnoop.BtsnoopFormat
import net.duhowpi.nobita.btsnoop.BtsnoopNetClient
import net.duhowpi.nobita.btsnoop.BtsnoopReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BtsnoopNetTest {
    @Test fun rejectsAnInvalidNetworkHeaderBeforeCreatingCaptureData() {
        val output = Files.createTempFile("nobita-invalid", ".btsnoop").toFile()
        val server = ServerSocket(8872)
        val serverThread = Thread {
            server.use {
                it.accept().use { client ->
                    val invalid = header().clone().also { bytes -> bytes[0] = 'x'.code.toByte() }
                    client.getOutputStream().write(invalid)
                }
            }
        }
        serverThread.start()

        try {
            BtsnoopNetClient(output, freeSpaceReserveBytes = 0).start()
            fail("Expected invalid BTSnoop header")
        } catch (_: IllegalStateException) {
            assertFalse(output.exists())
        } finally {
            serverThread.join(1_000)
            output.delete()
        }
    }

    @Test fun copiesAChunkedLiveBtsnoopStream() {
        val output = Files.createTempFile("nobita-net", ".btsnoop").toFile()
        val ready = CountDownLatch(1)
        val server = ServerSocket(8872)
        val serverThread = Thread {
            server.use {
                ready.countDown()
                it.accept().use { client ->
                    client.getOutputStream().use { stream ->
                        val record = record(flags = 1, packet = byteArrayOf(0x04, 0x0e, 0x00))
                        val bytes = header() + record
                        bytes.forEach { byte -> stream.write(byte.toInt()); stream.flush() }
                        Thread.sleep(100)
                    }
                }
            }
        }
        serverThread.start()
        assertTrue(ready.await(1, TimeUnit.SECONDS))

        val client = BtsnoopNetClient(output, freeSpaceReserveBytes = 0)
        client.start()
        serverThread.join(2_000)
        client.stop()

        val records = BtsnoopReader.read(output.inputStream()).toList()
        assertEquals(1, records.size)
        assertEquals(true, records.single().controllerToHost)
        assertEquals(24L + 3L, BtsnoopFileRecovery.inspect(output).completeLength - BtsnoopFormat.HEADER_LENGTH)
        output.delete()
    }

    @Test fun truncatesOnlyAnIncompleteFinalRecord() {
        val output = Files.createTempFile("nobita-recovery", ".btsnoop").toFile()
        output.writeBytes(header() + record(flags = 0, packet = byteArrayOf(0x01, 0x01, 0x00)) + record(flags = 1, packet = byteArrayOf(0x04)).copyOf(BtsnoopFormat.RECORD_HEADER_LENGTH.toInt()))

        val recovery = BtsnoopFileRecovery.truncateToCompleteRecords(output)

        assertEquals(1L, recovery.records)
        assertTrue(recovery.truncatedTail)
        assertEquals(BtsnoopFormat.HEADER_LENGTH + BtsnoopFormat.RECORD_HEADER_LENGTH + 3, output.length())
        output.delete()
    }

    @Test fun rejectsOversizedRecordWithoutReadingItsPayload() {
        val output = Files.createTempFile("nobita-malformed", ".btsnoop").toFile()
        output.writeBytes(ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.write(header())
                data.writeInt(BtsnoopFormat.MAX_INCLUDED_LENGTH + 1)
                data.writeInt(BtsnoopFormat.MAX_INCLUDED_LENGTH + 1)
                data.writeInt(0)
                data.writeInt(0)
                data.writeLong(0)
            }
        }.toByteArray())

        val recovery = BtsnoopFileRecovery.inspect(output)

        assertTrue(recovery.malformedRecord)
        assertEquals(0L, recovery.records)
        output.delete()
    }

    private fun header() = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(BtsnoopFormat.MAGIC)
            output.writeInt(BtsnoopFormat.VERSION)
            output.writeInt(BtsnoopFormat.ANDROID_DATALINK_H4)
        }
    }.toByteArray()

    private fun record(flags: Int, packet: ByteArray) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(packet.size)
            output.writeInt(packet.size)
            output.writeInt(flags)
            output.writeInt(0)
            output.writeLong(0x00dcddb30f2f8000L + 1_000_000)
            output.write(packet)
        }
    }.toByteArray()
}
