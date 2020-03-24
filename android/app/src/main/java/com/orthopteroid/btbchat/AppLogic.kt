package com.orthopteroid.btbchat

import android.bluetooth.le.AdvertiseData
import android.os.Handler
import android.util.Log
import java.time.LocalDateTime
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingDeque
import kotlin.collections.ArrayList
import kotlin.concurrent.thread
import kotlin.experimental.or
import kotlin.math.roundToInt
import kotlin.random.nextUInt

@kotlin.ExperimentalStdlibApi
@kotlin.ExperimentalUnsignedTypes
class AppLogic(activity: MainActivity, uiHandler: Handler) {
    protected val TAG = "AppLogic"

    public var shutdown = false
    public var debugmode = true
    public var meshmode = true
    public var privcode: Int = 0 // privstr.crc8();
    public var mfgcode: Short = 0x1122;

    // blocking deques that receive data from the UI or BT threads
    public var mLocalText = LinkedBlockingDeque<String>()
    public var mNetworkBytes = LinkedBlockingDeque<ByteArray>()

    private var minute: Int = 0xFF
    private var srcpackets = ArrayDeque<Txablepacket>()
    private var meshpackets = ArrayList<Pripacket>()
    private var pripacket_total: UInt = 0u

    private val TO_200MS = 200
    private val TO_1SEC = 1000
    private val TO_2SEC = 2000
    private val TO_3SEC = 3000

    // ctor args, set in class' init method
    private val mActivity: MainActivity
    private val mUIHandler: Handler

    ////////////
    // https://kotlinlang.org/docs/reference/extensions.html
    // https://stackoverflow.com/a/9855338

    fun ByteArray.extractShort(msOff: Int, lsOff: Int): Short {
        return (this[msOff] * 256).toShort() or (this[lsOff]).toUByte().toShort()
    }

    fun String.crc8() = crc8(this.toByteArray(Charsets.UTF_8), this.length);

    fun ByteArray.ToHexString(): String? {
        val HEX_ARRAY = "0123456789ABCDEF".toCharArray()
        val hexChars = CharArray(this.size * 2)
        for (j in this.indices) {
            val v: Int = this[j].toInt() and 0xFF
            hexChars[j * 2] = HEX_ARRAY[v ushr 4]
            hexChars[j * 2 + 1] = HEX_ARRAY[v and 0x0F]
        }
        return String(hexChars)
    }

    /////////
    //                   09                                      29
    // 02011A1BFF2211BEAC000C68656C6C6F00000000000000000000000000CE00
    //                   ||                                      ||
    //                   ADV BODY                                RSSI
    fun extractHeader(adv: ByteArray) : Short { return adv.extractShort(3, 4) }
    fun extractMFGCode(adv: ByteArray) : Short { return adv.extractShort(6, 5) }
    fun extractBEAC(adv: ByteArray) : Short { return adv.extractShort(7, 8) }
    fun extractRSSI(adv: ByteArray) : Int { return adv[29].toInt() and 0xFF /* 0xFF designifies */ }

    /////////

    // The 1-Wire CRC scheme is described in Maxim Application Note 27
    // https://gist.github.com/brimston3/83cdeda8f7d2cf55717b83f0d32f9b5e
    private val crc8_table: IntArray = intArrayOf( // signed byte workaround
        0x00,0x5E,0xBC,0xE2,0x61,0x3F,0xDD,0x83,0xC2,0x9C,0x7E,0x20,0xA3,0xFD,0x1F,0x41,
        0x9D,0xC3,0x21,0x7F,0xFC,0xA2,0x40,0x1E,0x5F,0x01,0xE3,0xBD,0x3E,0x60,0x82,0xDC,
        0x23,0x7D,0x9F,0xC1,0x42,0x1C,0xFE,0xA0,0xE1,0xBF,0x5D,0x03,0x80,0xDE,0x3C,0x62,
        0xBE,0xE0,0x02,0x5C,0xDF,0x81,0x63,0x3D,0x7C,0x22,0xC0,0x9E,0x1D,0x43,0xA1,0xFF,
        0x46,0x18,0xFA,0xA4,0x27,0x79,0x9B,0xC5,0x84,0xDA,0x38,0x66,0xE5,0xBB,0x59,0x07,
        0xDB,0x85,0x67,0x39,0xBA,0xE4,0x06,0x58,0x19,0x47,0xA5,0xFB,0x78,0x26,0xC4,0x9A,
        0x65,0x3B,0xD9,0x87,0x04,0x5A,0xB8,0xE6,0xA7,0xF9,0x1B,0x45,0xC6,0x98,0x7A,0x24,
        0xF8,0xA6,0x44,0x1A,0x99,0xC7,0x25,0x7B,0x3A,0x64,0x86,0xD8,0x5B,0x05,0xE7,0xB9,
        0x8C,0xD2,0x30,0x6E,0xED,0xB3,0x51,0x0F,0x4E,0x10,0xF2,0xAC,0x2F,0x71,0x93,0xCD,
        0x11,0x4F,0xAD,0xF3,0x70,0x2E,0xCC,0x92,0xD3,0x8D,0x6F,0x31,0xB2,0xEC,0x0E,0x50,
        0xAF,0xF1,0x13,0x4D,0xCE,0x90,0x72,0x2C,0x6D,0x33,0xD1,0x8F,0x0C,0x52,0xB0,0xEE,
        0x32,0x6C,0x8E,0xD0,0x53,0x0D,0xEF,0xB1,0xF0,0xAE,0x4C,0x12,0x91,0xCF,0x2D,0x73,
        0xCA,0x94,0x76,0x28,0xAB,0xF5,0x17,0x49,0x08,0x56,0xB4,0xEA,0x69,0x37,0xD5,0x8B,
        0x57,0x09,0xEB,0xB5,0x36,0x68,0x8A,0xD4,0x95,0xCB,0x29,0x77,0xF4,0xAA,0x48,0x16,
        0xE9,0xB7,0x55,0x0B,0x88,0xD6,0x34,0x6A,0x2B,0x75,0x97,0xC9,0x4A,0x14,0xF6,0xA8,
        0x74,0x2A,0xC8,0x96,0x15,0x4B,0xA9,0xF7,0xB6,0xE8,0x0A,0x54,0xD7,0x89,0x6B,0x35
    )

    fun crc8(s: ByteArray, len: Int): Int
    {
        var crc: Int = 0 // signed byte workaround
        for(i in 0..len) {
            val b: Int = s.get(i).toInt() and 0xFF // 0xFF designifies
            crc = crc8_table.get(crc xor b)
        }
        return crc
    }

    //////////

    // msoffice 5.1 32-Bit CRC Algorithm for COMPRESSED_BLOCK_HEADER
    // https://docs.microsoft.com/en-us/openspecs/office_protocols/ms-abs/06966aa2-70da-4bf9-8448-3355f277cd77?redirectedfrom=MSDN
    // might need this, not sure yet.

    private val crc32_table: LongArray = longArrayOf( // signed int workaround
        0x00000000,0x77073096,0xee0e612c,0x990951ba,0x076dc419,0x706af48f,0xe963a535,
        0x9e6495a3,0x0edb8832,0x79dcb8a4,0xe0d5e91e,0x97d2d988,0x09b64c2b,0x7eb17cbd,
        0xe7b82d07,0x90bf1d91,0x1db71064,0x6ab020f2,0xf3b97148,0x84be41de,0x1adad47d,
        0x6ddde4eb,0xf4d4b551,0x83d385c7,0x136c9856,0x646ba8c0,0xfd62f97a,0x8a65c9ec,
        0x14015c4f,0x63066cd9,0xfa0f3d63,0x8d080df5,0x3b6e20c8,0x4c69105e,0xd56041e4,
        0xa2677172,0x3c03e4d1,0x4b04d447,0xd20d85fd,0xa50ab56b,0x35b5a8fa,0x42b2986c,
        0xdbbbc9d6,0xacbcf940,0x32d86ce3,0x45df5c75,0xdcd60dcf,0xabd13d59,0x26d930ac,
        0x51de003a,0xc8d75180,0xbfd06116,0x21b4f4b5,0x56b3c423,0xcfba9599,0xb8bda50f,
        0x2802b89e,0x5f058808,0xc60cd9b2,0xb10be924,0x2f6f7c87,0x58684c11,0xc1611dab,
        0xb6662d3d,0x76dc4190,0x01db7106,0x98d220bc,0xefd5102a,0x71b18589,0x06b6b51f,
        0x9fbfe4a5,0xe8b8d433,0x7807c9a2,0x0f00f934,0x9609a88e,0xe10e9818,0x7f6a0dbb,
        0x086d3d2d,0x91646c97,0xe6635c01,0x6b6b51f4,0x1c6c6162,0x856530d8,0xf262004e,
        0x6c0695ed,0x1b01a57b,0x8208f4c1,0xf50fc457,0x65b0d9c6,0x12b7e950,0x8bbeb8ea,
        0xfcb9887c,0x62dd1ddf,0x15da2d49,0x8cd37cf3,0xfbd44c65,0x4db26158,0x3ab551ce,
        0xa3bc0074,0xd4bb30e2,0x4adfa541,0x3dd895d7,0xa4d1c46d,0xd3d6f4fb,0x4369e96a,
        0x346ed9fc,0xad678846,0xda60b8d0,0x44042d73,0x33031de5,0xaa0a4c5f,0xdd0d7cc9,
        0x5005713c,0x270241aa,0xbe0b1010,0xc90c2086,0x5768b525,0x206f85b3,0xb966d409,
        0xce61e49f,0x5edef90e,0x29d9c998,0xb0d09822,0xc7d7a8b4,0x59b33d17,0x2eb40d81,
        0xb7bd5c3b,0xc0ba6cad,0xedb88320,0x9abfb3b6,0x03b6e20c,0x74b1d29a,0xead54739,
        0x9dd277af,0x04db2615,0x73dc1683,0xe3630b12,0x94643b84,0x0d6d6a3e,0x7a6a5aa8,
        0xe40ecf0b,0x9309ff9d,0x0a00ae27,0x7d079eb1,0xf00f9344,0x8708a3d2,0x1e01f268,
        0x6906c2fe,0xf762575d,0x806567cb,0x196c3671,0x6e6b06e7,0xfed41b76,0x89d32be0,
        0x10da7a5a,0x67dd4acc,0xf9b9df6f,0x8ebeeff9,0x17b7be43,0x60b08ed5,0xd6d6a3e8,
        0xa1d1937e,0x38d8c2c4,0x4fdff252,0xd1bb67f1,0xa6bc5767,0x3fb506dd,0x48b2364b,
        0xd80d2bda,0xaf0a1b4c,0x36034af6,0x41047a60,0xdf60efc3,0xa867df55,0x316e8eef,
        0x4669be79,0xcb61b38c,0xbc66831a,0x256fd2a0,0x5268e236,0xcc0c7795,0xbb0b4703,
        0x220216b9,0x5505262f,0xc5ba3bbe,0xb2bd0b28,0x2bb45a92,0x5cb36a04,0xc2d7ffa7,
        0xb5d0cf31,0x2cd99e8b,0x5bdeae1d,0x9b64c2b0,0xec63f226,0x756aa39c,0x026d930a,
        0x9c0906a9,0xeb0e363f,0x72076785,0x05005713,0x95bf4a82,0xe2b87a14,0x7bb12bae,
        0x0cb61b38,0x92d28e9b,0xe5d5be0d,0x7cdcefb7,0x0bdbdf21,0x86d3d2d4,0xf1d4e242,
        0x68ddb3f8,0x1fda836e,0x81be16cd,0xf6b9265b,0x6fb077e1,0x18b74777,0x88085ae6,
        0xff0f6a70,0x66063bca,0x11010b5c,0x8f659eff,0xf862ae69,0x616bffd3,0x166ccf45,
        0xa00ae278,0xd70dd2ee,0x4e048354,0x3903b3c2,0xa7672661,0xd06016f7,0x4969474d,
        0x3e6e77db,0xaed16a4a,0xd9d65adc,0x40df0b66,0x37d83bf0,0xa9bcae53,0xdebb9ec5,
        0x47b2cf7f,0x30b5ffe9,0xbdbdf21c,0xcabac28a,0x53b39330,0x24b4a3a6,0xbad03605,
        0xcdd70693,0x54de5729,0x23d967bf,0xb3667a2e,0xc4614ab8,0x5d681b02,0x2a6f2b94,
        0xb40bbe37,0xc30c8ea1,0x5a05df1b,0x2d02ef8d
    );

    fun crc32(s: ByteArray, len: Int): Long
    {
        var crc: Long = 0
        for(i in 0..len) {
            val crc8: Int = (crc and 0xFF).toInt() // 0xFF masks
            val b: Int = s.get(i).toInt() and 0xFF // 0xFF designifies
            crc = crc32_table.get(crc8 xor b) xor (crc ushr 8);
        }
        return crc;
    }

    /////////

    fun get_minute() : Int { return (LocalDateTime.now() as LocalDateTime).minute }

    // minute is valid if from the current or previous minute
    fun valid_minute(mindiff: Int) : Boolean { return (mindiff == 0) || (((mindiff + 1) + 60) % 60 == 0) }

    /////////

    // 'open inner' needed for parent cxt resolution
    open inner class Apppacket {
        val app_offset = 10
        val text_offset = 2
        val pak_size = 20
        val text_size = pak_size - text_offset

        var pakdat = ByteArray(pak_size){0}
        var pakhash: Int = 0
        var rssi: Int = 0

        constructor () {}
        constructor(pp: Apppacket) {
            pakdat = pp.pakdat.copyOf()
            pakhash = pp.pakhash
            rssi = pp.rssi
        }

        fun valid_priv(privcode : Int) : Boolean { return (pakdat.get(0).toInt() and 0xFF) == privcode }
        fun valid_minute(clockmin : Int) : Boolean {
            // reference parent method which happens to have the same signature
            return this@AppLogic.valid_minute((pakdat.get(1).toInt() and 0xFF) - clockmin)
        }
        fun text_get() : String { return String(pakdat.sliceArray(2..pak_size-1), Charsets.UTF_8)}
    }

    ///////

    open inner class Txablepacket: Apppacket {
        var xtime: Int = 0
        constructor () {}
        constructor (ap: Apppacket, xt: Int): super(ap) {
            xtime = xt
        }
    }

    open inner class Pripacket: Txablepacket {
        var priority: UInt = 0u
        constructor () {}
        constructor(ap: Apppacket, xt: Int): super(ap, xt) {
            priority = (0xFF + ap.rssi).toUInt() // smaller rssi (closer station) is higher priority
            pripacket_total += priority
        }
    }

    // like a struct or a Pair<a,b> to return two args from a function
    data class Pickresult(val found: Boolean, val packet: Txablepacket)

    fun pick_packet(m: Int) : Pickresult {
        while(srcpackets.isNotEmpty()) {
            val packet = srcpackets.pop()
            if(packet.valid_minute(m)) return Pickresult(true, packet)
            if(debugmode) Log.i(TAG, "Skipping expired packet");
        }

        while(meshpackets.isNotEmpty()) {
            var t0 = 0u
            val t1 = kotlin.random.Random.nextUInt(0u, pripacket_total)
            val iter = meshpackets.listIterator()
            while(iter.hasNext()) {
                val pp = iter.next()
                t0 += pp.priority
                if(t0 >= t1) {
                    if(!pp.valid_minute(m)) { if(debugmode) Log.i(TAG, "Skipping expired packet"); break }
                    pripacket_total = pripacket_total - pp.priority
                    iter.remove()
                    return Pickresult(true, pp) // don't erase packet until stale/expires
                }
            }
            if(debugmode) Log.i(TAG, "meshpick overflow. trying again.");
        }

        return Pickresult(false, Txablepacket())
    }

    /////////

    // used to filter duplicate packets / packets we've seen within the last interval
    // used to prevent packet looping in mesh mode
    var dupl_minute: Int = 0xFF // invalid, by default
    var even = BooleanArray(256) { false }
    var odd = BooleanArray(256) { false }
    var dupl_table = arrayOf(even, odd)        // for even and odd minutes

    fun dupl_mark(m: Int, h: Int) {
        dupl_table[m and 0x01][h] = false
        dupl_table[((m +1) % 60) and 0x01][h] = true
    }
    fun dupl_clear(m: Int) { dupl_table[m and 0x01].fill(false); }
    fun dupl_test(m: Int, h: Int): Boolean { return dupl_table[m and 0x01][h]; }
    fun dupl_tick(m: Int) { if(m != dupl_minute) { dupl_clear(dupl_minute); dupl_minute = m; } }  // on minute rollover, flush the dupl table

    /////////

    // spurious wakeup workaround
    // this used to be a problem with java on linux but not java on windows
    fun ThreadSleep(delay: Int) {
        val quant = 10 // time quantum / tolerance
        var t = System.currentTimeMillis()
        val expire: Long = delay.toLong() + t
        while(true) {
            val remaining = expire - t
            if (remaining <= quant) break // done on v.small delays or when past deadine
            Thread.sleep(remaining)
            t = System.currentTimeMillis()
        }
    }

    /////////

    init {
        mActivity = activity
        mUIHandler = uiHandler

        // timer events
        // for the current minute, flush the duplicate-table
        // pick a packet: send either a src packet or if there are none forward a mesh packet
        thread(isDaemon = true, name = "timer") {
            var t_delay = TO_200MS
            while(!shutdown) {
                ThreadSleep( t_delay )
                t_delay = TO_200MS // default sleep-time on next loop

                try {
                    minute = get_minute();
                    dupl_tick(minute)
                    val pickresult = pick_packet(minute)
                    if (pickresult.found) {
                        var packet = arrayListOf<Byte>() // mutable object
                        val header = ubyteArrayOf(0xBEu, 0xACu).toByteArray().asList()
                        packet.addAll(header)
                        packet.addAll(pickresult.packet.pakdat.asList());

                        t_delay = pickresult.packet.xtime

                        val dataBuilder = AdvertiseData.Builder()
                        dataBuilder.addManufacturerData(mfgcode.toInt(), packet.toByteArray())
                        dataBuilder.setIncludeTxPowerLevel(true) // app protocol uses this
                        val data = dataBuilder.build()

                        if (debugmode) {
                            val mfgdata = data.manufacturerSpecificData[mfgcode.toInt()]
                            var ba = ByteArray(mfgdata.size) // mutable object
                            for (i in 0..ba.size - 1) ba[i] = mfgdata.get(i)
                            Log.i(TAG, "OutPacket: " + ba.ToHexString())
                        }

                        mUIHandler.post {
                            mActivity.SetAdvertisingData(data)
                        }
                    }
                } catch (e: Exception) {
                    if (debugmode) Log.e(TAG, "Timer exception: " + e.message)
                    t_delay = TO_3SEC // after an exception, bigger delay for possible further exceptions
                }
            }
        }

        // keyboard events
        thread(isDaemon = true, name = "input") {
            while(!shutdown) {
                try {
                    val txt = mLocalText.take() // from front ** BLOCKS **
                    var packet = Apppacket() // mutable object
                    packet.pakdat.fill(0)
                    packet.pakdat[0] = privcode.toByte()
                    packet.pakdat[1] = minute.toByte()
                    txt.toByteArray(Charsets.UTF_8).copyInto(packet.pakdat, 2,0, txt.length)

                    srcpackets.add(Txablepacket(packet, TO_3SEC))
                } catch (e: Exception) {
                    if (debugmode) Log.e(TAG, "Local input exception: " + e.message)
                }
            }
        }

        // network events
        // drop packets that are older than 1 minute ago and that we've seen before
        // queue remaining packets for mesh-forwarding
        // of these, drop any that don't match our privacy-code
        // display the remaining
        thread(isDaemon = true, name = "network") {
            while(!shutdown) {
                try {
                    do {
                        val adv = mNetworkBytes.take() // front front ** BLOCKS **
                        if (debugmode) Log.i(TAG, "InPacket: " + adv.ToHexString())
                        if (extractHeader(adv) != (0x1BFF).toShort()) { if (debugmode) Log.i(TAG, "Not advertisment header"); break }
                        if (extractMFGCode(adv) != mfgcode) { if (debugmode) Log.i(TAG, "Wrong mfgcode"); break }
                        if (extractBEAC(adv) != (0xBEAC).toShort()) { if (debugmode) Log.i(TAG, "Not a beacon"); break }

                        var packet = Apppacket() // var as object is mutable
                        adv.copyInto(packet.pakdat,0,9, Apppacket().text_size)
                        packet.pakhash = crc8(packet.pakdat, Apppacket().text_size)
                        packet.rssi = extractRSSI(adv)

                        if (!packet.valid_minute(minute)) { if (debugmode) Log.i(TAG, "Expired app packet"); break }
                        if (dupl_test(minute, packet.pakhash)) { if (debugmode) Log.i(TAG, "Duplicate app packet"); break }
                        dupl_mark(minute, packet.pakhash) // prevent repeat xmit in current and next minute
                        if (meshmode) meshpackets.add(Pripacket(packet, TO_1SEC))
                        if (!packet.valid_priv(privcode)) { if (debugmode) Log.i(TAG, "Squelched app packet"); break }
                        val msg = packet.text_get()

                        mUIHandler.post {
                            mActivity.AddWindowText(msg, (Math.random() * 0xFF).roundToInt()) // todo: pick color from rssi?
                        }
                    } while(false)
                } catch (e: Exception) {
                    if (debugmode) Log.e(TAG, "Network input exception: " + e.message)
                }
            }
        }
    }
}
