package com.creatorskit.capture;

import com.creatorskit.models.CustomModelComp;
import com.creatorskit.models.CustomModelType;
import com.creatorskit.saves.CharacterSave;
import com.creatorskit.saves.SetupSave;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-free helpers for scene resolve requests and capture timing.
 *
 * <p>Slot indexes mirror {@code KitType} declaration order (HEAD=0 through
 * JAW=11). Slots with no visual composition entry (ring, ammo) have no
 * index and are skipped by the resolver.
 */
public final class SceneResolver
{
	/** Composition offset added to an item id, mirroring PlayerComposition.ITEM_OFFSET. */
	public static final int ITEM_OFFSET = 512;

	public static final int IDX_HEAD = 0;
	public static final int IDX_CAPE = 1;
	public static final int IDX_AMULET = 2;
	public static final int IDX_WEAPON = 3;
	public static final int IDX_TORSO = 4;
	public static final int IDX_SHIELD = 5;
	public static final int IDX_LEGS = 7;
	public static final int IDX_HANDS = 9;
	public static final int IDX_BOOTS = 10;

	private static final Map<String, Integer> SLOT_TO_INDEX = new HashMap<>();

	static
	{
		SLOT_TO_INDEX.put("head", IDX_HEAD);
		SLOT_TO_INDEX.put("helm", IDX_HEAD);
		SLOT_TO_INDEX.put("cape", IDX_CAPE);
		SLOT_TO_INDEX.put("neck", IDX_AMULET);
		SLOT_TO_INDEX.put("amulet", IDX_AMULET);
		SLOT_TO_INDEX.put("main_hand", IDX_WEAPON);
		SLOT_TO_INDEX.put("weapon", IDX_WEAPON);
		SLOT_TO_INDEX.put("chest", IDX_TORSO);
		SLOT_TO_INDEX.put("torso", IDX_TORSO);
		SLOT_TO_INDEX.put("off_hand", IDX_SHIELD);
		SLOT_TO_INDEX.put("shield", IDX_SHIELD);
		SLOT_TO_INDEX.put("legs", IDX_LEGS);
		SLOT_TO_INDEX.put("hands", IDX_HANDS);
		SLOT_TO_INDEX.put("gloves", IDX_HANDS);
		SLOT_TO_INDEX.put("feet", IDX_BOOTS);
		SLOT_TO_INDEX.put("boots", IDX_BOOTS);
	}

	private SceneResolver()
	{
	}

	/**
	 * Maps an equipment slot name to its composition index, or null when the
	 * slot has no visual entry (ring, ammo) or is unknown.
	 */
	public static Integer slotToKitIndex(String slot)
	{
		if (slot == null)
		{
			return null;
		}
		if (slot.equalsIgnoreCase("ring") || slot.equalsIgnoreCase("ammo"))
		{
			return null;
		}
		return SLOT_TO_INDEX.get(slot.trim().toLowerCase());
	}

	/**
	 * Builds the composition equipment array from a base (usually the live
	 * local player composition) with the requested item ids applied.
	 *
	 * @param base       base equipment ids, cloned before use
	 * @param slots      requested slot name to item id
	 * @param itemOffset composition item offset (512 on the client)
	 * @return the merged array; entries for slots with no visual index are skipped
	 */
	public static int[] buildEquipmentArray(int[] base, Map<String, Integer> slots, int itemOffset)
	{
		int[] merged = base.clone();
		if (slots == null)
		{
			return merged;
		}
		for (Map.Entry<String, Integer> e : slots.entrySet())
		{
			Integer idx = slotToKitIndex(e.getKey());
			if (idx == null || idx < 0 || idx >= merged.length)
			{
				continue;
			}
			Integer itemId = e.getValue();
			if (itemId == null || itemId <= 0)
			{
				continue;
			}
			merged[idx] = itemOffset + itemId;
		}
		return merged;
	}

	/** Indexes of comps carrying an explicit resolve request. */
	public static List<Integer> unresolvedIndices(CustomModelComp[] comps)
	{
		List<Integer> out = new ArrayList<>();
		if (comps == null)
		{
			return out;
		}
		for (int i = 0; i < comps.length; i++)
		{
			if (comps[i] != null && comps[i].isResolveRequested())
			{
				out.add(i);
			}
		}
		return out;
	}

	/**
	 * Whether a comp still blocks playback: an explicit request is present,
	 * or a player/NPC comp that needs one still has no geometry.
	 */
	public static boolean blocksPlayback(CustomModelComp comp)
	{
		if (comp == null)
		{
			return false;
		}
		if (comp.isResolveRequested())
		{
			return true;
		}
		if (comp.getModelStats() == null
			&& (comp.getType() == CustomModelType.CACHE_PLAYER
			|| comp.getType() == CustomModelType.CACHE_NPC))
		{
			return true;
		}
		return false;
	}

	/**
	 * Re-bases one authored tile onto the player in a shared coordinate
	 * space (template space inside instances, plain tiles otherwise):
	 * {@code player + (authored - anchor) + (dx, dy, dz)}. All three inputs
	 * must use the same space; each is {x, y, plane}.
	 */
	public static int[] stageTile(
		int[] authored, int[] anchor, int[] player, int dx, int dy, int dz)
	{
		return new int[]{
			player[0] + (authored[0] - anchor[0]) + dx,
			player[1] + (authored[1] - anchor[1]) + dy,
			player[2] + (authored[2] - anchor[2]) + dz,
		};
	}

	/** Human-readable one-line summary of why a comp cannot play. */
	public static String describeBlocker(int index, CustomModelComp comp)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("comp ").append(index);
		if (comp.getType() != null)
		{
			sb.append(" (").append(comp.getType()).append(")");
		}
		if (comp.isResolveRequested())
		{
			sb.append(" still carries needs_resolve=true");
			if (comp.getType() == CustomModelType.CACHE_NPC)
			{
				sb.append("; npc_id=").append(comp.getNpcId());
			}
			else if (comp.getType() == CustomModelType.CACHE_PLAYER)
			{
				sb.append("; equipment_slots=").append(comp.getEquipmentSlots());
			}
		}
		else
		{
			sb.append(" has no model geometry");
		}
		return sb.toString();
	}

	/**
	 * Scans every keyframe track of a setup for the maximum tick, for
	 * deriving a batch end time when none is configured.
	 */
	public static double maxTick(SetupSave save)
	{
		double max = 0.0;
		if (save == null || save.getMasterFolderNode() == null
			|| save.getMasterFolderNode().getCharacterSaves() == null)
		{
			return max;
		}
		for (CharacterSave ch : save.getMasterFolderNode().getCharacterSaves())
		{
			if (ch == null)
			{
				continue;
			}
			max = Math.max(max, maxTickOf(ch.getAnimationKeyFrames()));
			max = Math.max(max, maxTickOf(ch.getMovementKeyFrames()));
			max = Math.max(max, maxTickOf(ch.getSpawnKeyFrames()));
			max = Math.max(max, maxTickOf(ch.getModelKeyFrameSaves()));
			max = Math.max(max, maxTickOf(ch.getOrientationKeyFrames()));
			max = Math.max(max, maxTickOf(ch.getTextKeyFrames()));
			max = Math.max(max, maxTickOf(ch.getOverheadKeyFrames()));
			max = Math.max(max, maxTickOf(ch.getHealthKeyFrames()));
			if (ch.getSpotanimKeyFrames() != null)
			{
				for (Object[] track : ch.getSpotanimKeyFrames())
				{
					max = Math.max(max, maxTickOf(track));
				}
			}
			if (ch.getHitsplatKeyFrames() != null)
			{
				for (Object[] track : ch.getHitsplatKeyFrames())
				{
					max = Math.max(max, maxTickOf(track));
				}
			}
		}
		return max;
	}

	private static double maxTickOf(Object[] frames)
	{
		double max = 0.0;
		if (frames == null)
		{
			return max;
		}
		for (Object o : frames)
		{
			if (o instanceof com.creatorskit.swing.timesheet.keyframe.KeyFrame)
			{
				com.creatorskit.swing.timesheet.keyframe.KeyFrame kf =
					(com.creatorskit.swing.timesheet.keyframe.KeyFrame) o;
				if (Double.isFinite(kf.getTick()))
				{
					max = Math.max(max, kf.getTick());
				}
			}
		}
		return max;
	}
}
