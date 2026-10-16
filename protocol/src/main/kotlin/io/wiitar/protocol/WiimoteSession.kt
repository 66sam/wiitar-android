/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.protocol

/** Everything a [WiimoteSession] reports. Consumers pick the events they care about. */
sealed interface WiimoteEvent {
    /** Every packet received, before parsing. For diagnostics. */
    class RawInput(val bytes: ByteArray) : WiimoteEvent {
        override fun toString() = "RawInput(${bytes.toHex()})"
    }

    /** Every report sent. For diagnostics. */
    class RawOutput(val bytes: ByteArray) : WiimoteEvent {
        override fun toString() = "RawOutput(${bytes.toHex()})"
    }

    data class Status(val report: InputReport.Status) : WiimoteEvent

    data class AccelCalibrationRead(val calibration: AccelCalibration) : WiimoteEvent

    /** Extension identified. Calibration ([ExtensionCalibrationRead]) follows. */
    class ExtensionConnected(val type: ExtensionType, val id: ByteArray) : WiimoteEvent {
        override fun toString() = "ExtensionConnected($type, id=${id.toHex()})"
    }

    /** Extension calibration read; the extension is now fully set up and its input is decoded. */
    data class ExtensionCalibrationRead(val type: ExtensionType, val calibration: ExtensionCalibration?) : WiimoteEvent

    data object ExtensionDisconnected : WiimoteEvent

    data class ExtensionError(val message: String) : WiimoteEvent

    /** Answer to [WiimoteSession.readMemory]. [error]: 0 = success, -1 = no answer, else the device's error code. */
    class MemoryRead(val address: Int, val eeprom: Boolean, val data: ByteArray, val error: Int) : WiimoteEvent {
        override fun toString() = "MemoryRead(0x%06x, eeprom=$eeprom, error=$error, ${data.toHex()})".format(address)
    }

    /** One continuous data report, decoded. */
    class Input(
        val mode: ReportMode,
        val buttons: CoreButtons?,
        val accel: AccelRaw?,
        val tilt: Tilt?,
        val guitar: GuitarState?,
        val extensionRaw: ByteArray?,
    ) : WiimoteEvent {
        override fun toString() = "Input($mode, $buttons, $accel, $tilt, $guitar, ext=${extensionRaw?.toHex()})"
    }

    data class Warning(val message: String) : WiimoteEvent
}

data class SessionConfig(
    /** Request accelerometer data (needed for tilt). */
    val useAccelerometer: Boolean = true,
    val tilt: TiltConfig = TiltConfig(),
    /** How many times to re-read the extension ID while the plug is still settling. */
    val extensionIdRetries: Int = 3,
    /** How many times [WiimoteSession.onCommandTimeout] resends a memory command before giving up. */
    val commandRetries: Int = 2,
)

/**
 * Protocol state machine for one Wiimote. Transport-agnostic: feed it every
 * received report through [onInputReport], and it calls [send] with the
 * reports to write (report ID first, no 0xA2 header) and [emit] with events.
 *
 * Memory reads/writes are sent one at a time. If the transport sees no answer
 * within about a second while [hasPendingCommand] is true, it should call
 * [onCommandTimeout].
 *
 * Not thread-safe: call it from one thread.
 */
class WiimoteSession(
    private val config: SessionConfig = SessionConfig(),
    private val send: (ByteArray) -> Unit,
    private val emit: (WiimoteEvent) -> Unit,
) {
    enum class ExtensionState { NONE, INITIALIZING, READY, FAILED }

    private enum class Purpose { EXT_INIT_1, EXT_INIT_2, EXT_ID, EXT_CALIBRATION, ACCEL_CALIBRATION, USER }

    private sealed class Op(val purpose: Purpose, val generation: Int) {
        abstract val report: ByteArray

        class Write(val address: Int, val data: ByteArray, purpose: Purpose, generation: Int) : Op(purpose, generation) {
            override val report get() = OutputReports.writeRegister(address, data)
        }

        class Read(val address: Int, val size: Int, val eeprom: Boolean, purpose: Purpose, generation: Int) :
            Op(purpose, generation) {
            override val report
                get() = if (eeprom) OutputReports.readEeprom(address, size) else OutputReports.readRegister(address, size)
        }
    }

    private val queue = ArrayDeque<Op>()
    private var pending: Op? = null
    private var pendingAttempts = 0

    /** Bumped whenever the extension goes away, so late answers for the old one are ignored. */
    private var extensionGeneration = 0
    private var idAttempts = 0
    private var guitarDecoder = GuitarDecoder()

    var extensionState = ExtensionState.NONE
        private set
    var extensionType: ExtensionType? = null
        private set
    var extensionCalibration: ExtensionCalibration? = null
        private set
    var accelCalibration: AccelCalibration = AccelCalibration.DEFAULT
        private set
    var reportMode: ReportMode? = null
        private set
    var lastStatus: InputReport.Status? = null
        private set

    val hasPendingCommand: Boolean get() = pending != null
    val hasTouchStrip: Boolean get() = guitarDecoder.hasTouchStrip

    /** Call once the channel is open. */
    fun start(playerSlot: Int) {
        setPlayerSlot(playerSlot)
        enqueue(Op.Read(AccelCalibration.EEPROM_ADDRESS, AccelCalibration.SIZE, true, Purpose.ACCEL_CALIBRATION, extensionGeneration))
        requestStatus()
    }

    fun setPlayerSlot(slot: Int) = sendReport(OutputReports.setLeds(OutputReports.playerLedMask(slot)))

    fun requestStatus() = sendReport(OutputReports.requestStatus())

    /** Raw memory read for the diagnostics screen (up to 16 bytes; answer arrives as [WiimoteEvent.MemoryRead]). */
    fun readMemory(address: Int, size: Int, eeprom: Boolean = false) {
        require(size in 1..16) { "diagnostic reads are limited to 16 bytes" }
        enqueue(Op.Read(address, size, eeprom, Purpose.USER, extensionGeneration))
    }

    /** Re-run extension setup, e.g. after [ExtensionState.FAILED]. */
    fun retryExtension() {
        dropExtension(notify = false)
        requestStatus()
    }

    fun onCommandTimeout() {
        val op = pending ?: return
        if (pendingAttempts <= config.commandRetries) {
            pendingAttempts++
            emit(WiimoteEvent.Warning("no answer to ${op.purpose}, resending (attempt $pendingAttempts)"))
            sendReport(op.report)
        } else {
            pending = null
            fail(op, "no answer after ${pendingAttempts} attempts")
            sendNext()
        }
    }

    fun onInputReport(packet: ByteArray) {
        emit(WiimoteEvent.RawInput(packet))
        when (val report = InputReport.parse(packet)) {
            null -> emit(WiimoteEvent.Warning("truncated or empty report: ${packet.toHex()}"))
            is InputReport.Status -> onStatus(report)
            is InputReport.ReadData -> onReadData(report)
            is InputReport.Ack -> onAck(report)
            is InputReport.Data -> onData(report)
            is InputReport.Unknown -> emit(WiimoteEvent.Warning("unknown report $report"))
        }
    }

    private fun onStatus(report: InputReport.Status) {
        lastStatus = report
        emit(WiimoteEvent.Status(report))
        if (report.extensionConnected) {
            if (extensionState == ExtensionState.NONE) beginExtensionSetup()
        } else if (extensionState != ExtensionState.NONE) {
            dropExtension(notify = true)
        }
        // The Wiimote stops sending data reports after a status report until the mode is set again.
        applyReportMode()
    }

    private fun beginExtensionSetup() {
        extensionState = ExtensionState.INITIALIZING
        idAttempts = 0
        val g = extensionGeneration
        enqueue(Op.Write(ExtensionRegisters.INIT_1, bytesOf(0x55), Purpose.EXT_INIT_1, g))
        enqueue(Op.Write(ExtensionRegisters.INIT_2, bytesOf(0x00), Purpose.EXT_INIT_2, g))
        enqueue(Op.Read(ExtensionRegisters.ID, ExtensionRegisters.ID_SIZE, false, Purpose.EXT_ID, g))
    }

    private fun dropExtension(notify: Boolean) {
        val wasPresent = extensionState != ExtensionState.NONE
        extensionGeneration++
        queue.removeAll { it.purpose in EXTENSION_PURPOSES }
        extensionState = ExtensionState.NONE
        extensionType = null
        extensionCalibration = null
        guitarDecoder = GuitarDecoder()
        if (notify && wasPresent) emit(WiimoteEvent.ExtensionDisconnected)
    }

    private fun applyReportMode() {
        val withExtension = extensionState == ExtensionState.READY
        val mode = when {
            withExtension && config.useAccelerometer -> ReportMode.CORE_ACCEL_EXT16
            withExtension -> ReportMode.CORE_EXT8
            config.useAccelerometer -> ReportMode.CORE_ACCEL
            else -> ReportMode.CORE
        }
        reportMode = mode
        sendReport(OutputReports.setReportMode(mode))
    }

    private fun onReadData(report: InputReport.ReadData) {
        val op = pending as? Op.Read
        if (op == null || report.addressLow != (op.address and 0xFFFF)) {
            emit(WiimoteEvent.Warning("unexpected read data $report"))
            return
        }
        complete()
        if (report.error != 0) fail(op, "read error ${report.error}", report.error) else succeedRead(op, report.data)
        sendNext()
    }

    private fun onAck(report: InputReport.Ack) {
        val op = pending as? Op.Write
        if (report.ackedReportId != OutputReports.ID_WRITE_MEMORY || op == null) {
            if (report.error != 0) {
                emit(WiimoteEvent.Warning("report 0x%02x rejected, error ${report.error}".format(report.ackedReportId)))
            }
            return
        }
        complete()
        if (report.error != 0) fail(op, "write error ${report.error}")
        sendNext()
    }

    private fun onData(report: InputReport.Data) {
        val tilt = report.accel?.let { TiltCalculator.compute(it, accelCalibration, config.tilt) }
        val ext = report.extension
        val guitar = if (
            extensionState == ExtensionState.READY &&
            extensionType?.family == ExtensionFamily.GUITAR &&
            ext != null && ext.size >= 6
        ) guitarDecoder.decode(ext) else null
        emit(WiimoteEvent.Input(report.mode, report.buttons, report.accel, tilt, guitar, ext))
    }

    private fun succeedRead(op: Op.Read, data: ByteArray) {
        if (op.purpose in EXTENSION_PURPOSES && op.generation != extensionGeneration) return
        when (op.purpose) {
            Purpose.ACCEL_CALIBRATION -> {
                val cal = AccelCalibration.parse(data)
                if (cal.checksumValid) {
                    accelCalibration = cal
                } else {
                    emit(WiimoteEvent.Warning("accelerometer calibration checksum mismatch, using defaults"))
                }
                emit(WiimoteEvent.AccelCalibrationRead(cal))
            }
            Purpose.EXT_ID -> onExtensionId(op, data)
            Purpose.EXT_CALIBRATION -> {
                extensionCalibration = ExtensionCalibration(data)
                finishExtensionSetup()
            }
            Purpose.USER -> emit(WiimoteEvent.MemoryRead(op.address, op.eeprom, data, 0))
            Purpose.EXT_INIT_1, Purpose.EXT_INIT_2 -> Unit
        }
    }

    private fun onExtensionId(op: Op.Read, id: ByteArray) {
        if (ExtensionType.isNotReady(id)) {
            if (++idAttempts <= config.extensionIdRetries) {
                emit(WiimoteEvent.Warning("extension ID ${id.toHex()} not ready, re-reading"))
                queue.addFirst(Op.Read(op.address, op.size, false, Purpose.EXT_ID, op.generation))
            } else {
                extensionState = ExtensionState.FAILED
                emit(WiimoteEvent.ExtensionError("extension ID stayed ${id.toHex()}; is the plug fully inserted?"))
            }
            return
        }
        val type = ExtensionType.fromId(id)
        extensionType = type
        emit(WiimoteEvent.ExtensionConnected(type, id))
        queue.addFirst(
            Op.Read(ExtensionRegisters.CALIBRATION, ExtensionRegisters.CALIBRATION_SIZE, false, Purpose.EXT_CALIBRATION, op.generation),
        )
    }

    private fun finishExtensionSetup() {
        extensionState = ExtensionState.READY
        guitarDecoder = GuitarDecoder()
        emit(WiimoteEvent.ExtensionCalibrationRead(extensionType ?: ExtensionType.UNKNOWN, extensionCalibration))
        applyReportMode()
    }

    /** @param code device error code, or -1 when the Wiimote never answered. */
    private fun fail(op: Op, reason: String, code: Int = -1) {
        if (op.purpose in EXTENSION_PURPOSES && op.generation != extensionGeneration) return
        when (op.purpose) {
            Purpose.ACCEL_CALIBRATION -> emit(WiimoteEvent.Warning("accelerometer calibration: $reason, using defaults"))
            Purpose.EXT_CALIBRATION -> {
                emit(WiimoteEvent.Warning("extension calibration: $reason"))
                finishExtensionSetup()
            }
            Purpose.EXT_INIT_1, Purpose.EXT_INIT_2, Purpose.EXT_ID -> {
                queue.removeAll { it.purpose in EXTENSION_PURPOSES }
                extensionState = ExtensionState.FAILED
                emit(WiimoteEvent.ExtensionError("${op.purpose}: $reason"))
            }
            Purpose.USER -> {
                val read = op as Op.Read
                emit(WiimoteEvent.MemoryRead(read.address, read.eeprom, ByteArray(0), code))
            }
        }
    }

    private fun enqueue(op: Op) {
        queue.addLast(op)
        if (pending == null) sendNext()
    }

    private fun complete() {
        pending = null
        pendingAttempts = 0
    }

    private fun sendNext() {
        if (pending != null) return
        val op = queue.removeFirstOrNull() ?: return
        pending = op
        pendingAttempts = 1
        sendReport(op.report)
    }

    private fun sendReport(report: ByteArray) {
        emit(WiimoteEvent.RawOutput(report))
        send(report)
    }

    private companion object {
        val EXTENSION_PURPOSES = setOf(Purpose.EXT_INIT_1, Purpose.EXT_INIT_2, Purpose.EXT_ID, Purpose.EXT_CALIBRATION)
    }
}
