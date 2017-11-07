package it.innove;

import java.util.UUID;
import com.facebook.react.bridge.Callback;

class BLECommand {
	// Types
	public static int READ = 10000;
	public static int REGISTER_NOTIFY = 10001;
	public static int REMOVE_NOTIFY = 10002;
	public static int READ_RSSI = 10003;
	// BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
	// BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

	private Callback callback;
	private UUID serviceUUID;
	private UUID characteristicUUID;
	private byte[] data;
	private int type;
	private Integer queueSleepTime;
	private Integer maxByteSize;


	public BLECommand(UUID serviceUUID, UUID characteristicUUID, Integer maxByteSize, Integer queueSleepTime, Callback callback, int type) {
		this.callback = callback;
		this.serviceUUID = serviceUUID;
		this.characteristicUUID = characteristicUUID;
		this.maxByteSize = maxByteSize;
		this.queueSleepTime = queueSleepTime;
		this.type = type;
	}

	public BLECommand(UUID serviceUUID, UUID characteristicUUID, byte[] data, Integer maxByteSize, Integer queueSleepTime, Callback callback, int type) {
		this.callback = callback;
		this.serviceUUID = serviceUUID;
		this.characteristicUUID = characteristicUUID;
		this.maxByteSize = maxByteSize;
		this.queueSleepTime = queueSleepTime;
		this.data = data;
		this.type = type;
	}

	public Callback getCallback() {
		return callback;
	}

	public int getType() {
		return type;
	}

	public UUID getServiceUUID() {
		return serviceUUID;
	}

	public UUID getCharacteristicUUID() {
		return characteristicUUID;
	}

	public byte[] getData() {
		return data;
	}

	public Integer getQueueSleepTime() {
		return queueSleepTime;
	}

	public Integer getMaxByteSize() {
		return maxByteSize;
	}
}
