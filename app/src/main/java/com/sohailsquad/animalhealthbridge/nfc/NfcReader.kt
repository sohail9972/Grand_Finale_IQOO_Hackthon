package com.sohailsquad.animalhealthbridge.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NfcV
import android.os.Bundle
import android.util.Log

/**
 * Reads an animal's identity from an NFC tag.
 *
 * Two tag families are accepted:
 *
 *   - **NTAG213/215/216** (ISO 14443-A) - the cheap sticker tags used for the
 *     demo. If one has been written with NXP TagWriter we read the NDEF text
 *     value; a blank one falls back to its factory UID.
 *   - **ISO 15693 / NFC-V** - the real livestock ear tags. These carry no NDEF
 *     payload, so we always use the factory UID.
 *
 * Uses [NfcAdapter.enableReaderMode] rather than foreground dispatch:
 *
 *   1. It lets us ask for exactly the technologies we accept (NFC-A and
 *      NFC-V), so the platform filters for us instead of handing over every
 *      tag type.
 *   2. It works while the screen is in the foreground without the app having to
 *      win an intent-dispatch race against other apps.
 *   3. It keeps the platform from showing its own "tag found" UI, so the owner
 *      sees our screen and nothing else.
 *
 * Everything is local: no network, no cloud lookup. The string we produce is the
 * animal's identity for the rest of the app.
 *
 * @param activity the host activity. Reader mode is bound to a foreground
 *   activity, so this must be the real Activity - an application context will
 *   throw.
 * @param onTagRead invoked on the main thread with the formatted identity as
 *   soon as a tag is read. The reader stops itself afterwards, so a single tap
 *   produces exactly one callback rather than a stream of them.
 */
class NfcReader(
    private val activity: Activity,
    private val onTagRead: (String) -> Unit,
) {

    /**
     * Cached adapter.
     *
     * `null` means the device has no NFC hardware at all - a real possibility
     * for the low-end phones this app targets, which is why every call site
     * must consult [isAvailable] before [start].
     */
    private val adapter: NfcAdapter? by lazy {
        try {
            (activity.getSystemService(Context.NFC_SERVICE) as? NfcManager)?.defaultAdapter
        } catch (t: Throwable) {
            // Defensive: a vendor ROM can throw here instead of returning null.
            // Treat any failure as "no NFC" rather than crashing the screen.
            Log.w(TAG, "NFC adapter lookup failed; treating device as NFC-less", t)
            null
        }
    }

    /** True once reader mode has been enabled and not yet disabled. */
    private var readerModeActive = false

    /**
     * True when the device has NFC hardware.
     *
     * Says nothing about whether NFC is switched on - see [isEnabled].
     */
    fun isAvailable(): Boolean = adapter != null

    /**
     * True when NFC hardware exists and the user has NFC switched on.
     *
     * Android will not let an app enable NFC itself; when this returns false the
     * UI must send the owner to Settings.
     */
    fun isEnabled(): Boolean = adapter?.isEnabled == true

    /**
     * Begins listening for a tag.
     *
     * Safe to call repeatedly and safe to call on a device without NFC: it logs
     * and returns instead of throwing, because a farmer tapping "Scan" on a
     * phone with no reader must not crash the app.
     */
    fun start() {
        val nfc = adapter
        if (nfc == null) {
            Log.w(TAG, "start() ignored: no NFC hardware on this device")
            return
        }
        if (!nfc.isEnabled) {
            Log.w(TAG, "start() ignored: NFC is switched off")
            return
        }
        if (readerModeActive) {
            // Already listening. Re-enabling would be harmless but would reset
            // the presence-check timer, so skip it.
            Log.d(TAG, "start() ignored: reader mode already active")
            return
        }

        try {
            // FLAG_READER_NFC_A - accept ISO 14443-A, i.e. NTAG21x sticker tags.
            // FLAG_READER_NFC_V - accept ISO 15693, i.e. livestock ear tags.
            // FLAG_READER_NO_PLATFORM_SOUNDS - we give our own feedback, so
            //   suppress the system tone.
            //
            // FLAG_READER_SKIP_NDEF_CHECK is deliberately NOT set. Skipping the
            // NDEF probe would leave the platform's NDEF cache unpopulated, and
            // Ndef.get(tag) would then return null for a written tag - which is
            // exactly the case we need to read.
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

            val extras = Bundle().apply {
                // Ask the platform to verify the tag is still present every
                // 250 ms. Without this a tag lifted away mid-read can leave the
                // callback hanging.
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_MS)
            }

            nfc.enableReaderMode(activity, readerCallback, flags, extras)
            readerModeActive = true
            Log.i(TAG, "Reader mode started (NFC-A + NFC-V)")
        } catch (t: Throwable) {
            // enableReaderMode throws IllegalStateException if the activity is
            // not resumed. Recover by staying stopped rather than crashing.
            readerModeActive = false
            Log.e(TAG, "Failed to enable reader mode", t)
        }
    }

    /**
     * Stops listening.
     *
     * Safe to call at any time, including when never started. Always call this
     * from onPause: leaving reader mode on drains the battery and keeps the
     * phone reacting to tags while the owner is elsewhere in the app.
     */
    fun stop() {
        if (!readerModeActive) return
        try {
            adapter?.disableReaderMode(activity)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to disable reader mode", t)
        } finally {
            // Clear the flag even if the platform call failed, otherwise the
            // reader would refuse to start again for the rest of the session.
            readerModeActive = false
            Log.i(TAG, "Reader mode stopped")
        }
    }

    /**
     * Platform callback. Runs on a binder thread, so it does no UI work and no
     * database work - it resolves the identity and hands it to the caller.
     */
    private val readerCallback = NfcAdapter.ReaderCallback { tag: Tag ->
        try {
            val uid = readUid(tag)
            if (uid == null) {
                Log.w(TAG, "Tag discovered but its UID was empty; ignoring")
                return@ReaderCallback
            }

            Log.i(TAG, "Tag detected uid=$uid tech=${tag.techList.joinToString()}")

            // Stop first, then deliver. Stopping before the callback means a
            // tag left resting on the phone cannot fire a second time and
            // create a duplicate animal.
            stop()

            // Hop to the main thread: the callback arrives on a binder thread,
            // and both Compose state writes and navigation must happen on main.
            activity.runOnUiThread {
                try {
                    onTagRead(uid)
                } catch (t: Throwable) {
                    // Never let a consumer's bug take down the NFC callback.
                    Log.e(TAG, "onTagRead handler threw", t)
                }
            }
        } catch (t: Throwable) {
            // A tag can be removed mid-read; that is normal, not an error worth
            // propagating.
            Log.w(TAG, "Failed to read tag (tag may have been removed)", t)
            stop()
        }
    }

    /**
     * Resolves a tag to the animal's identity, preferring a written value.
     *
     * Two paths, in order:
     *
     *   1. **NDEF text** - if the tag was written with NXP TagWriter (the usual
     *      way a demo NTAG21x is prepared) the text record is the human-meaningful
     *      identity, e.g. "AHB-2024-000001".
     *   2. **Factory UID** - a blank NTAG21x, and every ISO 15693 ear tag, has
     *      no NDEF payload, so we fall back to the hardware UID.
     *
     * @return either the NDEF text value or the UID as colon-separated
     *   upper-case hex (e.g. "E0:04:01:5A:..."); null when the tag yields
     *   neither. Callers must treat the result as an opaque string: it is not
     *   guaranteed to be hex.
     */
    private fun readUid(tag: Tag): String? {
        // Path 1: NDEF text written to the tag.
        readNdefText(tag)?.let { return it }

        // Path 2: factory UID fallback.
        val raw = tag.id ?: return null
        if (raw.isEmpty()) return null

        // ISO 15693 transponders report their UID least-significant-byte first,
        // whereas the number printed on the ear tag is most-significant-byte
        // first. Reversing for NfcV makes what we store match what the farmer
        // can read off the tag, so the two can be compared by eye.
        // Only reverse for genuine ISO 15693. NTAG21x UIDs are MSB-first already.
        val isIso15693 = tag.techList.any { it == NfcV::class.java.name }
        val ordered = if (isIso15693) raw.reversedArray() else raw

        return ordered.joinToString(separator = ":") { byte ->
            "%02X".format(byte)
        }
    }

    /**
     * Reads a well-known NDEF text record from the tag, if there is one.
     *
     * The text record layout per the NFC Forum spec is: a status byte, then the
     * language code, then the text. Bit 7 of the status byte selects UTF-16
     * over UTF-8, and the low 6 bits give the language-code length.
     *
     * @return the decoded text, or null when the tag is blank, carries no text
     *   record, or cannot be read.
     */
    private fun readNdefText(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val message = ndef.ndefMessage ?: return null
            for (record in message.records) {
                val payload = record.payload ?: continue
                if (record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                    record.type.contentEquals(NdefRecord.RTD_TEXT)
                ) {
                    // Guard the status byte: a malformed record could be empty,
                    // and an out-of-range language length would make the slice
                    // below throw.
                    if (payload.isEmpty()) continue
                    val status = payload[0].toInt()
                    val langLen = status and 0x3F
                    if (1 + langLen > payload.size) continue
                    val textBytes = payload.copyOfRange(1 + langLen, payload.size)
                    val charset = if ((status and 0x80) == 0) Charsets.UTF_8
                    else Charsets.UTF_16
                    return String(textBytes, charset).trim().ifEmpty { null }
                }
            }
            null
        } catch (t: Throwable) {
            // Blank tags throw here; expected, not an error.
            Log.d(TAG, "NDEF read failed (likely blank tag): ${t.message}")
            null
        } finally {
            try { ndef.close() } catch (_: Throwable) {}
        }
    }

    private companion object {
        /** Logcat tag; filter with: adb logcat -s AhbNfc */
        const val TAG = "AhbNfc"

        /** Presence re-check interval handed to the platform, in milliseconds. */
        const val PRESENCE_CHECK_MS = 250
    }
}