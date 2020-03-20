# btbchat
Bluetooth Beacon Chat

A pairing-free packet and protocol for one to one or many to many (mesh) chat via bluetooth.

btbchat packets are in non-connectable bluetooth [Alt-Beacon](https://github.com/AltBeacon/spec) format, of which there are really only 20 bytes of Beacon Data available for btbchat. Alt-beacon looks generally like this:

AD len & AD type (2B) | MFG ID (2B) | BEAC Code (2B) | Beacon Data (20B) | RSSI (1B) | MFG Other (1B)
-----------|-----------|------------|-----------------|-------------|-------------

Into this packet, btbchat sets the MFG ID (2 bytes), the Beacon Data (20 bytes) and places a fixed value into RSSI (1 byte). Into the 20 bytes of beacon data btbchat tries to fit privacy and routing code-bytes - leaving 18 bytes for unencrypted/uncompressed text.

The other bytes are set according to the bluetooth spec, except for the MFG Other bytes which is left 0 as the Android api doesn't seem to have a way for us to set it. The btbchat packet is then simply 2 code bytes and 18 bytes of text (no CR/LF and 0 cleared):

Privacy Code (1B) | Routing Code (1B) | Chat Text (18B)
------------------|-------------------|----------------

#### Privacy Byte

The purpose of btbchat is to try to use non-connectable beacons for basic unsecured 2 point chat and/or mesh chat. Instead of trying to secure the chat contents, btbchat instead tries to filter chat contents that are displayed using a 'privacy code' akin what is used in FMR/GMRS radio systems - more of an anti-spam code. 

btbchat initially uses a user-selectable privacy-code, typically a byte-code derived from a keyword hash. This code is assumed to be static for the duration of the chat but more advanced btbchat forks could use a shared clock to pseudo-randomly rotate this code or an actual text encryption key.

#### Routing Byte

Being unconnectable beacons there no packet-acknowledgements. So, the beacon packets (containing the btbchat packets) are left to transmit for several seconds at a reasonably high rate. What happends is that when a btbchat packet is built, it is stamped with **the current clock-minute as a routing code**. Any btbchat application that receives this packet will (conditionally) retain it for occasional retransmission through the current and next minute.

#### RSSI Byte

The RSSI byte in a beacon packet allows a beacon receiver to determine distance to the beacon transmitter by using an rf propagation model with the RSSI value. The RSSI value must be calibrated on a per-transmitter basis which btbchat doesn't do, so btbchat uses a fixed value.

#### Application Logic Structure (Generally, at time of writing)

Exactly how long a beacon is left to transmit btbchat data for and if the btbchat data is ever transmitted again is part of the btbchat application logic. This logic manages the transmission of packets from a source and the retransmission of packets from other sources (ie mesh mode). The logic uses a packet's routing-code (which is the minute creation-time), the RSSI of the transmitting station, a packet-hash and some queues to retransmit btbchat packets so they don't get lost.

As there is no source addressing information that be relied upon, the decision to retain the packet for later retransmission is made according the presence of it's hash in a recently-seen-packet table which is flushed every other minute.

When a packet is received, the RSSI of the transmitting station of the packet is normalized so that it can be used as a priority for retransmission (such that farther stations are given lower priority). The priority is used as a probability in a stochastic selection of retransmittable packets.

When a source creates a chat message the text is packed into a packet and queued in a source-queue. Packets from the source queue are always picked for retransmission before received packets are selected via stochastic selection. So, sourced packets have priority over mesh packets, and from mesh packets the nearer ones have a higher likelihood of sooner transmission than farther mesh packets.

#### Implementation Details

The linux client is a single threaded app that uses device polling to check for user and mesh packets and a timeout mechanism to set the beacon.

The android client uses threads for each of user and mesh packets and for setting the beacon in addition to the default main UI thread.
