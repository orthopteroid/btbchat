package com.orthopteroid.btbchat.ui.main

import androidx.lifecycle.ViewModel

/*
 * https://altbeacon.github.io/android-beacon-library/samples.html
 * https://stackoverflow.com/a/51876272
 * https://ukbaz.github.io/howto/beacon_scan_cmd_line.html
 * https://github.com/dburr/linux-ibeacon/blob/master/ibeacon
 * https://esf.eurotech.com/docs/how-to-user-bluetooth-le-beacons
 * tools: btmon bluetoothctl hcitool
 * hciconfig hci0 down ; hciconfig hci0 up
 * btmgmt -i hci0 find

 * https://stackoverflow.com/a/21790504
 * systemctl daemon-reload ; systemctl restart bluetooth
 * hcitool lescan --duplicates 1>/dev/null & ; hcidump --raw

 * https://www.silabs.com/community/wireless/bluetooth/knowledge-base.entry.html/2017/11/14/bluetooth_advertisin-zCHh

 * https://www.bluetooth.com/specifications/assigned-numbers/company-identifiers/

 * adverts are on channel 37/38/39 (alternating)

 * https://stackoverflow.com/a/40251129
 * so, something like "24 bytes of actual data in the advertising data or 27 bytes in the scan response."

 * https://source.android.com/devices/bluetooth/ble_advertising
 * https://developer.android.com/reference/kotlin/android/bluetooth/le/AdvertisingSet
 * http://android-er.blogspot.com/2014/08/implement-simple-http-server-running-on.html
 *
 *
 */

class MainViewModel : ViewModel()// TODO: Implement the ViewModel
