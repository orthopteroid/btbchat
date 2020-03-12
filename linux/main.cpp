#include <unistd.h>
#include <cstdio>
#include <cerrno>
#include <cstring>
#include <iostream>
#include <cstdlib>
#include <stdio.h>
#include <getopt.h>
#include <sys/ioctl.h>
#include <sys/socket.h>

#include <poll.h>
#include <csignal>

#include <bluetooth/bluetooth.h>
#include <bluetooth/hci.h>
#include <bluetooth/hci_lib.h>

#include <boost/date_time/posix_time/posix_time.hpp>
#include <random>

#define SCAN_FILTER_DUP 0x01
#define SCAN_TYPE 0x01
#define SCAN_FILTER_POLICY 0x00
#define SCAN_INTERVAL 0x0010
#define SCAN_WINDOW 0x0010

#define TO_10SECS 10000
#define TO_200MS 200
#define TO_1SEC 1000
#define TO_2SEC 2000
#define TO_3SEC 3000

#define failmessage(s) if(debugmode>1) printf("%s\n",(s));
#define failmessage_break(s) { failmessage(s) break; }
#define failmessage_continue(s) { failmessage(s) continue; }

#define statusmessage(s) if(debugmode>0) printf("%s\n",(s));
#define statusmessage_break(s) { statusmessage(s) break; }
#define statusmessage_continue(s) { statusmessage(s) continue; }

// 0=silent, 1=info, 2=verbose
#if defined(DEBUG)
#define DEFAULT_DEBUG_MODE 2
#else
#define DEFAULT_DEBUG_MODE 0
#endif

struct txablepacket;
struct pripacket;

int debugmode = DEFAULT_DEBUG_MODE;
int meshmode = 0;
uint8_t privcode = 0;
uint16_t mfgcode = 0x1122;
uint8_t minute = 0xFF; // default, invalid
std::deque<txablepacket> srcpackets; // packets we are the source for
std::vector<pripacket> meshpackets; // mesh / forwardable packets
uint pripacket_total = 0;

////////////

static hci_filter old_sock_settings;

std::random_device rnd_seed;
std::default_random_engine rnd(rnd_seed());

static int dev_fd = -1;

static uint8_t hcibuf[256];
static char h2abuf[256];
static char msgbuf[256];

//////////////////

static int signal_received = 0;
static void signal_handler(int sig) { signal_received = sig; }

//////////////////

static inline uint8_t negate(uint8_t v) { return (uint8_t)~v + 1; }

template<class ByteType>
constexpr uint8_t text_len(const ByteType* s, const uint8_t maxlen) {
    uint8_t i = 0;
    while(i<maxlen && s[i]!=(ByteType)' ' && s[i]!=(ByteType)0) i++; // halt scan on ' ' or \0
    return i;
}

//////////////////

// byte array to uppercase hex/ascii \0 terminated string
template<class ByteType>
static char* htoa(char* buf, size_t bufsize, const ByteType* data, uint8_t len) {
    const char* hex = "0123456789ABCDEF";
    for (int s = 0; (s < len) && (s*2+1 < bufsize); s++) {
        buf[s*2+0] = hex[data[s] >> 4];
        buf[s*2+1] = hex[data[s] & 0x0F];
        buf[s*2+2]=0;
    }
    return buf;
}

//////////////////

// The 1-Wire CRC scheme is described in Maxim Application Note 27
// https://gist.github.com/brimston3/83cdeda8f7d2cf55717b83f0d32f9b5e
static constexpr uint8_t crc8_table[] = {
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
};

template<class ByteType>
static constexpr uint8_t crc8(const ByteType* s, const uint8_t len)
{
    uint8_t crc = 0;
    for (int i = 0; i < len; i++) crc = crc8_table[crc ^ (uint8_t)s[i]];
    return crc;
}

//////////

// msoffice 5.1 32-Bit CRC Algorithm for COMPRESSED_BLOCK_HEADER
// https://docs.microsoft.com/en-us/openspecs/office_protocols/ms-abs/06966aa2-70da-4bf9-8448-3355f277cd77?redirectedfrom=MSDN
// might need this, not sure yet.

static constexpr uint32_t crc32_tab[] = {
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
};

static constexpr uint32_t crc32(const uint8_t* s, int len)
{
    uint32_t crc = 0;
    for(int i = 0;  i < len;  i++) {
        uint8_t b = (crc & (uint32_t)0xFF) ^ s[i];
        crc = crc32_tab[b] ^ (crc >> (uint8_t)8);
    }
    return crc;
}

//////////////////

static inline uint8_t get_minute() { return boost::posix_time::microsec_clock::local_time().time_of_day().minutes(); }

// minute is valid if from the current or previous minute
static inline bool valid_minute(const int mindiff) { return (mindiff == 0) || ((((mindiff + 1) + 60) %60) == 0); }

///////////////////

struct apppacket {
    static constexpr int app_offset = 10;
    static constexpr int text_offset = 2;
    static constexpr int pak_size = 20;
    static constexpr int text_size = pak_size - text_offset;
    uint8_t pakdat[pak_size]; // <priv> <minute> <text...>
    uint8_t pakhash;
    int8_t rssi;
    apppacket() = default;
    apppacket(const apppacket & pp) {
        ::memcpy(pakdat, pp.pakdat, pak_size);
        pakhash = pp.pakhash;
        rssi = pp.rssi;
    }
    virtual ~apppacket() = default;
    bool valid_priv(const uint8_t privcode) const { return pakdat[0] == privcode; }
    bool valid_minute(const uint8_t clockmin) const { return ::valid_minute(pakdat[1] - clockmin); }
    const char* text_start() const { return (const char*)pakdat + text_offset; }

    void parse_host(const uint8_t privcode, const uint8_t minute)
    {
        memset(&pakdat, 0, sizeof(pakdat));
        pakdat[0] = privcode;
        pakdat[1] = minute;
        int len = read(0, &pakdat[text_offset], text_size);
        pakdat[text_offset + len - 1]=0; // clean /n
        pakhash = crc8(pakdat, sizeof(pakdat));
        rssi = 0xFF; // high priority, small -ve value
    }
    int parse_network() {
        // parse an hci packet and extract an advertisement body (if applicable). example:
        // 043e28020102013a2d0161166d1c1bff0552beac2f234454cf6d4a0fadf2f4911ba9ffa600010003c500c2
        //   ||          ||||||||||||  ||          ||                                          ||
        //   META        BT ADDR       ADV LEN     ADV BODY                                    META RSSI
        // ||  ||                    ||
        // HCI META LEN              META DATA LEN

        do {
            uint16_t hciheader = bt_get_le16(hcibuf);
            if(hciheader != ((EVT_LE_META_EVENT << 8) | HCI_EVENT_PKT)) statusmessage_break("Unknown HCI packet")

            snprintf(msgbuf, sizeof(msgbuf), "HCI packet in: %s", htoa(h2abuf, sizeof(h2abuf), hcibuf, hcibuf[2]));
            statusmessage(msgbuf);

            if (bt_get_le16(hcibuf + 18) != 0xacbe) failmessage_break("Not a beacon")
            if (bt_get_le16(hcibuf + 16) != 0x1122) failmessage_break("Not our beacon")

            int applen = (uint8_t)std::min(sizeof(pakdat), (size_t)(hcibuf[14] - 5)); // -5 for ff mm nn be ac header
            snprintf(msgbuf, sizeof(msgbuf), "App packet in: %s", htoa(h2abuf, sizeof(h2abuf), hcibuf + 20, applen));
            statusmessage(msgbuf);

            memset(pakdat, 0, sizeof(pakdat));
            memcpy(pakdat, hcibuf + 20, applen);
            pakhash = crc8(pakdat, sizeof(pakdat));

            rssi = hcibuf[20 + applen];

            snprintf(msgbuf, sizeof(msgbuf), "App packet hash: %02X  rssi: %d", pakhash, rssi);
            statusmessage(msgbuf);

            return 0;
        } while(false);

        return 1;
    }
    int set_beacon() {
        uint8_t status;

        do {
            // outgoing packet example:
            // 1F02011A1BFF2211BEAC001D343435353535350000000000000000000000FF00
            const uint8_t advtemplate[] = {
                0x1F,0x02,0x01,0x1A,0x1B,0xFF,0x22,0x11,0xBE,0xAC, // adv preamble mfgr: 0x1122
                0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00, // apppacket
                negate(50),0x00 // fixed rssi and misc
            };

            memcpy(hcibuf, advtemplate, sizeof(advtemplate));
            memcpy(hcibuf + app_offset, pakdat, sizeof(pakdat));
            bt_put_le16(mfgcode, hcibuf + 6);

            snprintf(msgbuf, sizeof(msgbuf), "Beacon packet out: %s", htoa(h2abuf, sizeof(h2abuf), hcibuf, hcibuf[0] + 1)); // +1 for len byte
            failmessage(msgbuf);

            hci_le_set_advertise_enable(dev_fd, 0x00, TO_1SEC); // ignore fail

            // the fastest adv type 3 can advertise in 100ms, so min_internal can't be smaller than 0x00A0 as 0x00A0*.625ms=100ms
            // https://stackoverflow.com/questions/21124993/is-there-a-way-to-increase-ble-advertisement-frequency-in-bluez#21126744
            le_set_advertising_parameters_cp adv_params_cp = {
                htobs(0x00A0), htobs(0x00A0 * 2),3,0,0,
                {0,0,0,0},7,0
            };
            hci_request adv_params = {0x08,0x0006,0, &adv_params_cp,15, &status,1};
            if(hci_send_req(dev_fd, &adv_params, TO_1SEC) < 0) failmessage_break("Unable to set advertising parameters")

            hci_request adv_data = {0x08, 0x0008, 0, hcibuf, 32, &status, 1};
            if (hci_send_req(dev_fd, &adv_data, TO_1SEC) < 0) failmessage_break("Unable to set advertising data")

            if (hci_le_set_advertise_enable(dev_fd, 0x01, TO_1SEC)) failmessage_break("Enable beacon failed")
            return 0;
        } while(false);
        return 1;
    }

};

struct txablepacket: public apppacket {
    txablepacket() = default;
    txablepacket(const apppacket& ap, const int xt) : apppacket(ap), xtime(xt) {}
    int xtime;
};

struct pripacket: public txablepacket {
    pripacket(const apppacket& ap, const int xt) : txablepacket(ap, xt) {
        priority = 0xFF - negate(ap.rssi); // higher priority to nearer stations, as rssi is -ve
        pripacket_total += priority;
    }
    uint priority;
};

bool pick_packet(txablepacket &packet, const uint8_t m) {
    // grab first valid src packet
    while(!srcpackets.empty()) {
        do {
            packet = srcpackets.front();
            if (!packet.valid_minute(m)) statusmessage_break("Skipping expired packet\n")
            return true;
        } while(false);
    }

    // otherwise, randomly pick a mesh packet
    while (!meshpackets.empty()) {
        uint32_t t0 = 0;
        uint32_t t1 = rnd() % pripacket_total;
        auto iter = meshpackets.begin();
        while (iter != meshpackets.end()) {
            t0 += iter->priority;
            if(t0 >= t1) {
                if (!iter->valid_minute(m)) statusmessage_break("Skipping expired packet")
                packet = *iter;
                pripacket_total -= iter->priority;
                meshpackets.erase(iter);
                return true;
            }
            iter++;
        }
        statusmessage("meshpick overflow. trying again.")
    }
    return false;
}

//////////////////

bool timer_expired(const boost::posix_time::ptime &t_expire)
{
    auto t_curr = boost::posix_time::second_clock::local_time();
    return  t_curr > t_expire;
}

bool timer_delay(boost::posix_time::ptime &t_expire, uint msec)
{
    t_expire = boost::posix_time::second_clock::local_time() + boost::posix_time::milliseconds( msec + (rnd() % 250) );
}

////////////////

// used to filter duplicate packets / packets we've seen within the last interval
// used to prevent packet looping in mesh mode
static uint8_t dupl_minute = 0xFF; // invalid, by default
boost::array<bool, 256> dupl_table[2]; // for current and next minute

static inline void dupl_mark(const uint m, const uint h) { dupl_table[m & 0x01][h] = dupl_table[((m +1) % 60) & 0x01][h] = true; }
static inline void dupl_clear(const uint m) { dupl_table[m & 0x01].fill(false); }
static inline bool dupl_test(const uint m, const uint h) { return dupl_table[m & 0x01][h]; }
static inline void dupl_tick(const uint8_t m) { if(m != dupl_minute) { dupl_clear(dupl_minute); dupl_minute = m; } }  // on minute rollover, flush the dupl table

//////////////////

int parse_cmd(const apppacket &ap)
{
    if(*ap.text_start()!='/') return 0; // skip cmd parsing when / prefix missing
    unsigned int temp;

    auto cmd_len = [](const char* s) { return ::text_len(s, apppacket::text_size); };
    auto arg_len = [](const char* s) {
        auto argoffset = ::text_len(s, apppacket::text_size -2) + 2; // +2 for start of arg
        auto arglen = ::text_len(s +argoffset, apppacket::text_size -argoffset) +argoffset;
        return arglen;
    };
    auto arg_start = [&ap, cmd_len]() -> const char* {
        auto argoffset = cmd_len(ap.text_start()) + 1; // +1 for start of arg
        auto argstart = ap.text_start() + argoffset;
        return argoffset < apppacket::text_size ? argstart : nullptr;
    };

    // a cmd token combines values that help ensure it is unique. ie: first two letters, length and crc8
    auto cmdtoken = [](const char* s, const uint8_t len) -> uint { return (s[1]<<24) | (s[2]<<16) | (len<<(uint8_t)8) | crc8(s,len); };
    auto strid = [cmdtoken](const char* s) -> uint { return cmdtoken(s,::text_len(s,apppacket::text_size)); };
    auto cmdid = [&ap, strid]() -> uint { return strid(ap.text_start()); };

    const char* arg = arg_start();
    switch( cmdid() )
    {
        case strid("/pc"):
        case strid("/privcode"):
            privcode = *arg ? crc8(arg, arg_len(arg)) : 0;
            break;
        case strid("/dm"):
        case strid("/debugmode"):
            debugmode = *arg ? *arg - '0' : 0;
            break;
        case strid("/mm"):
        case strid("/meshmode"):
            meshmode = *arg ? *arg - '0' : 0;
            break;
        case strid("/mc"):
        case strid("/mfgcode"):
            mfgcode = 0;
            if(*arg) { sscanf(arg, "%4x", &temp); mfgcode = temp; }
            break;
        case strid("/st"):
        case strid("/status"):
            printf("privcode %02X debugmode %d meshmode %d mfgcode %02X\n",privcode,debugmode,meshmode,mfgcode);
            break;
        case strid("/q"):
        case strid("/quit"):
            signal_received = SIGINT;
            break;
        case strid("/?"):
            printf("[ /pc | /privcode ] <max18chartext>     [ /dm | /debugmode ] [0|1|2]\n");
            printf("[ /mc | /mfgcode  ] <4hexdigits>        [ /mm | /meshmode  ] [0|1]\n");
            printf("[ /q  | /quit ]                         [ /? ]\n");
            break;
        default:
            return 0;
    }
    return 1;
}

//////////////////

// instead of sudo ... consider `sudo setcap CAP_NET_RAW+ep "$(readlink -f /usr/sbin/app)"`
// https://security.stackexchange.com/q/128958
int hci_up(int dev_id)
{
    int ctl = socket(AF_BLUETOOTH, SOCK_RAW, BTPROTO_HCI);
    int fail = (ioctl(ctl, HCIDEVUP, dev_id) < 0) && (errno != EALREADY) ? 1 : 0;
    close(ctl);
    return fail;
}

int hci_read() {
    do {
        memset(&hcibuf, 0, sizeof(hcibuf));
        int count = read(dev_fd, (void *) &hcibuf, sizeof(hcibuf));
        if (count == 0) failmessage_break("Socket closed")
        else if ((count < 0) && !(errno == EAGAIN || errno == EINTR)) failmessage_break("Unknown socket error")
        return 0;
    } while(false);
    return 1;
}

int main() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_flags = SA_NOCLDSTOP;
    sa.sa_handler = signal_handler;
    sigaction(SIGINT, &sa, NULL);

    ////////////////////

    auto t_expire = boost::posix_time::second_clock::local_time();

    apppacket packet;
    strcpy((char*)packet.text_start(), "/status");
    parse_cmd(packet);

    minute = get_minute();
    dupl_tick(minute);

    do {
        if (hci_up(0/* device 0*/)) failmessage_break("Can't bring up hci")
        if ((dev_fd = hci_open_dev(0/* device 0*/)) < 0) failmessage_break("Could not open device")

        // configure scan mode
        if (hci_le_set_scan_parameters(dev_fd, SCAN_TYPE, htobs(SCAN_INTERVAL), htobs(SCAN_WINDOW), LE_PUBLIC_ADDRESS, SCAN_FILTER_POLICY, TO_10SECS) < 0) failmessage_break("Set scan parameters failed")
        if (hci_le_set_scan_enable(dev_fd, 0x01, SCAN_FILTER_DUP, TO_10SECS) < 0) failmessage_break("Enable scan failed")

        // save and set filter
        socklen_t olen = sizeof(old_sock_settings);
        if (getsockopt(dev_fd, SOL_HCI, HCI_FILTER, &old_sock_settings, &olen) < 0) failmessage_break("HCI filter save failed")
        struct hci_filter flt;
        hci_filter_clear(&flt);
        hci_filter_set_ptype(HCI_EVENT_PKT, &flt);
        hci_filter_all_events(&flt);
        if (setsockopt(dev_fd, SOL_HCI, HCI_FILTER, &flt, sizeof(flt)) < 0) failmessage_break("HCI filter set failed")

        hci_le_set_advertise_enable(dev_fd, 0x00, TO_1SEC); // ignore fail

        // begin interactive chat
        struct pollfd pollgroup[2] = {{.fd = 0, .events = POLLIN}, {.fd = dev_fd, .events = POLLIN}};
        do {
            errno = 0;
            if (signal_received == SIGINT) statusmessage_break("Signal received")

            int nevt = poll(pollgroup, 2, TO_200MS); // poll an fd group, with timeout
            if (nevt<0) continue; // err

            // timer events
            if (timer_expired(t_expire)) {
                minute = get_minute();
                dupl_tick(minute);
                txablepacket txpak;
                if (pick_packet(txpak, minute)) {
                    if (txpak.set_beacon()) failmessage_break("Set beacon failed")
                    timer_delay(t_expire, txpak.xtime);
                } else {
                    hci_le_set_advertise_enable(dev_fd, 0x00, TO_1SEC); // ignore fail
                    timer_delay(t_expire, TO_1SEC);
                }
            }

            // keyboard events
            if (pollgroup[0].revents > 0) {
                packet.parse_host(privcode, minute);
                snprintf(msgbuf, sizeof(msgbuf), "App packet: %s", htoa(h2abuf, sizeof(h2abuf), packet.pakdat, apppacket::pak_size));
                statusmessage(msgbuf);
                if (parse_cmd(packet)) continue;
                srcpackets.emplace_back(packet, TO_3SEC);
            }

            // network events
            if (pollgroup[1].revents > 0) {
                if (hci_read()) failmessage_break("Device error")
                if (packet.parse_network()) continue;
                if (!packet.valid_minute(minute)) statusmessage_continue("Expired app packet")
                if (dupl_test(minute, packet.pakhash)) statusmessage_continue("Duplicate app packet")
                dupl_mark(minute, packet.pakhash);
                if (meshmode) meshpackets.emplace_back(packet, TO_2SEC);
                if (!packet.valid_priv(privcode)) statusmessage_continue("Squelched app packet")
                memset(msgbuf,0, apppacket::text_size +1); // +1 for /0
                printf("(%d) %s\n", negate(packet.rssi), strncpy(msgbuf, packet.text_start(), apppacket::text_size));
            }
        } while (true); // message loop

        // reset filter, halt scanning and advertising
        setsockopt(dev_fd, SOL_HCI, HCI_FILTER, &old_sock_settings, sizeof(old_sock_settings));
        hci_le_set_scan_enable(dev_fd, 0x00, SCAN_FILTER_DUP, TO_1SEC); // ignore fail
        hci_le_set_advertise_enable(dev_fd, 0x00, TO_1SEC); // ignore fail

    } while(false);

    if (dev_fd >= 0)
        hci_close_dev(dev_fd);

    return 0;
}
