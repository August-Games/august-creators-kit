package com.creatorskit.capture;

import com.creatorskit.models.CustomModelComp;
import com.creatorskit.models.CustomModelType;
import com.creatorskit.saves.CharacterSave;
import com.creatorskit.saves.SetupSave;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
	 * or a player/NPC comp still has no geometry (null or empty stats).
	 * Applies to every comp, requested or not.
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
		if (comp.getType() == CustomModelType.CACHE_PLAYER
			|| comp.getType() == CustomModelType.CACHE_NPC)
		{
			return comp.getModelStats() == null || comp.getModelStats().length == 0;
		}
		return false;
	}

	/**
	 * Requested equipment slots that can never resolve: unknown slot names.
	 * {@code ring}/{@code ammo} have no visual entry and stay no-ops; ids
	 * of zero or less keep their current skip behaviour and are not listed.
	 */
	public static List<String> badEquipmentSlots(Map<String, Integer> slots)
	{
		List<String> bad = new ArrayList<>();
		if (slots == null)
		{
			return bad;
		}
		for (String name : slots.keySet())
		{
			if (name == null)
			{
				bad.add(null);
				continue;
			}
			String lower = name.trim().toLowerCase();
			if (lower.equals("ring") || lower.equals("ammo"))
			{
				continue;
			}
			if (slotToKitIndex(name) == null)
			{
				bad.add(name);
			}
		}
		return bad;
	}

	/**
	 * Requested positive item ids absent from the known item database.
	 * Pure so it stays unit-testable; the caller supplies the ids.
	 */
	public static List<Integer> unknownItemIds(
		Map<String, Integer> slots, java.util.Collection<Integer> knownIds)
	{
		List<Integer> unknown = new ArrayList<>();
		if (slots == null)
		{
			return unknown;
		}
		for (Integer id : slots.values())
		{
			if (id == null || id <= 0)
			{
				continue;
			}
			if (knownIds == null || !knownIds.contains(id))
			{
				unknown.add(id);
			}
		}
		return unknown;
	}

	/**
	 * Ident-kit wearpos slots (HEAD, JAW, TORSO, ARMS, HANDS, LEGS, FEET).
	 * A kit renders only when its wearpos holds no worn item and no worn
	 * item hides it. Mirrors the in-game player-appearance composition,
	 * which drops kits from the worn (id, wearPos2, wearPos3) triples:
	 * e.g. a full helm hides the hair and jaw kits, a platebody hides
	 * the arms kit. Without this, base body kits render straight through
	 * covering equipment.
	 */
	public static final int[] IDENT_KIT_WEARPOS = {8, 11, 4, 6, 9, 7, 10};

	/**
	 * Wearpos hidden by worn items' secondary/tertiary cover, from each
	 * worn equipment index's {wearPos1, wearPos2, wearPos3} triple (read
	 * from the cache item definitions).
	 * Pure so it stays unit-testable.
	 *
	 * @param wearposBySlot equipment index to {wearPos1, wearPos2, wearPos3}
	 * @param size equipment slot count (wearpos outside [0, size) are ignored)
	 */
	public static boolean[] computeHiddenWearpos(Map<Integer, int[]> wearposBySlot, int size)
	{
		boolean[] hidden = new boolean[Math.max(0, size)];
		if (wearposBySlot == null)
		{
			return hidden;
		}
		for (int[] triple : wearposBySlot.values())
		{
			if (triple == null || triple.length < 3)
			{
				continue;
			}
			for (int k = 1; k <= 2; k++)
			{
				int w = triple[k];
				if (w >= 0 && w < hidden.length)
				{
					hidden[w] = true;
				}
			}
		}
		return hidden;
	}

	/**
	 * Drops identity kits covered by worn items, in place: a kit is dropped
	 * when its wearpos is hidden (see {@link #computeHiddenWearpos}) or
	 * occupied by a worn item (e.g. a platebody replaces the torso kit).
	 * Only the seven ident-kit wearpos are touched; weapon/shield/head
	 * slots keep their current behaviour.
	 *
	 * @param kitShortList kit id per equipment index (-1 = none), mutated
	 * @param itemShortList worn item id per equipment index (-1 = none)
	 * @param hidden hidden wearpos from {@link #computeHiddenWearpos}
	 */
	public static void dropCoveredKits(int[] kitShortList, int[] itemShortList, boolean[] hidden)
	{
		if (kitShortList == null || itemShortList == null || hidden == null)
		{
			return;
		}
		for (int w : IDENT_KIT_WEARPOS)
		{
			if (w < 0 || w >= kitShortList.length || w >= itemShortList.length || w >= hidden.length)
			{
				continue;
			}
			if (hidden[w] || itemShortList[w] != -1)
			{
				kitShortList[w] = -1;
			}
		}
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
	 * Every character in a setup, descending into nested folders. Native
	 * saves keep characters beneath folder nodes, so root-only scans miss
	 * nested actors for both duration and staging.
	 */
	public static List<CharacterSave> allCharacterSaves(SetupSave save)
	{
		List<CharacterSave> out = new ArrayList<>();
		if (save == null)
		{
			return out;
		}
		collectCharacters(save.getMasterFolderNode(), out);
		return out;
	}

	private static void collectCharacters(
		com.creatorskit.saves.FolderNodeSave node, List<CharacterSave> out)
	{
		if (node == null)
		{
			return;
		}
		if (node.getCharacterSaves() != null)
		{
			for (CharacterSave ch : node.getCharacterSaves())
			{
				if (ch != null)
				{
					out.add(ch);
				}
			}
		}
		if (node.getFolderSaves() != null)
		{
			for (com.creatorskit.saves.FolderNodeSave child : node.getFolderSaves())
			{
				collectCharacters(child, out);
			}
		}
	}

	/**
	 * Scans every keyframe track of a setup for the maximum tick, for
	 * deriving a batch end time when none is configured. Covers nested
	 * folders and camera tracks.
	 */
	public static double maxTick(SetupSave save)
	{
		double max = 0.0;
		if (save == null)
		{
			return max;
		}
		for (CharacterSave ch : allCharacterSaves(save))
		{
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
		if (save.getCameraScriptSaves() != null)
		{
			for (com.creatorskit.saves.CameraScriptSave cam : save.getCameraScriptSaves())
			{
				if (cam != null && Double.isFinite(cam.getTick()))
				{
					max = Math.max(max, cam.getTick());
				}
			}
		}
		return max;
	}

	/**
	 * Removes one job's owned outputs so a rerun or a refused job never
	 * inherits stale frames or terminal markers. Best effort per file.
	 */
	public static void clearOwnedOutputs(File dir)
	{
		if (dir == null || !dir.isDirectory())
		{
			return;
		}
		File[] files = dir.listFiles();
		if (files == null)
		{
			return;
		}
		for (File f : files)
		{
			String n = f.getName();
			if (f.isFile() && (n.startsWith("frame_") && n.endsWith(".png")
				|| n.equals("capture.json") || n.equals("resolved_scene.json")
				|| n.equals("DONE") || n.equals("ERROR")
				|| n.endsWith(".done") || n.endsWith(".error")
				|| n.endsWith(".tmp")))
			{
				try
				{
					Files.deleteIfExists(f.toPath());
				}
				catch (Exception ignored)
				{
				}
			}
		}
	}

	/**
	 * Atomically claims a daemon request: moves it into the processing
	 * directory first, so a request is never executed twice and a
	 * replacement dropped mid-capture is not archived unexecuted.
	 *
	 * @return the claimed file inside the processing directory
	 */
	public static File claimRequest(File req, File processingDir) throws Exception
	{
		processingDir.mkdirs();
		File claimed = new File(processingDir, req.getName());
		try
		{
			Files.move(req.toPath(), claimed.toPath(),
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		}
		catch (Exception atomicFailed)
		{
			Files.move(req.toPath(), claimed.toPath(),
				StandardCopyOption.REPLACE_EXISTING);
		}
		return claimed;
	}

	/** Writes bytes atomically (temp + move) so readers never see halves. */
	public static void atomicWrite(File target, byte[] bytes) throws Exception
	{
		File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
		Files.write(tmp.toPath(), bytes);
		try
		{
			Files.move(tmp.toPath(), target.toPath(),
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		}
		catch (Exception atomicFailed)
		{
			Files.move(tmp.toPath(), target.toPath(),
				StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Terminal error result for jobs that never reach the normal writer
	 * (bad requests, validation failures): a Gson-built
	 * {@code capture.json} plus the {@code ERROR} marker, both atomic.
	 */
	public static void writeErrorResult(
		Gson gson, File outDir, String scene, String mode,
		double fps, boolean cropViewport, String reason) throws Exception
	{
		outDir.mkdirs();
		JsonObject root = new JsonObject();
		root.addProperty("status", "error");
		root.addProperty("mode", mode);
		root.addProperty("scene", scene == null ? "" : scene);
		root.addProperty("fps", fps);
		root.addProperty("crop_viewport", cropViewport);
		root.addProperty("error", reason == null ? "" : reason);
		root.add("frames", new JsonArray());
		atomicWrite(new File(outDir, "capture.json"),
			gson.toJson(root).getBytes(StandardCharsets.UTF_8));
		atomicWrite(new File(outDir, "ERROR"),
			((reason == null ? "" : reason) + "\n").getBytes(StandardCharsets.UTF_8));
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
