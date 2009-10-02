/*   
 *   Remuco - A remote control system for media players.
 *   Copyright (C) 2006-2009 Oben Sonne <obensonne@googlemail.com>
 *
 *   This file is part of Remuco.
 *
 *   Remuco is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Remuco is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Remuco.  If not, see <http://www.gnu.org/licenses/>.
 *   
 */
package remuco.comm;

import java.util.Hashtable;

import javax.bluetooth.BluetoothStateException;
import javax.bluetooth.DataElement;
import javax.bluetooth.DeviceClass;
import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.DiscoveryListener;
import javax.bluetooth.LocalDevice;
import javax.bluetooth.RemoteDevice;
import javax.bluetooth.ServiceRecord;
import javax.bluetooth.UUID;

import remuco.UserException;
import remuco.util.Log;

// TODO: Service finder classes can make use of final instance variables 
// because they are used only once per instances now

public final class BluetoothServiceFinder implements DiscoveryListener,
		IServiceFinder {

	private static class BTD extends RemoteDevice {

		public BTD(String addr) {
			super(addr);
		}

	}

	/** Container for service search related data. */
	private static class Search {

		public final int id;
		public final boolean failsafe;
		public final IServiceListener listener;
		public final Hashtable services;

		public Search(int id, boolean failsafe, IServiceListener listener) {
			this.id = id;
			this.failsafe = failsafe;
			this.listener = listener;
			this.services = new Hashtable();
		}

	}

	/** Service name in default language. */
	private static final int ATTRIBUTE_NAME = 0x0100;

	private static final int ATTRIBUTE_LIST[] = new int[] { ATTRIBUTE_NAME };

	private static final String DEFAULT_SERVICE_NAME = "NoName";

	private static final int SECURITY = ServiceRecord.NOAUTHENTICATE_NOENCRYPT;

	/** Remuco service UUID */
	private final static String UUID = "025fe2ae07624bed90f2d8d778f020fe";

	private static final UUID[] UUID_LIST = new UUID[] { new UUID(UUID, false) };

	/** UUID list with a short UUID for SPP services. */
	private static final UUID[] UUID_LIST_SHORT = new UUID[] { new UUID(0x1101) };

	/**
	 * Get the name of a service (the player behind the remuco service).
	 * 
	 * @param sr
	 *            the service to get the name from
	 * @return the name or {@value #DEFAULT_SERVICE_NAME} if name is not
	 *         available;
	 */
	private static String getServiceName(ServiceRecord sr) {

		DataElement de = sr.getAttributeValue(ATTRIBUTE_NAME);

		if (de == null) {
			Log.ln("failed to get service name");
			return DEFAULT_SERVICE_NAME;
		}

		try {
			return (String) de.getValue();
		} catch (ClassCastException e) {
			Log.ln("failed to get service name", e);
			return DEFAULT_SERVICE_NAME;
		}

		// TODO: return null on error and then assign name as in failsafe mode

	}

	private DiscoveryAgent agent = null;

	private LocalDevice localDevice = null;

	/**
	 * Lock to synchronize service search state control. Concurrent threads
	 * which have to be synchronized are the discovery agent's thread, the UI
	 * event thread and the global timer thread.
	 */
	private final Object lock;

	private Search search = null;

	public BluetoothServiceFinder() {

		lock = new Object();

	}

	public void cancelServiceSearch() {

		synchronized (lock) {
			if (search != null) {
				agent.cancelServiceSearch(search.id);
				search = null;
			}
		}
	}

	public void deviceDiscovered(RemoteDevice btDevice, DeviceClass cod) {
		Log.bug("Mar 18, 2009.0:16:23 AM");
	}

	public void findServices(Device device, IServiceListener listener)
			throws UserException {

		synchronized (lock) {

			if (search != null)
				return;

			// TODO: handle manual port setting (fake service search)

			initBluetooth();

			final BluetoothDevice bd = (BluetoothDevice) device;

			final UUID uuidList[];
			final int attrList[];
			final boolean failsafe;

			failsafe = bd.getSearch() == BluetoothDevice.SEARCH_FAILSAFE;

			if (failsafe) {
				uuidList = UUID_LIST_SHORT;
				attrList = null;
			} else {
				uuidList = UUID_LIST;
				attrList = ATTRIBUTE_LIST;
			}

			final BTD btd = new BTD(bd.getAddress());

			final int sid;
			try {
				sid = agent.searchServices(attrList, uuidList, btd, this);
			} catch (BluetoothStateException e) {
				Log.ln("[BT] BluetoothStateException", e);
				throw new UserException("Bluetooth Error",
						"Bluetooth seems to be busy. Cannot search for Remuco "
								+ "services right now.");
			} catch (NullPointerException e) {
				// WTK emulator throws this if there are no services
				return;
			}

			search = new Search(sid, failsafe, listener);
		}
	}

	public void inquiryCompleted(int discType) {
		Log.bug("Mar 18, 2009.0:15:49 AM");
	}

	public void servicesDiscovered(int transId, ServiceRecord[] srs) {

		synchronized (lock) {

			if (search == null) {
				return;
			}

			for (int i = 0; i < srs.length; i++) {

				final String url = srs[i].getConnectionURL(SECURITY, false);

				final String name;
				if (search.failsafe) {
					name = "Player " + i;
				} else {
					name = getServiceName(srs[i]);
				}

				search.services.put(name, url); // assuming names are unique
			}
		}
	}

	public void serviceSearchCompleted(int transID, final int respCode) {

		synchronized (lock) {

			if (search == null) {
				return; // canceled
			}

			Log.ln("[BT] service search finished (" + respCode + ")");

			switch (respCode) {

			case SERVICE_SEARCH_NO_RECORDS:

				search.listener.notifyServices(null, new UserException(
						"No Service",
						"Found no Remuco service on the selected device"));
				break;

			case SERVICE_SEARCH_ERROR:

				search.listener.notifyServices(null, new UserException(
						"Bluetooth Error",
						"Search for a Remuco service on the selected device "
								+ "failed."));
				break;

			case SERVICE_SEARCH_TERMINATED:

				// nothing to do
				break;

			case SERVICE_SEARCH_DEVICE_NOT_REACHABLE:

				search.listener.notifyServices(null, new UserException(
						"Bluetooth Error", "Selected device is not reachable."));
				break;

			case SERVICE_SEARCH_COMPLETED:

				search.listener.notifyServices(search.services, null);
				break;

			default:

				search.listener.notifyServices(search.services, null);
				break;

			}

			search = null;
		}
	}

	private void initBluetooth() throws UserException {

		if (localDevice == null) {
			try {
				localDevice = LocalDevice.getLocalDevice();

			} catch (BluetoothStateException e) {

				Log.ln("[BT] failed to get local device", e);
				throw new UserException("Bluetooth Error",
						"Bluetooth seems to be off.");
			}
		}

		if (agent == null) {
			agent = localDevice.getDiscoveryAgent();
		}

	}

}
