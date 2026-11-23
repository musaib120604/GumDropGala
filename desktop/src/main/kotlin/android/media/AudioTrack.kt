package android.media

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

class AudioAttributes private constructor() {
    class Builder {
        fun setUsage(u: Int) = this
        fun setContentType(c: Int) = this
        fun build() = AudioAttributes()
    }
    companion object { const val USAGE_GAME = 14; const val CONTENT_TYPE_SONIFICATION = 4 }
}

class AudioFormat private constructor(internal val rate: Int, internal val channels: Int) {
    class Builder {
        private var rate = 22050; private var ch = 1
        fun setSampleRate(r: Int) = apply { rate = r }
        fun setEncoding(e: Int) = this
        fun setChannelMask(m: Int) = apply { ch = if (m == CHANNEL_OUT_STEREO) 2 else 1 }
        fun build() = AudioFormat(rate, ch)
    }
    companion object { const val ENCODING_PCM_16BIT = 2; const val CHANNEL_OUT_MONO = 4; const val CHANNEL_OUT_STEREO = 12 }
}

/** Streams 16-bit PCM to the default Windows/Java Sound output. */
class AudioTrack private constructor(private val line: SourceDataLine, private val channels: Int) {
    class Builder {
        private var fmt: AudioFormat = AudioFormat.Builder().build()
        private var buf = 4096
        fun setAudioAttributes(a: AudioAttributes) = this
        fun setAudioFormat(f: AudioFormat) = apply { fmt = f }
        fun setBufferSizeInBytes(b: Int) = apply { buf = b }
        fun setTransferMode(m: Int) = this
        fun build(): AudioTrack {
            val jf = javax.sound.sampled.AudioFormat(fmt.rate.toFloat(), 16, fmt.channels, true, false)
            val line = AudioSystem.getSourceDataLine(jf)
            line.open(jf, buf.coerceAtLeast(4096))
            return AudioTrack(line, fmt.channels)
        }
    }

    private var bytes = ByteArray(0)
    fun play() { line.start() }
    fun pause() { line.stop() }
    fun flush() { line.flush() }
    fun stop() { try { line.drain() } catch (e: Exception) { /* ignore */ }; line.stop() }
    fun release() { line.close() }
    fun write(data: ShortArray, off: Int, len: Int): Int {
        if (bytes.size < len * 2) bytes = ByteArray(len * 2)
        for (i in 0 until len) { val s = data[off + i].toInt(); bytes[i * 2] = s.toByte(); bytes[i * 2 + 1] = (s shr 8).toByte() }
        line.write(bytes, 0, len * 2)
        return len
    }

    companion object {
        const val MODE_STREAM = 1
        @JvmStatic fun getMinBufferSize(rate: Int, channelMask: Int, encoding: Int): Int = rate / 10
    }
}
