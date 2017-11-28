package it.innove;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.os.Build;
import android.support.annotation.Nullable;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.RCTNativeAppEventEmitter;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH;
import static android.os.Build.VERSION_CODES.LOLLIPOP;

class CallbackException extends Exception {
  public CallbackException(String message) {
        super(message);
    }
}

/**
 * Peripheral wraps the BluetoothDevice and provides methods to convert to JSON.
 */
public class Peripheral extends BluetoothGattCallback {

    private static final String CHARACTERISTIC_NOTIFICATION_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";
    public static final String LOG_TAG = "logs";

    private BluetoothDevice device;
    private byte[] advertisingData;
    private int advertisingRSSI;
    private boolean connected = false;
    private ConcurrentLinkedQueue<BLECommand> commandQueue = new ConcurrentLinkedQueue<BLECommand>();
    private boolean bleProcessing;


    private BluetoothGatt gatt;

    private Callback connectCallback;
    private Callback retrieveServicesCallback;
    private Callback readCallback;
    private Callback readRSSICallback;
    private Callback writeCallback;
    private Callback registerNotifyCallback;

    private ReactContext reactContext;

    private List<byte[]> writeQueue = new ArrayList<>();

    public Peripheral(BluetoothDevice device, int advertisingRSSI, byte[] scanRecord, ReactContext reactContext) {

        this.device = device;
        this.advertisingRSSI = advertisingRSSI;
        this.advertisingData = scanRecord;
        this.reactContext = reactContext;

    }

    public Peripheral(BluetoothDevice device, ReactContext reactContext) {
        this.device = device;
        this.reactContext = reactContext;
    }

    private void sendEvent(String eventName, @Nullable WritableMap params) {
        reactContext
                .getJSModule(RCTNativeAppEventEmitter.class)
                .emit(eventName, params);
    }

    private void sendConnectionEvent(BluetoothDevice device, String eventName) {
        WritableMap map = Arguments.createMap();
        map.putString("peripheral", device.getAddress());
        sendEvent(eventName, map);
        Log.d(LOG_TAG, "Peripheral event (" + eventName + "):" + device.getAddress());
    }

    public void connect(Callback callback, Activity activity) {
        if (!connected) {
            BluetoothDevice device = getDevice();
            this.connectCallback = callback;
            gatt = device.connectGatt(activity, false, this);
        } else {
            if (gatt != null) {
                callback.invoke();
            } else
                callback.invoke("BluetoothGatt is null");
        }
    }

    public void disconnect() {
        connectCallback = null;
        connected = false;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
                gatt = null;
                Log.d(LOG_TAG, "Disconnect");
                sendConnectionEvent(device, "BleManagerDisconnectPeripheral");
            } catch (Exception e) {
                sendConnectionEvent(device, "BleManagerDisconnectPeripheral");
                Log.d(LOG_TAG, "Error on disconnect", e);
            }
        } else
            Log.d(LOG_TAG, "GATT is null");
    }

    public WritableMap asWritableMap() {

        WritableMap map = Arguments.createMap();

        try {
            map.putString("name", device.getName());
            map.putString("id", device.getAddress()); // mac address
            map.putMap("advertising", byteArrayToWritableMap(advertisingData));
            map.putInt("rssi", advertisingRSSI);
        } catch (Exception e) { // this shouldn't happen
            e.printStackTrace();
        }

        return map;
    }

    public WritableMap asWritableMap(BluetoothGatt gatt) {

        WritableMap map = asWritableMap();

        WritableArray servicesArray = Arguments.createArray();
        WritableArray characteristicsArray = Arguments.createArray();

        if (connected && gatt != null) {
            for (Iterator<BluetoothGattService> it = gatt.getServices().iterator(); it.hasNext(); ) {
                BluetoothGattService service = it.next();
                WritableMap serviceMap = Arguments.createMap();
                serviceMap.putString("uuid", UUIDHelper.uuidToString(service.getUuid()));


                for (Iterator<BluetoothGattCharacteristic> itCharacteristic = service.getCharacteristics().iterator(); itCharacteristic.hasNext(); ) {
                    BluetoothGattCharacteristic characteristic = itCharacteristic.next();
                    WritableMap characteristicsMap = Arguments.createMap();

                    characteristicsMap.putString("service", UUIDHelper.uuidToString(service.getUuid()));
                    characteristicsMap.putString("characteristic", UUIDHelper.uuidToString(characteristic.getUuid()));

                    characteristicsMap.putMap("properties", Helper.decodeProperties(characteristic));

                    if (characteristic.getPermissions() > 0) {
                        characteristicsMap.putMap("permissions", Helper.decodePermissions(characteristic));
                    }


                    WritableArray descriptorsArray = Arguments.createArray();

                    for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
                        WritableMap descriptorMap = Arguments.createMap();
                        descriptorMap.putString("uuid", UUIDHelper.uuidToString(descriptor.getUuid()));
                        if (descriptor.getValue() != null)
                            descriptorMap.putString("value", Base64.encodeToString(descriptor.getValue(), Base64.NO_WRAP));
                        else
                            descriptorMap.putString("value", null);

                        if (descriptor.getPermissions() > 0) {
                            descriptorMap.putMap("permissions", Helper.decodePermissions(descriptor));
                        }
                        descriptorsArray.pushMap(descriptorMap);
                    }
                    if (descriptorsArray.size() > 0) {
                        characteristicsMap.putArray("descriptors", descriptorsArray);
                    }
                    characteristicsArray.pushMap(characteristicsMap);
                }
                servicesArray.pushMap(serviceMap);
            }
            map.putArray("services", servicesArray);
            map.putArray("characteristics", characteristicsArray);
        }

        return map;
    }

    static JSONObject byteArrayToJSON(byte[] bytes) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("CDVType", "ArrayBuffer");
        object.put("data", bytes != null ? Base64.encodeToString(bytes, Base64.NO_WRAP) : null);
        return object;
    }

    static WritableMap byteArrayToWritableMap(byte[] bytes) throws JSONException {
        WritableMap object = Arguments.createMap();
        object.putString("CDVType", "ArrayBuffer");
        object.putString("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
        object.putArray("bytes", BleManager.bytesToWritableArray(bytes));
        return object;
    }

    public boolean isConnected() {
        return connected;
    }

    public BluetoothDevice getDevice() {
        return device;
    }

    public Boolean hasService(UUID uuid) {
        if (gatt == null) {
            return null;
        }
        return gatt.getService(uuid) != null;
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);
        if (retrieveServicesCallback != null) {
            WritableMap map = this.asWritableMap(gatt);
            retrieveServicesCallback.invoke(null, map);
            retrieveServicesCallback = null;
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

        Log.d(LOG_TAG, "onConnectionStateChange from " + status + " to " + newState + " on peripheral:" + device.getAddress());

        this.gatt = gatt;

        if (newState == BluetoothGatt.STATE_CONNECTED) {

            connected = true;

            sendConnectionEvent(device, "BleManagerConnectPeripheral");

            if (connectCallback != null) {
                Log.d(LOG_TAG, "Connected to: " + device.getAddress());
                connectCallback.invoke();
                connectCallback = null;
            }

        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {

            if (connected) {
                connected = false;

                if (gatt != null) {
                    gatt.disconnect();
                    gatt.close();
                    this.gatt = null;
                }
            }

            sendConnectionEvent(device, "BleManagerDisconnectPeripheral");
            List<Callback> callbacks = Arrays.asList(writeCallback, retrieveServicesCallback, readRSSICallback, readCallback, registerNotifyCallback);
            for (Callback currentCallback : callbacks) {
                if (currentCallback != null) {
                    currentCallback.invoke("Device disconnected");
                }
            }
            if (connectCallback != null) {
                connectCallback.invoke("Connection error");
                connectCallback = null;
            }
            writeCallback = null;
            readCallback = null;
            retrieveServicesCallback = null;
            readRSSICallback = null;
            if(registerNotifyCallback != null){
              registerNotifyCallback.invoke("Device disconnected");
              registerNotifyCallback = null;
            }


        }

    }

    public void updateRssi(int rssi) {
        advertisingRSSI = rssi;
    }

    public void updateData(byte[] data) {
        advertisingData = data;
    }

    public int unsignedToBytes(byte b) {
        return b & 0xFF;
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);

        byte[] dataValue = characteristic.getValue();
        Log.d(LOG_TAG, "Read: " + BleManager.bytesToHex(dataValue) + " from peripheral: " + device.getAddress());

        WritableMap map = Arguments.createMap();
        map.putString("peripheral", device.getAddress());
        map.putString("characteristic", characteristic.getUuid().toString());
        map.putString("service", characteristic.getService().getUuid().toString());
        map.putArray("value", BleManager.bytesToWritableArray(dataValue));
        sendEvent("BleManagerDidUpdateValueForCharacteristic", map);
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);
        Log.d(LOG_TAG, "onCharacteristicRead " + characteristic);

        if (readCallback != null) {

            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] dataValue = characteristic.getValue();

                if (readCallback != null) {
                    readCallback.invoke(null, BleManager.bytesToWritableArray(dataValue));
                }
            } else {
                readCallback.invoke("Error reading " + characteristic.getUuid() + " status=" + status, null);
            }

            readCallback = null;

        }

        commandCompleted();
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicWrite(gatt, characteristic, status);

        if (writeCallback != null) {

            if (writeQueue.size() > 0) {
                byte[] data = writeQueue.get(0);
                writeQueue.remove(0);
                doWrite(characteristic, data);
            } else {

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeCallback.invoke();
                } else {
                    Log.e(LOG_TAG, "Error onCharacteristicWrite:" + status);
                    writeCallback.invoke("Error writing status: " + status);
                }

                writeCallback = null;
            }
        } else {
            Log.e(LOG_TAG, "No callback on write");
        }
        commandCompleted();
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        super.onDescriptorWrite(gatt, descriptor, status);
        if (registerNotifyCallback != null) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                registerNotifyCallback.invoke();
            } else {
                registerNotifyCallback.invoke("Error writing descriptor stats=" + status, null);
            }

            registerNotifyCallback = null;
        }
        commandCompleted();
    }

    @Override
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
        super.onReadRemoteRssi(gatt, rssi, status);
        if (readRSSICallback != null) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                updateRssi(rssi);
                readRSSICallback.invoke(null, rssi);
            } else {
                readRSSICallback.invoke("Error reading RSSI status=" + status, null);
            }

            readRSSICallback = null;
        }
        commandCompleted();
    }

    private void throwIfDisconnectedOrGattUnavailable() throws Exception {
      if (!isConnected()) {
          throw new Exception("Device is not connected");
      }
      if (gatt == null) {
          throw new Exception("BluetoothGatt is null");
      }
    }

    private void setNotify(UUID serviceUUID, UUID characteristicUUID, Boolean notify, Callback callback) throws Exception {
        throwIfDisconnectedOrGattUnavailable();

        BluetoothGattService service = gatt.getService(serviceUUID);
        BluetoothGattCharacteristic characteristic = findNotifyCharacteristic(service, characteristicUUID);

        if (characteristic == null) {
          throw new CallbackException("Characteristic " + characteristicUUID + " not found");
        }

        if (gatt.setCharacteristicNotification(characteristic, notify)) {

            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUIDHelper.uuidFromString(CHARACTERISTIC_NOTIFICATION_CONFIG));
            if (descriptor != null) {

                // Prefer notify over indicate
                if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    Log.d(LOG_TAG, "Characteristic " + characteristicUUID + " set NOTIFY");
                    descriptor.setValue(notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                } else if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    Log.d(LOG_TAG, "Characteristic " + characteristicUUID + " set INDICATE");
                    descriptor.setValue(notify ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                } else {
                    Log.d(LOG_TAG, "Characteristic " + characteristicUUID + " does not have NOTIFY or INDICATE property set");
                }

                if (gatt.writeDescriptor(descriptor)) {
                    Log.d(LOG_TAG, "setNotify complete");
                    registerNotifyCallback = callback;
                } else {
                    throw new CallbackException("Failed to set client characteristic notification for " + characteristicUUID);
                }


            } else {
                throw new CallbackException("Set notification failed for " + characteristicUUID);
            }

        } else {
            throw new CallbackException("Failed to register notification for " + characteristicUUID);
        }

    }

    public void registerNotify(UUID serviceUUID, UUID characteristicUUID, Callback callback) throws Exception {
        Log.d(LOG_TAG, "registerNotify");
        this.setNotify(serviceUUID, characteristicUUID, true, callback);
        commandCompleted();
    }

    public void removeNotify(UUID serviceUUID, UUID characteristicUUID, Callback callback) throws Exception {
        Log.d(LOG_TAG, "removeNotify");
        this.setNotify(serviceUUID, characteristicUUID, false, callback);
        commandCompleted();
    }

    // Some devices reuse UUIDs across characteristics, so we can't use service.getCharacteristic(characteristicUUID)
    // instead check the UUID and properties for each characteristic in the service until we find the best match
    // This function prefers Notify over Indicate
    private BluetoothGattCharacteristic findNotifyCharacteristic(BluetoothGattService service, UUID characteristicUUID) {
        BluetoothGattCharacteristic characteristic = null;

        try {
            // Check for Notify first
            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            for (BluetoothGattCharacteristic c : characteristics) {
                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 && characteristicUUID.equals(c.getUuid())) {
                    characteristic = c;
                    break;
                }
            }

            if (characteristic != null) return characteristic;

            // If there wasn't Notify Characteristic, check for Indicate
            for (BluetoothGattCharacteristic c : characteristics) {
                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 && characteristicUUID.equals(c.getUuid())) {
                    characteristic = c;
                    break;
                }
            }

            // As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
            if (characteristic == null) {
                characteristic = service.getCharacteristic(characteristicUUID);
            }

            return characteristic;
        } catch (Exception e) {
            Log.e(LOG_TAG, "Errore su caratteristica " + characteristicUUID, e);
            return null;
        }
    }

    public void read(UUID serviceUUID, UUID characteristicUUID, Callback callback) throws Exception {
        Log.d(LOG_TAG, "@@ Doing a read");
        throwIfDisconnectedOrGattUnavailable();
        Log.d(LOG_TAG, "@@ Doing a read 0");

        BluetoothGattService service = gatt.getService(serviceUUID);
        BluetoothGattCharacteristic characteristic = findReadableCharacteristic(service, characteristicUUID);

        if (characteristic == null) {
            throw new CallbackException("Characteristic " + characteristicUUID + " not found.");
        }
        readCallback = callback;
        if (!gatt.readCharacteristic(characteristic)) {
            readCallback = null;
            throw new CallbackException("Read failed");
        }

        commandCompleted();
    }

    public void readRSSI(Callback callback) throws Exception {
        throwIfDisconnectedOrGattUnavailable();
        readRSSICallback = callback;

        if (!gatt.readRemoteRssi()) {
            readCallback = null;
            throw new CallbackException("Read RSSI failed");
        }
        commandCompleted();
    }

    public void retrieveServices(Callback callback) throws Exception {
        throwIfDisconnectedOrGattUnavailable();
        this.retrieveServicesCallback = callback;

        if (Build.VERSION.SDK_INT >= LOLLIPOP) {
            gatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH);
        }
        gatt.discoverServices();
    }


    // Some peripherals re-use UUIDs for multiple characteristics so we need to check the properties
    // and UUID of all characteristics instead of using service.getCharacteristic(characteristicUUID)
    private BluetoothGattCharacteristic findReadableCharacteristic(BluetoothGattService service, UUID characteristicUUID) {
        BluetoothGattCharacteristic characteristic = null;

        if (service != null) {
            int read = BluetoothGattCharacteristic.PROPERTY_READ;

            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            for (BluetoothGattCharacteristic c : characteristics) {
                if ((c.getProperties() & read) != 0 && characteristicUUID.equals(c.getUuid())) {
                    characteristic = c;
                    break;
                }
            }

            // As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
            if (characteristic == null) {
                characteristic = service.getCharacteristic(characteristicUUID);
            }
        }

        return characteristic;
    }


    public boolean doWrite(BluetoothGattCharacteristic characteristic, byte[] data) {
        characteristic.setValue(data);

        if (!gatt.writeCharacteristic(characteristic)) {
            Log.d(LOG_TAG, "Error on doWrite");
            return false;
        }
        return true;
    }



    public void write(UUID serviceUUID, UUID characteristicUUID, byte[] data, Integer maxByteSize, Integer queueSleepTime, Callback callback, int writeType) throws Exception {
        throwIfDisconnectedOrGattUnavailable();

        BluetoothGattService service = gatt.getService(serviceUUID);
        BluetoothGattCharacteristic characteristic = findWritableCharacteristic(service, characteristicUUID, writeType);

        if (characteristic == null) {
            throw new CallbackException("Characteristic " + characteristicUUID + " not found.");
        }

        characteristic.setWriteType(writeType);

        if (writeQueue.size() > 0) {
            throw new CallbackException("You have already an queued message");
        }

        if (writeCallback != null ) {
            throw new CallbackException("You're already writing");
        }

        if (BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT == writeType) {
            writeCallback = callback;
        }

        if (data.length > maxByteSize) {
            // Split into messages of a size below maxByteSize
            int dataLength = data.length;
            int count = 0;
            byte[] firstMessage = null;
            List<byte[]> splittedMessage = new ArrayList<>();

            while (count < dataLength && (dataLength - count > maxByteSize)) {
                if (count == 0) {
                    firstMessage = Arrays.copyOfRange(data, count, count + maxByteSize);
                } else {
                    byte[] splitMessage = Arrays.copyOfRange(data, count, count + maxByteSize);
                    splittedMessage.add(splitMessage);
                }
                count += maxByteSize;
            }
            if (count < dataLength) {
                // Other bytes in queue
                byte[] splitMessage = Arrays.copyOfRange(data, count, data.length);
                splittedMessage.add(splitMessage);
            }

            if (BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT == writeType) {
                writeQueue.addAll(splittedMessage);
                if (!doWrite(characteristic, firstMessage)) {
                    writeQueue.clear();
                    writeCallback = null;
                    throw new CallbackException("Write failed - first of queued split messages - WRITE_TYPE_DEFAULT");
                }
            } else {
                // InterruptedException might happen here, it will get bubbled up to the callback
                boolean writeError = false;
                if (!doWrite(characteristic, firstMessage)) {
                    writeError = true;
                    throw new CallbackException("Write failed - first of queued split messages - !WRITE_TYPE_DEFAULT");
                }
                if (!writeError) {
                    Thread.sleep(queueSleepTime);
                    for (byte[] message : splittedMessage) {
                        if (!doWrite(characteristic, message)) {
                            writeError = true;
                            throw new CallbackException("Write failed - while attempting to write remaining split messages");
                        }
                        Thread.sleep(queueSleepTime);
                    }
                    if (!writeError)
                        callback.invoke();
                }

            }
        } else {
            if (doWrite(characteristic, data)) {
                Log.d(LOG_TAG, "Write completed");
                if (BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE == writeType) {
                    callback.invoke();
                }
            } else {
                writeCallback = null;
                throw new CallbackException("Write failed - single message");
            }
        }
        commandCompleted();
    }

    // Some peripherals re-use UUIDs for multiple characteristics so we need to check the properties
    // and UUID of all characteristics instead of using service.getCharacteristic(characteristicUUID)
    private BluetoothGattCharacteristic findWritableCharacteristic(BluetoothGattService service, UUID characteristicUUID, int writeType) {
        try {
            BluetoothGattCharacteristic characteristic = null;

            // get write property
            int writeProperty = BluetoothGattCharacteristic.PROPERTY_WRITE;
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                writeProperty = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
            }

            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            for (BluetoothGattCharacteristic c : characteristics) {
                if ((c.getProperties() & writeProperty) != 0 && characteristicUUID.equals(c.getUuid())) {
                    characteristic = c;
                    break;
                }
            }

            // As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
            if (characteristic == null) {
                characteristic = service.getCharacteristic(characteristicUUID);
            }

            return characteristic;
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error on findWritableCharacteristic", e);
            return null;
        }
    }

    // New queue logic
    public void queueRead(UUID serviceUUID, UUID characteristicUUID, Callback callback) {
        BLECommand command = new BLECommand(serviceUUID, characteristicUUID, null, null, callback, BLECommand.READ);
        queueCommand(command);
    }

    public void queueWrite(UUID serviceUUID, UUID characteristicUUID, byte[] data, Integer maxByteSize, Integer queueSleepTime, Callback callback, int writeType) {
        BLECommand command = new BLECommand(serviceUUID, characteristicUUID, data, maxByteSize, queueSleepTime, callback, writeType);
        queueCommand(command);
    }

    public void queueRegisterNotifyCallback(UUID serviceUUID, UUID characteristicUUID, Callback callback) {
        BLECommand command = new BLECommand(serviceUUID, characteristicUUID, null, null, callback, BLECommand.REGISTER_NOTIFY);
        queueCommand(command);
        Log.d(LOG_TAG, "queueRegisterNotifyCallback finished");
    }

    public void queueRemoveNotifyCallback(UUID serviceUUID, UUID characteristicUUID, Callback callback) {
        BLECommand command = new BLECommand(serviceUUID, characteristicUUID, null, null, callback, BLECommand.REMOVE_NOTIFY);
        queueCommand(command);
    }

    public void queueReadRSSI(Callback callback) {
        BLECommand command = new BLECommand(null, null, null, null, callback, BLECommand.READ_RSSI);
        queueCommand(command);
    }

    private void queueCommand(BLECommand command) {
        commandQueue.add(command);
        if (!bleProcessing) {
          processCommands();
        } else {
          Log.d(LOG_TAG, "Will not process commands, already processing");
        }
    }

    // command finished, queue the next command
    private void commandCompleted() {
        Log.d(LOG_TAG, "Processing Complete");
        bleProcessing = false;
        processCommands();
    }

    // process the queue
    private void processCommands() {
        Log.d(LOG_TAG, "Processing Commands");

        if (bleProcessing) {
            return;
        }

        BLECommand command = commandQueue.poll();

        if (command == null) {
          Log.d(LOG_TAG, "Command Queue is empty.");
          return;
        }

        try {
          bleProcessing = true;

          int commandType = command.getType();

          if(commandType == BLECommand.READ){
            Log.d(LOG_TAG, "$Read " + command.getCharacteristicUUID());
            read(command.getServiceUUID(), command.getCharacteristicUUID(), command.getCallback());
          }
          else if(commandType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT){
              Log.d(LOG_TAG, "$Write " + command.getCharacteristicUUID());
              write(command.getServiceUUID(), command.getCharacteristicUUID(), command.getData(), command.getMaxByteSize(), command.getQueueSleepTime(), command.getCallback(), command.getType());
          }
          else if(commandType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE){
            Log.d(LOG_TAG, "$Write No Response " + command.getCharacteristicUUID());
            write(command.getServiceUUID(), command.getCharacteristicUUID(), command.getData(), command.getMaxByteSize(), command.getQueueSleepTime(), command.getCallback(), command.getType());
          }
          else if(commandType == BLECommand.REGISTER_NOTIFY){
            Log.d(LOG_TAG, "$Register Notify " + command.getCharacteristicUUID());
            registerNotify(command.getServiceUUID(), command.getCharacteristicUUID(), command.getCallback());
          }
          else if(commandType == BLECommand.REMOVE_NOTIFY){
            Log.d(LOG_TAG, "$Remove Notify " + command.getCharacteristicUUID());
            removeNotify(command.getServiceUUID(), command.getCharacteristicUUID(), command.getCallback());
          }
          else if(commandType == BLECommand.READ_RSSI){
            Log.d(LOG_TAG, "$Read RSSI");
            readRSSI(command.getCallback());
          }
          else {
            throw new RuntimeException("Unexpected BLE Command type " + command.getType());
          }


        } catch (Exception e) {
          Log.d(LOG_TAG, "Queue command failed");
          Log.e(LOG_TAG, "Queue command exception:", e);
          Callback callback = command.getCallback();
          if(callback != null) {
            callback.invoke("Command failed: " + e.getMessage(), null);
          }
          commandCompleted();
        }

    }

    // End new queue logic

    private String generateHashKey(BluetoothGattCharacteristic characteristic) {
        return generateHashKey(characteristic.getService().getUuid(), characteristic);
    }

    private String generateHashKey(UUID serviceUUID, BluetoothGattCharacteristic characteristic) {
        return String.valueOf(serviceUUID) + "|" + characteristic.getUuid() + "|" + characteristic.getInstanceId();
    }

}
