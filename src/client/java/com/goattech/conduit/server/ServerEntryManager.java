package com.goattech.conduit.server;

import com.goattech.conduit.ConduitMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

/**
 * Manages the temporary server-list entry Conduit injects into the Multiplayer screen
 * so the user can click-to-connect.
 *
 * <p>Entries are tagged with a {@code " (Conduit)"} suffix so they can be reliably
 * identified and removed when the tunnel is torn down or the client shuts down.
 */
public final class ServerEntryManager {

	private static final String CONDUIT_TAG = " (Conduit)";

	/** Name of the currently active entry (if any). */
	private volatile String activeName;

	/**
	 * Adds or updates a Conduit server-list entry for the given world/address pair.
	 * Any previous Conduit entry for the same world or address is removed first.
	 */
	public synchronized void addOrUpdate(String worldName, String address) {
		ServerList list = new ServerList(Minecraft.getInstance());
		list.load();
		String name = worldName + CONDUIT_TAG;

		// Remove any stale conduit entries for this world or address.
		for (int i = list.size() - 1; i >= 0; i--) {
			ServerData entry = list.get(i);
			if (entry == null || entry.name == null) continue;
			if (entry.name.endsWith(CONDUIT_TAG)
					&& (entry.name.equals(name) || entry.ip.equals(address))) {
				list.remove(entry);
			}
		}

		ServerData newEntry = new ServerData(name, address, ServerData.Type.OTHER);
		list.add(newEntry, false);
		list.save();
		activeName = name;
		ConduitMod.LOGGER.info("Added server entry '{}' -> {}", name, address);
	}

	/** Removes the currently-active Conduit entry from the server list. */
	public synchronized void removeActive() {
		if (activeName == null) return;

		ServerList list = new ServerList(Minecraft.getInstance());
		list.load();
		for (int i = list.size() - 1; i >= 0; i--) {
			ServerData entry = list.get(i);
			if (entry != null && activeName.equals(entry.name)) {
				list.remove(entry);
			}
		}
		list.save();
		ConduitMod.LOGGER.info("Removed server entry '{}'", activeName);
		activeName = null;
	}

	/** Called during client shutdown to ensure no stale entries persist. */
	public synchronized void restoreSnapshotIfAny() {
		if (activeName != null) {
			removeActive();
		}
	}
}
