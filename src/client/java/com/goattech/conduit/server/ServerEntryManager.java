package com.goattech.conduit.server;

import com.goattech.conduit.ConduitMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

import java.util.UUID;

/**
 * Manages the temporary entry Conduit adds into the user's Multiplayer server list so they
 * can click-to-connect with one button. We tag our entry by name so we can reliably remove
 * it when the tunnel is torn down, even if Minecraft restarts with it still in servers.dat.
 */
public final class ServerEntryManager {

	private static final String CONDUIT_TAG = " (Conduit)";

	private volatile String activeName;

	public synchronized void addOrUpdate(String worldName, String address) {
		ServerList list = new ServerList(Minecraft.getInstance());
		list.load();
		String name = worldName + CONDUIT_TAG;
		// Remove existing conduit entries for this world.
		for (int i = list.size() - 1; i >= 0; i--) {
			ServerData d = list.get(i);
			if (d != null && d.name != null && d.name.endsWith(CONDUIT_TAG)
					&& (d.name.equals(name) || d.ip.equals(address))) {
				list.remove(d);
			}
		}
		ServerData entry = new ServerData(name, address, ServerData.Type.OTHER);
		list.add(entry, false);
		list.save();
		this.activeName = name;
		ConduitMod.LOGGER.info("[Conduit] Added server entry '{}' -> {}", name, address);
	}

	public synchronized void removeActive() {
		if (activeName == null) return;
		ServerList list = new ServerList(Minecraft.getInstance());
		list.load();
		for (int i = list.size() - 1; i >= 0; i--) {
			ServerData d = list.get(i);
			if (d != null && activeName.equals(d.name)) {
				list.remove(d);
			}
		}
		list.save();
		ConduitMod.LOGGER.info("[Conduit] Removed server entry '{}'", activeName);
		this.activeName = null;
	}

	/** Called during client shutdown to make sure we don't leave stale entries behind. */
	public synchronized void restoreSnapshotIfAny() {
		if (activeName != null) removeActive();
	}
}
