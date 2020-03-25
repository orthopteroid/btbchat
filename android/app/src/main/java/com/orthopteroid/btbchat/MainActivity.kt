package com.orthopteroid.btbchat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.roundToInt

@kotlin.ExperimentalStdlibApi
@kotlin.ExperimentalUnsignedTypes
class MainActivity : AppCompatActivity() {
    protected val TAG = "MainActivity"

    private val REQUEST_BTENABLE = 1
    private val REQUEST_BTLOCATION = 2

    private var mAdapter: BluetoothAdapter? = null
    private var mAdvertiseCallback: AdvertiseCallback? = null
    private var mAdvertiseSettings: AdvertiseSettings? = null
    private var mAppLogic: AppLogic? = null

    fun failAndQuit(msg: String) {
        val builder = AlertDialog.Builder(this@MainActivity)
        builder.setTitle(":(")
        builder.setMessage(msg).setCancelable(false)
            .setPositiveButton("OK", DialogInterface.OnClickListener { dialog, id -> finish() })
        val dialog: AlertDialog = builder.create()
        dialog.show()
    }

    /////////////////////////

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        if (applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)==false) {
            failAndQuit("App can't run - it requires Bluetooth LE.")
            return
        }

        mAdapter = (this.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        if (mAdapter!!.isEnabled)
            testBTPermissions()
        else
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_BTENABLE)
    }

    override fun onDestroy() {
        super.onDestroy()

        mAppLogic?.shutdown = true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode==REQUEST_BTENABLE) {
            if (resultCode==Activity.RESULT_OK) {
                testBTPermissions()
            }
        }
    }

    fun testBTPermissions() {
        //if (mAdapter!!.isMultipleAdvertisementSupported==false) throw Exception() // api26, but not sure we need this...

        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            configHandlers()
        else
            ActivityCompat.requestPermissions(this as Activity, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_BTLOCATION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_BTLOCATION -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    configHandlers()
                    return
                }
            }
            else -> { }
        }

        failAndQuit("App can't run - it requires Bluetooth Location Permission.")
    }

    fun configHandlers() {
        val asb = AdvertiseSettings.Builder()
        asb.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
        asb.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        asb.setTimeout(10000) // msec
        asb.setConnectable(false)
        mAdvertiseSettings = asb.build()

        val ssb = ScanSettings.Builder()
        ssb.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        ssb.setMatchMode(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) // api 26+
        ssb.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) // api 23+

        // set scan filter here, if we were to do this.
        // but we're not going to because we're going to allow the app to change the mfgcode
        // https://github.com/AltBeacon/android-beacon-library/blob/master/lib/src/main/java/org/altbeacon/beacon/service/scanner/ScanFilterUtils.java

        ///////////////

        mAppLogic = AppLogic(this, Handler(Looper.getMainLooper()))

        ///////////////

        mAdvertiseCallback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                if(mAppLogic!!.debugmode) Log.e(TAG, "Advertisement start failed")
            }
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if(mAppLogic!!.debugmode) Log.i(TAG, "Advertisement start")
            }
        }

        val etext = findViewById<EditText>(R.id.editText) as EditText
        etext.setOnClickListener(object : View.OnClickListener {
            override fun onClick(view: View?) {
                val txt = etext.text.toString()
                if (txt.isNotEmpty()) {
                    AddWindowText(txt, (Math.random() * 0xFF).roundToInt()) // todo: pick color?
                    mAppLogic?.mLocalText?.addLast(txt)
                    etext.text.clear()
                }
            }
        })

        mAdapter?.bluetoothLeScanner?.startScan(
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    mAppLogic?.mNetworkBytes?.addLast(result.scanRecord!!.bytes)
                }
                //override fun onBatchScanResults(results: List<ScanResult?>?) {}
                override fun onScanFailed(errorCode: Int) {
                    if(mAppLogic!!.debugmode) Log.e(TAG, "Scan start failed")
                }
            }
        )
    }

    ///////////////////

    public fun AddWindowText(text: String, hexHue: Int) {
        val tview = getLayoutInflater().inflate(R.layout.main_item, null) as TextView
        tview.text = text

        val hsvArray = arrayOf(360f * (hexHue.toFloat() / 255f), .4f, 1f)
        val rgb = Color.HSVToColor(255, hsvArray.toFloatArray())
        tview.setBackgroundColor(rgb)

        val llview = findViewById<LinearLayout>(R.id.llView) as LinearLayout
        llview.addView(tview)

        val scroll = findViewById<ScrollView>(R.id.sView) as ScrollView
        val lastChild = scroll.getChildAt(scroll.childCount - 1)
        val bottom = lastChild.bottom + scroll.paddingBottom
        val delta = bottom - (scroll.scrollY+ scroll.height)
        scroll.smoothScrollBy(0, delta)
    }

    public fun SetAdvertisingData(data: AdvertiseData) {
        mAdapter?.bluetoothLeAdvertiser?.stopAdvertising(mAdvertiseCallback)
        mAdapter?.bluetoothLeAdvertiser?.startAdvertising(mAdvertiseSettings, data, mAdvertiseCallback)
    }



}
