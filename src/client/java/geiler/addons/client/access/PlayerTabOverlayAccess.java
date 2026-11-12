package geiler.addons.client.access;

import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.List;

/** Runtime bridge for vanilla's sorted tab-list accessor. */
public interface PlayerTabOverlayAccess {
	List<PlayerInfo> geileraddons$getPlayerInfos();
}
