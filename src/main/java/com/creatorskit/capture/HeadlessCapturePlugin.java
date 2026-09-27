package com.creatorskit.capture;

import com.creatorskit.CreatorsPlugin;
import com.creatorskit.models.CustomModelComp;
import com.creatorskit.models.CustomModelType;
import com.creatorskit.models.DataFinder;
import com.creatorskit.models.ModelStats;
import com.creatorskit.models.datatypes.NpcDefinition;
import com.creatorskit.saves.CharacterSave;
import com.creatorskit.saves.ModelKeyFrameSave;
import com.creatorskit.saves.SetupSave;
import com.creatorskit.swing.timesheet.keyframe.subtypes.AnimationKeyFrame;
import com.creatorskit.swing.timesheet.keyframe.subtypes.MovementKeyFrame;
import com.google.inject.Inject;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.api.Perspective;
import net.runelite.api.PlayerComposition;
import net.runelite.api.ScriptID;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.DrawManager;

/**
 * Generic headless capture mode for the kit.
 *
 * <p>Driven entirely by {@code ck.capture.*} system properties (see
 * {@code docs/headless-capture.md}). In batch/stills mode the plugin resolves
 * any scene resolve requests against the live cache, plays the scene back
 * with paused seeks, captures one frame per completed draw, writes
 * {@code frame_%05d.png} plus {@code capture.json} and a completion sentinel,
 * then exits the JVM. In daemon mode it watches a request directory and
 * processes scenes without restarting.
 *
 * <p>When no capture properties are set the plugin stays idle, so interactive
 * use is unaffected.
 */
@PluginDescriptor(
	name = "Headless Capture",
	description = "System-property driven headless scene capture (batch, stills, daemon)"
)
@PluginDependency(CreatorsPlugin.class)
public class HeadlessCapturePlugin extends Plugin
{
	private static final org.slf4j.Logger log =
		org.slf4j.LoggerFactory.getLogger(HeadlessCapturePlugin.class);

	private static final int[] DEFAULT_KIT_RECOLOURS = {2, 26, 9, 0, 0};

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private DrawManager drawManager;
	@Inject private CreatorsPlugin creators;
	@Inject private PluginManager pluginManager;
	@Inject private RenderCallbackManager renderCallbackManager;

	private volatile Thread worker;

	@Override
	protected void startUp()
	{
		CaptureOptions options;
		try
		{
			options = CaptureOptions.fromSystemProperties();
		}
		catch (IllegalArgumentException e)
		{
			log.error("Headless capture: bad options: {}", e.getMessage());
			return;
		}
		if (!options.isEnabled())
		{
			return;
		}
		log.warn("Headless capture starting in {} mode", options.mode);
		if (worker != null && worker.isAlive())
		{
			log.warn("Headless capture worker still running; not starting another");
			return;
		}
		worker = new Thread(() -> runCapture(options), "headless-capture");
		worker.setDaemon(true);
		worker.start();
	}

	@Override
	protected void shutDown()
	{
		Thread w = worker;
		worker = null;
		if (w != null && w.isAlive())
		{
			w.interrupt();
			try
			{
				// Bounded: never block the EDT indefinitely on a worker
				// that may itself be awaiting the EDT.
				w.join(3000);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		log.warn("Headless capture: game state -> {}", e.getGameState());
	}

	private void runCapture(CaptureOptions options)
	{
		try
		{
			if (options.mode == CaptureOptions.Mode.DAEMON)
			{
				runDaemon(options);
				return;
			}
			int code = runJob(options.scene, options.out, options, null);
			System.exit(code);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			log.warn("Headless capture worker interrupted; stopping quietly");
		}
		catch (Exception e)
		{
			if (worker == null || Thread.currentThread().isInterrupted())
			{
				Thread.currentThread().interrupt();
				log.warn("Headless capture stopped during shutdown; not exiting");
				return;
			}
			log.error("Headless capture failed", e);
			System.exit(1);
		}
	}

	// Daemon mode: poll the request directory for scene jobs.
	private void runDaemon(CaptureOptions options) throws Exception
	{
		File dir = new File(options.requestDir);
		File handled = new File(dir, "handled");
		handled.mkdirs();
		log.warn("Headless capture daemon watching {}", dir);
		while (!Thread.currentThread().isInterrupted())
		{
			if (new File(dir, "stop").exists())
			{
				log.warn("Headless capture daemon saw stop file, exiting loop");
				return;
			}
			File[] requests = dir.listFiles(
				(f) -> f.isFile() && f.getName().endsWith(".request.json"));
			if (requests != null)
			{
				java.util.Arrays.sort(requests);
				for (File req : requests)
				{
					handleDaemonRequest(req, handled, options);
				}
			}
			Thread.sleep(2000);
		}
	}

	private void handleDaemonRequest(File req, File handled, CaptureOptions daemon)
	{
		String base = req.getName().replaceFirst("\\.request\\.json$", "");
		File dir = req.getParentFile();
		File processing = new File(dir, "processing");
		File claimed;
		try
		{
			claimed = SceneResolver.claimRequest(req, processing);
		}
		catch (Exception e)
		{
			log.warn("Headless capture could not claim {}; skipping", req.getName());
			return;
		}
		String outPath = null;
		try
		{
			String json = new String(Files.readAllBytes(claimed.toPath()), StandardCharsets.UTF_8);
			@SuppressWarnings("unchecked")
			Map<String, Object> map = creators.getGson().fromJson(json, Map.class);
			if (map == null)
			{
				throw new IllegalArgumentException("request is not a JSON object");
			}
			Object outRaw = map.get("out");
			outPath = outRaw == null ? null : outRaw.toString();
			// Seed from the daemon's startup properties so JVM -D flags act
			// as defaults; request keys override per job. Nothing here
			// mutates JVM state, so jobs cannot leak framing into each other.
			Properties props = new Properties();
			for (String key : new String[]{
				"ck.capture.fps", "ck.capture.start", "ck.capture.end",
				"ck.capture.settleMs", "ck.capture.cropViewport",
				"ck.capture.stageOnPlayer", "ck.capture.stageOffset",
				"ck.capture.aimCamera", "ck.capture.pitch",
				"ck.capture.zoom", "ck.capture.canvas",
				"ck.capture.dumpWidgets"})
			{
				String sys = System.getProperty(key);
				if (sys != null)
				{
					props.setProperty(key, sys);
				}
			}
			putIfPresent(props, "ck.capture.scene", map.get("scene"));
			putIfPresent(props, "ck.capture.out", map.get("out"));
			putIfPresent(props, "ck.capture.fps", map.get("fps"));
			putIfPresent(props, "ck.capture.start", map.get("start"));
			putIfPresent(props, "ck.capture.end", map.get("end"));
			putIfPresent(props, "ck.capture.settleMs", map.get("settleMs"));
			putIfPresent(props, "ck.capture.stageOnPlayer", map.get("stageOnPlayer"));
			putIfPresent(props, "ck.capture.stageOffset", map.get("stageOffset"));
			putIfPresent(props, "ck.capture.aimCamera", map.get("aimCamera"));
			putIfPresent(props, "ck.capture.pitch", map.get("pitch"));
			putIfPresent(props, "ck.capture.zoom", map.get("zoom"));
			putIfPresent(props, "ck.capture.canvas", map.get("canvas"));
			putIfPresent(props, "ck.capture.dumpWidgets", map.get("dumpWidgets"));
			if (map.get("times") instanceof List)
			{
				StringBuilder sb = new StringBuilder();
				for (Object t : (List<?>) map.get("times"))
				{
					if (sb.length() > 0)
					{
						sb.append(',');
					}
					sb.append(t);
				}
				props.setProperty("ck.capture.times", sb.toString());
				props.setProperty("ck.capture.mode", "stills");
			}
			else
			{
				props.setProperty("ck.capture.mode", "batch");
			}
			Object crop = map.get("cropViewport");
			props.setProperty("ck.capture.cropViewport",
				crop == null ? Boolean.toString(daemon.cropViewport) : crop.toString());
			CaptureOptions job = CaptureOptions.fromProperties(props);
			int code = runJob(job.scene, job.out, job, null);
			writeSentinel(new File(job.out), base, code == 0, code == 0 ? "ok" : "job failed");
			log.warn("Headless capture daemon finished {} -> {}", req.getName(),
				code == 0 ? "ok" : "error");
		}
		catch (Exception e)
		{
			if (e instanceof InterruptedException || Thread.currentThread().isInterrupted())
			{
				Thread.currentThread().interrupt();
				log.warn("Headless capture daemon interrupted; stopping");
				return;
			}
			log.error("Headless capture daemon request {} failed", req.getName(), e);
			writeDaemonError(dir, base, outPath, e.toString());
		}
		finally
		{
			try
			{
				Files.move(claimed.toPath(), new File(handled, claimed.getName()).toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			catch (Exception e)
			{
				log.error("Headless capture could not archive {}", claimed.getName(), e);
			}
		}
	}

	/**
	 * Terminal error for a daemon request that never reached the normal
	 * writer: into the job output dir when one is known, otherwise a
	 * {@code <name>.error} file beside the request. Never throws.
	 */
	private void writeDaemonError(File dir, String base, String outPath, String reason)
	{
		try
		{
			if (outPath != null && !outPath.trim().isEmpty())
			{
				File out = new File(outPath);
				SceneResolver.writeErrorResult(creators.getGson(), out,
					null, "batch", 30.0, false, reason);
				writeSentinel(out, base, false, reason);
				return;
			}
		}
		catch (Exception inner)
		{
			log.error("Headless capture could not write job error result", inner);
		}
		try
		{
			Files.write(new File(dir, base + ".error").toPath(),
				(reason + "\n").getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception inner)
		{
			log.error("Headless capture could not write request error file", inner);
		}
	}

	private static void putIfPresent(Properties props, String key, Object v)
	{
		if (v != null)
		{
			props.setProperty(key, v.toString());
		}
	}

	/**
	 * Runs one capture job. Returns the process exit code the caller should
	 * use (0 ok, non-zero on any failure, including refused scenes).
	 */
	private int runJob(String scenePath, String outPath, CaptureOptions options, double[] forceTimes)
	{
		try
		{
			// The login screen may already be gone (late plugin start, or a
			// daemon job after the first login): only wait for it when the
			// client is still pre-login.
			GameState state = client.getGameState();
			if (state != GameState.LOGGING_IN
				&& state != GameState.LOADING
				&& state != GameState.LOGGED_IN)
			{
				waitFor(GameState.LOGIN_SCREEN, 300_000, "login screen");
			}
			else
			{
				log.warn("Headless capture already past the login screen ({}), "
					+ "skipping screen wait", state);
			}
			if (!waitFor(GameState.LOGGED_IN, 600_000, "login"))
			{
				return fail(outPath, options, "timed out waiting for login");
			}
			reloadCatalogs();
			waitCatalog();

			SetupSave save = readScene(scenePath);
			if (save == null || save.getComps() == null)
			{
				return fail(outPath, options, "could not parse scene: " + scenePath);
			}

			// Best-effort resolution first; the gate below examines EVERY comp
			// (requested or not), so geometry-less comps can never slip through.
			resolveComps(save);
			defaultNpcIdlePoses(save);
			defaultNpcSizes(save);
			List<String> blockers = new ArrayList<>();
			CustomModelComp[] comps = save.getComps();
			if (comps != null)
			{
				for (int i = 0; i < comps.length; i++)
				{
					if (SceneResolver.blocksPlayback(comps[i]))
					{
						blockers.add(SceneResolver.describeBlocker(i, comps[i]));
					}
				}
			}
			if (!blockers.isEmpty())
			{
				return fail(outPath, options,
					"refusing scene with unresolved resolve requests: " + blockers);
			}

			double[] times = forceTimes;
			if (times == null)
			{
				if (options.mode == CaptureOptions.Mode.STILLS && options.stillTimes != null)
				{
					times = options.stillTimes.clone();
				}
				else
				{
					double start = options.effectiveStartSec();
					double maxTick = SceneResolver.maxTick(save);
					double end = options.effectiveEndSec(maxTick * CaptureOptions.SEC_PER_TICK);
					times = CaptureOptions.frameTimesForRange(start, end, options.fps);
				}
			}

			File out = new File(outPath);
			out.mkdirs();
			// Fresh outputs per job: a rerun or refusal must never inherit
			// stale frames or terminal markers.
			SceneResolver.clearOwnedOutputs(out);
			// Stage first: the resolved file must match what actually plays,
			// or the actors end up at the authored tiles while the camera
			// follows the capturing player elsewhere.
			int staged = stageOnPlayer(save, options);
			File resolved = new File(out, "resolved_scene.json");
			Files.write(resolved.toPath(),
				creators.getGson().toJson(save).getBytes(StandardCharsets.UTF_8));

			resetScene();
			loadSceneFile(resolved);
			ensureCanvasSize(options);
			if (options.dumpWidgets)
			{
				dumpWidgets();
			}
			cameraAimed = false;
			parkMouseOffCanvas();
			quietClientForCapture();

			List<String> spawned = spawnedCharacterNames();
			log.warn("Headless capture spawned {} characters: {}",
				spawned.size(), spawned);
			reportSpawnHealth();
			applyNpcCentreOffsets(save);
			// A load that silently creates nothing must fail loudly, not
			// write a successful empty capture.
			if (staged > 0 && spawned.size() < staged)
			{
				return fail(outPath, options, "scene loaded " + spawned.size()
					+ " of " + staged + " staged characters");
			}

			List<FrameMeta> frames = captureAll(save, times, out, options);
			if (frames == null)
			{
				return fail(outPath, options, "capture produced no frames");
			}
			writeCaptureJson(out, options, scenePath, frames);
			writeSentinel(out, null, true,
				frames.size() + " frames from " + scenePath);
			log.warn("Headless capture done: {} frames in {}", frames.size(), out);
			return 0;
		}
		catch (Exception e)
		{
			log.error("Headless capture job failed", e);
			try
			{
				fail(outPath, options, e.toString());
			}
			catch (Exception ignored)
			{
			}
			return 1;
		}
	}

	private int fail(String outPath, CaptureOptions options, String message)
	{
		log.error("Headless capture refusing/failing: {}", message);
		try
		{
			if (outPath != null)
			{
				File out = new File(outPath);
				out.mkdirs();
				writeCaptureJson(out, options, options.scene, new ArrayList<>(), message);
				writeSentinel(out, null, false, message);
			}
		}
		catch (Exception e)
		{
			log.error("Headless capture could not write failure files", e);
		}
		return 1;
	}

	private boolean waitFor(GameState want, long ms, String what) throws Exception
	{
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end)
		{
			if (client.getGameState() == want)
			{
				return true;
			}
			Thread.sleep(500);
		}
		log.error("Headless capture timed out waiting for {}", what);
		return false;
	}

	/**
	 * The catalog is parsed once at plugin enable, which on a fast headless
	 * boot can land before the cache index syncs: lists come back empty while
	 * still reporting loaded. Re-parse now that the login screen is up.
	 */
	private void reloadCatalogs() throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				creators.getDataFinder().reloadData();
			}
			finally
			{
				latch.countDown();
			}
		});
		if (!latch.await(300, TimeUnit.SECONDS))
		{
			throw new IllegalStateException("catalog reload timed out");
		}
	}

	private void waitCatalog() throws Exception
	{
		DataFinder finder = creators.getDataFinder();
		long end = System.currentTimeMillis() + 240_000;
		while (System.currentTimeMillis() < end
			&& !(finder.isDataLoaded(DataFinder.DataType.ITEM)
			&& finder.isDataLoaded(DataFinder.DataType.KIT)
			&& finder.isDataLoaded(DataFinder.DataType.NPC)))
		{
			Thread.sleep(1000);
		}
		log.warn("Headless capture catalog ITEM={} KIT={} NPC={} SPOTANIM={} OBJECT={}",
			finder.isDataLoaded(DataFinder.DataType.ITEM),
			finder.isDataLoaded(DataFinder.DataType.KIT),
			finder.isDataLoaded(DataFinder.DataType.NPC),
			finder.isDataLoaded(DataFinder.DataType.SPOTANIM),
			finder.isDataLoaded(DataFinder.DataType.OBJECT));
	}

	private SetupSave readScene(String scenePath) throws Exception
	{
		File f = new File(scenePath);
		String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
		return creators.getGson().fromJson(json, SetupSave.class);
	}

	/**
	 * Gives every NPC character its definition's stand/walk/run pose set:
	 * without it the pose channel is empty wherever no action anim plays
	 * (before the first action keyframe, between actions, or for actors
	 * with no action at all) and the character renders the bind pose
	 * (T-pose). Only fills pose slots the scene left at -1, and ensures a
	 * tick-0 pose keyframe exists so ticks before the first authored
	 * action are covered too. Authored action (active) animations are
	 * never touched.
	 */
	private void defaultNpcIdlePoses(SetupSave save)
	{
		CustomModelComp[] comps = save.getComps();
		if (comps == null)
		{
			return;
		}
		DataFinder dataFinder = creators.getDataFinder();
		if (dataFinder == null)
		{
			return;
		}
		for (CharacterSave ch : SceneResolver.allCharacterSaves(save))
		{
			if (ch == null || ch.getCompId() < 0 || ch.getCompId() >= comps.length)
			{
				continue;
			}
			CustomModelComp comp = comps[ch.getCompId()];
			if (comp == null || comp.getType() != CustomModelType.CACHE_NPC || comp.getNpcId() == null)
			{
				continue;
			}
			NpcDefinition def = dataFinder.findNpcDefinition(comp.getNpcId());
			if (def == null || def.getStandingAnimation() == -1)
			{
				continue;
			}
			AnimationKeyFrame[] kfs = ch.getAnimationKeyFrames();
			if (kfs == null || kfs.length == 0)
			{
				ch.setAnimationKeyFrames(new AnimationKeyFrame[]{
					npcPoseKeyFrame(0.0, def)});
				log.warn("Headless capture defaulted {} to stand anim {}",
					ch.getName(), def.getStandingAnimation());
				continue;
			}
			boolean patched = false;
			double earliest = Double.MAX_VALUE;
			for (AnimationKeyFrame kf : kfs)
			{
				if (kf == null)
				{
					continue;
				}
				earliest = Math.min(earliest, kf.getTick());
				patched |= fillNpcPoseSlot(kf, def);
			}
			if (earliest > 0.0)
			{
				AnimationKeyFrame[] grown = new AnimationKeyFrame[kfs.length + 1];
				grown[0] = npcPoseKeyFrame(0.0, def);
				System.arraycopy(kfs, 0, grown, 1, kfs.length);
				ch.setAnimationKeyFrames(grown);
				patched = true;
			}
			if (patched)
			{
				log.warn("Headless capture defaulted {} to stand anim {}",
					ch.getName(), def.getStandingAnimation());
			}
		}
	}

	/**
	 * Whole-tile part of the footprint-centre shift for a size-N NPC.
	 * The model is centred (N-1)/2 tiles NE of its SW anchor; the save
	 * (tile ints) carries the floored part, the live character the exact
	 * local-unit remainder via {@link #npcCentreRemainderLocal}.
	 */
	static int npcCentreShiftTiles(int size)
	{
		return (size - 1) / 2;
	}

	/**
	 * Exact local-unit remainder (1/128th tile) of the footprint-centre
	 * shift after {@link #npcCentreShiftTiles}: (N-1)*64 minus the shifted
	 * whole tiles back in local units. Zero for odd sizes; 64 (half tile)
	 * for size 2, etc.
	 */
	static int npcCentreRemainderLocal(int size)
	{
		return (size - 1) * 64 - npcCentreShiftTiles(size) * 128;
	}

	/**
	 * Stamps one NPC character save with its cache size: object radius
	 * (culling/clickbox for the correctly placed big model) and the
	 * whole-tile footprint-centre shift on the staged anchor and movement
	 * path. Pure save mutation (unit-tested); the sub-tile remainder rides
	 * the live character via {@link #applyNpcCentreOffsets}.
	 */
	static void applyNpcSize(CharacterSave ch, int size)
	{
		ch.setRadius(size);
		int shift = npcCentreShiftTiles(size);
		if (shift != 0)
		{
			WorldPoint p = ch.getNonInstancedPoint();
			if (p != null)
			{
				ch.setNonInstancedPoint(
					new WorldPoint(p.getX() + shift, p.getY() + shift,
						p.getPlane()));
			}
			MovementKeyFrame[] moves = ch.getMovementKeyFrames();
			if (moves != null)
			{
				for (MovementKeyFrame mkf : moves)
				{
					if (mkf == null || mkf.getPath() == null)
					{
						continue;
					}
					for (int[] step : mkf.getPath())
					{
						if (step != null && step.length >= 2)
						{
							step[0] += shift;
							step[1] += shift;
						}
					}
				}
			}
		}
		ModelKeyFrameSave[] models = ch.getModelKeyFrameSaves();
		if (models != null)
		{
			ModelKeyFrameSave[] grown = new ModelKeyFrameSave[models.length];
			for (int i = 0; i < models.length; i++)
			{
				ModelKeyFrameSave kf = models[i];
				grown[i] = kf == null ? null : new ModelKeyFrameSave(
					kf.getTick(), kf.isUseCustomModel(), kf.getModelId(),
					kf.getCustomModel(), size);
			}
			ch.setModelKeyFrameSaves(grown);
		}
	}

	/**
	 * Gives every NPC character its cache footprint: radius + whole-tile
	 * centre shift on the save (see {@link #applyNpcSize}). A size-N NPC
	 * occupies NxN tiles from its SW anchor with the model centred on the
	 * footprint middle; the kit stages models at the anchor tile, so
	 * without this e.g. size-7 Vorkath renders 3 tiles SW and spills onto
	 * the neighbouring player tile. Size-1 NPCs are untouched.
	 */
	private void defaultNpcSizes(SetupSave save)
	{
		CustomModelComp[] comps = save.getComps();
		if (comps == null)
		{
			return;
		}
		DataFinder dataFinder = creators.getDataFinder();
		if (dataFinder == null)
		{
			return;
		}
		for (CharacterSave ch : SceneResolver.allCharacterSaves(save))
		{
			if (ch == null || ch.getCompId() < 0 || ch.getCompId() >= comps.length)
			{
				continue;
			}
			CustomModelComp comp = comps[ch.getCompId()];
			if (comp == null || comp.getType() != CustomModelType.CACHE_NPC || comp.getNpcId() == null)
			{
				continue;
			}
			NpcDefinition def = dataFinder.findNpcDefinition(comp.getNpcId());
			if (def == null || def.getSize() <= 1)
			{
				continue;
			}
			applyNpcSize(ch, def.getSize());
			log.warn("Headless capture sized {} to {} (centre shift {} tile(s))",
				ch.getName(), def.getSize(), npcCentreShiftTiles(def.getSize()));
		}
	}

	/**
	 * Sets the exact sub-tile footprint-centre remainder on each spawned
	 * NPC character (see {@link #npcCentreRemainderLocal}); the save-level
	 * whole-tile shift in {@link #defaultNpcSizes} cannot represent the
	 * half tile of even sizes. Matched by character name; missing live
	 * characters fail the spawn census above, so silence here is safe.
	 */
	private void applyNpcCentreOffsets(SetupSave save)
	{
		CustomModelComp[] comps = save.getComps();
		if (comps == null)
		{
			return;
		}
		DataFinder dataFinder = creators.getDataFinder();
		if (dataFinder == null)
		{
			return;
		}
		for (com.creatorskit.Character ch : creators.getCharacters())
		{
			if (ch == null || ch.getName() == null)
			{
				continue;
			}
			for (CharacterSave csv : SceneResolver.allCharacterSaves(save))
			{
				if (csv == null || !ch.getName().equals(csv.getName()))
				{
					continue;
				}
				if (csv.getCompId() < 0 || csv.getCompId() >= comps.length)
				{
					break;
				}
				CustomModelComp comp = comps[csv.getCompId()];
				if (comp == null || comp.getType() != CustomModelType.CACHE_NPC || comp.getNpcId() == null)
				{
					break;
				}
				NpcDefinition def = dataFinder.findNpcDefinition(comp.getNpcId());
				if (def == null || def.getSize() <= 1)
				{
					break;
				}
				int remainder = npcCentreRemainderLocal(def.getSize());
				ch.setNpcCentreOffsetLocal(remainder);
				log.warn("Headless capture centred {} (size {}): remainder {} local units",
					ch.getName(), def.getSize(), remainder);
				break;
			}
		}
	}

	/** Pose-only keyframe (no action): the NPC's definition anim set. */
	private static AnimationKeyFrame npcPoseKeyFrame(double tick, NpcDefinition def)
	{
		return new AnimationKeyFrame(
			tick, false, -1, 0, false, false,
			def.getStandingAnimation(),
			def.getWalkingAnimation(),
			def.getRunAnimation(),
			def.getRotate180Animation(),
			def.getRotateRightAnimation(),
			def.getRotateLeftAnimation(),
			def.getIdleRotateRightAnimation(),
			def.getIdleRotateLeftAnimation());
	}

	/**
	 * Fills pose slots the scene left at -1 from the NPC definition.
	 * Returns true when anything changed.
	 */
	private static boolean fillNpcPoseSlot(AnimationKeyFrame kf, NpcDefinition def)
	{
		boolean changed = false;
		if (kf.getIdle() == -1 && def.getStandingAnimation() != -1)
		{
			kf.setIdle(def.getStandingAnimation());
			changed = true;
		}
		if (kf.getWalk() == -1 && def.getWalkingAnimation() != -1)
		{
			kf.setWalk(def.getWalkingAnimation());
			changed = true;
		}
		if (kf.getRun() == -1 && def.getRunAnimation() != -1)
		{
			kf.setRun(def.getRunAnimation());
			changed = true;
		}
		if (kf.getWalk180() == -1 && def.getRotate180Animation() != -1)
		{
			kf.setWalk180(def.getRotate180Animation());
			changed = true;
		}
		if (kf.getWalkRight() == -1 && def.getRotateRightAnimation() != -1)
		{
			kf.setWalkRight(def.getRotateRightAnimation());
			changed = true;
		}
		if (kf.getWalkLeft() == -1 && def.getRotateLeftAnimation() != -1)
		{
			kf.setWalkLeft(def.getRotateLeftAnimation());
			changed = true;
		}
		if (kf.getIdleRight() == -1 && def.getIdleRotateRightAnimation() != -1)
		{
			kf.setIdleRight(def.getIdleRotateRightAnimation());
			changed = true;
		}
		if (kf.getIdleLeft() == -1 && def.getIdleRotateLeftAnimation() != -1)
		{
			kf.setIdleLeft(def.getIdleRotateLeftAnimation());
			changed = true;
		}
		return changed;
	}

	/**
	 * Resolves every comp carrying a resolve request against the live cache.
	 * Resolution is best effort; the caller gates on every comp afterwards,
	 * so anything still unresolved refuses the scene.
	 */
	private void resolveComps(SetupSave save) throws Exception
	{
		CustomModelComp[] comps = save.getComps();
		List<Integer> pending = SceneResolver.unresolvedIndices(comps);
		if (pending.isEmpty())
		{
			return;
		}
		log.warn("Headless capture resolving {} comps", pending.size());

		int[] base = readPlayerBase();
		int[] colours = readPlayerColours();

		for (int i : pending)
		{
			CustomModelComp comp = comps[i];
			if (comp.getType() == CustomModelType.CACHE_PLAYER)
			{
				if (!resolvePlayer(comp, base, colours))
				{
					log.error("Headless capture could not resolve player comp {}", i);
				}
			}
			else if (comp.getType() == CustomModelType.CACHE_NPC)
			{
				if (!resolveNpc(comp))
				{
					log.error("Headless capture could not resolve NPC comp {} npc_id={}",
						i, comp.getNpcId());
				}
			}
			else
			{
				log.error("Headless capture has no resolver for type {} on comp {}",
					comp.getType(), i);
			}
		}

		for (int i : pending)
		{
			if (SceneResolver.blocksPlayback(comps[i]))
			{
				log.error("Headless capture comp {} still blocked: {}", i,
					SceneResolver.describeBlocker(i, comps[i]));
			}
		}
	}

	private boolean resolvePlayer(CustomModelComp comp, int[] base, int[] colours) throws Exception
	{
		if (base == null)
		{
			return false;
		}
		Map<String, Integer> slots = comp.getEquipmentSlots();
		List<String> badSlots = SceneResolver.badEquipmentSlots(slots);
		if (!badSlots.isEmpty())
		{
			log.error("Headless capture player comp requests unknown slots: {}", badSlots);
			return false;
		}
		int[] equipment = SceneResolver.buildEquipmentArray(
			base, slots, PlayerComposition.ITEM_OFFSET);
		boolean maleItem = !Boolean.TRUE.equals(comp.getFemale());
		AtomicReference<ModelStats[]> ref = new AtomicReference<>();
		AtomicReference<List<Integer>> unknownRef = new AtomicReference<>(new ArrayList<>());
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				ref.set(creators.getDataFinder().findModelsForPlayer(
					false, maleItem, equipment, -1, -1, -1, new int[0]));
				// Per-item tracking: every requested positive id must exist
				// in the item database, or the request stays unresolved.
				if (slots != null)
				{
					List<Integer> unknown = new ArrayList<>();
					for (Integer id : slots.values())
					{
						if (id != null && id > 0
							&& !creators.getDataFinder().hasItemId(id))
						{
							unknown.add(id);
						}
					}
					unknownRef.set(unknown);
				}
			}
			finally
			{
				latch.countDown();
			}
		});
		if (!latch.await(120, TimeUnit.SECONDS))
		{
			return false;
		}
		if (!unknownRef.get().isEmpty())
		{
			log.error("Headless capture player comp requests unknown item ids: {}",
				unknownRef.get());
			return false;
		}
		ModelStats[] stats = ref.get();
		if (stats == null || stats.length == 0)
		{
			return false;
		}
		comp.setModelStats(stats);
		if (comp.getKitRecolours() == null || comp.getKitRecolours().length == 0)
		{
			comp.setKitRecolours(colours != null ? colours.clone() : DEFAULT_KIT_RECOLOURS.clone());
		}
		comp.setNeedsResolve(false);
		return true;
	}

	private boolean resolveNpc(CustomModelComp comp) throws Exception
	{
		if (comp.getNpcId() == null)
		{
			return false;
		}
		final int npcId = comp.getNpcId();
		AtomicReference<Map.Entry<int[], ModelStats[]>> ref = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				ref.set(creators.getDataFinder().findModelsForNPC(npcId));
			}
			finally
			{
				latch.countDown();
			}
		});
		if (!latch.await(120, TimeUnit.SECONDS))
		{
			return false;
		}
		Map.Entry<int[], ModelStats[]> entry = ref.get();
		if (entry == null || entry.getValue() == null || entry.getValue().length == 0)
		{
			return false;
		}
		comp.setModelStats(entry.getValue());
		if (entry.getKey() != null && entry.getKey().length >= 2)
		{
			comp.setWidthScale(entry.getKey()[0]);
			comp.setHeightScale(entry.getKey()[1]);
		}
		comp.setModelId(npcId);
		comp.setNeedsResolve(false);
		return true;
	}

	private int[] readPlayerBase() throws Exception
	{
		AtomicReference<int[]> ref = new AtomicReference<>();
		long end = System.currentTimeMillis() + 90_000;
		while (System.currentTimeMillis() < end && ref.get() == null)
		{
			CountDownLatch latch = new CountDownLatch(1);
			clientThread.invokeLater(() ->
			{
				try
				{
					if (client.getLocalPlayer() != null
						&& client.getLocalPlayer().getPlayerComposition() != null)
					{
						ref.set(client.getLocalPlayer().getPlayerComposition()
							.getEquipmentIds().clone());
					}
				}
				finally
				{
					latch.countDown();
				}
			});
			latch.await(30, TimeUnit.SECONDS);
			if (ref.get() == null)
			{
				Thread.sleep(2000);
			}
		}
		return ref.get();
	}

	private int[] readPlayerColours() throws Exception
	{
		AtomicReference<int[]> ref = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				if (client.getLocalPlayer() != null
					&& client.getLocalPlayer().getPlayerComposition() != null)
				{
					ref.set(client.getLocalPlayer().getPlayerComposition()
						.getColors().clone());
				}
			}
			finally
			{
				latch.countDown();
			}
		});
		latch.await(30, TimeUnit.SECONDS);
		return ref.get();
	}

	/**
	 * Re-bases character spawn tiles around the local player, preserving the
	 * scene's relative layout. Compiled scenes carry absolute tiles; without
	 * this the actors can end up wherever the authoring area was instead of
	 * where the capturing player stands.
	 */
	/**
	 * Re-bases every character in the setup (including nested folders)
	 * around the settled player tile. Returns the number staged.
	 */
	private int stageOnPlayer(SetupSave save, CaptureOptions options) throws Exception
	{
		if (!options.stageOnPlayer)
		{
			return 0;
		}
		int[] dxdy = parseOffset(options.stageOffset);
		int[] pp = readStablePlayerTile();
		if (pp == null)
		{
			log.warn("Headless capture: no local player for staging; using authored tiles");
			return 0;
		}
		// NonInstancedPoint is consumed literally, and template-based points
		// provably do not draw in-instance. Stage at the player's actual
		// tile so objects land in the loaded scene in both the main world
		// and instances.
		int[] base = pp;
		List<CharacterSave> chars = SceneResolver.allCharacterSaves(save);
		WorldPoint anchor = null;
		for (CharacterSave ch : chars)
		{
			if (ch.getNonInstancedPoint() != null)
			{
				anchor = ch.getNonInstancedPoint();
				break;
			}
		}
		if (anchor == null)
		{
			return 0;
		}
		int[] anchorArr = {anchor.getX(), anchor.getY(), anchor.getPlane()};
		int staged = 0;
		for (CharacterSave ch : chars)
		{
			if (ch.getNonInstancedPoint() == null)
			{
				continue;
			}
			WorldPoint p = ch.getNonInstancedPoint();
			int[] moved = SceneResolver.stageTile(
				new int[]{p.getX(), p.getY(), p.getPlane()},
				anchorArr, base, dxdy[0], dxdy[1], 0);
			ch.setNonInstancedPoint(new WorldPoint(moved[0], moved[1], moved[2]));
			staged++;
			log.warn("Headless capture staged {} at {}/{}/{} (player at {}/{}/{})",
				ch.getName(), moved[0], moved[1], moved[2],
				base[0], base[1], base[2]);
		}
		log.warn("Headless capture staged {} characters near {}/{}/{}",
			staged, pp[0], pp[1], pp[2]);
		return staged;
	}

	private static int[] parseOffset(String raw)
	{
		try
		{
			String[] parts = raw.split(",");
			return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
		}
		catch (Exception e)
		{
			return new int[]{2, 0};
		}
	}

	/**
	 * Reads the local player tile once it stops moving. Hosts may teleport
	 * the player after login (spawn staging); capturing the tile mid-teleport
	 * strands the scene where the player used to be. Polls until the tile is
	 * unchanged for three consecutive reads, with a bounded total wait.
	 *
	 * @return {x, y, plane}, or null when no local player appears
	 */
	private int[] readStablePlayerTile() throws Exception
	{
		int[] last = null;
		int stable = 0;
		long end = System.currentTimeMillis() + 90_000;
		while (System.currentTimeMillis() < end)
		{
			int[] cur = readPlayerTile();
			if (cur != null
				&& last != null
				&& cur[0] == last[0] && cur[1] == last[1] && cur[2] == last[2])
			{
				if (++stable >= 3)
				{
					log.warn("Headless capture player tile settled at {}/{}/{}",
						cur[0], cur[1], cur[2]);
					return cur;
				}
			}
			else
			{
				stable = 0;
				if (cur != null
					&& (last == null || cur[0] != last[0] || cur[1] != last[1] || cur[2] != last[2]))
				{
					log.warn("Headless capture player tile now {}/{}/{}",
						cur[0], cur[1], cur[2]);
				}
			}
			last = cur;
			Thread.sleep(2000);
		}
		log.warn("Headless capture player tile never settled; using latest");
		return last;
	}

	private int[] readPlayerTile() throws Exception
	{
		AtomicReference<int[]> ref = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				if (client.getLocalPlayer() != null)
				{
					WorldPoint wp = client.getLocalPlayer().getWorldLocation();
					ref.set(new int[]{wp.getX(), wp.getY(), wp.getPlane()});
				}
			}
			finally
			{
				latch.countDown();
			}
		});
		latch.await(30, TimeUnit.SECONDS);
		return ref.get();
	}

	/** Clears the current setup so a (re)loaded scene starts clean. */
	private void resetScene() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				creators.getCreatorsPanel().getToolBox().getManagerPanel()
					.getManagerTree().removeAllNodes();
				creators.getCreatorsPanel().getToolBox().getModelUtilities()
					.clearCustomModels();
				creators.getCreatorsPanel().getToolBox().getCameraManager()
					.clearKeyFrames();
			}
			catch (Exception e)
			{
				log.error("Headless capture scene reset failed", e);
			}
		});
	}

	private void loadSceneFile(File file) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
			creators.getCreatorsPanel().loadSetup(file, false));
		Thread.sleep(10_000);
	}

	/** Names of the kit characters currently spawned (scene census). */
	private List<String> spawnedCharacterNames()
	{
		List<String> names = new ArrayList<>();
		try
		{
			for (com.creatorskit.Character ch : creators.getCharacters())
			{
				names.add(ch.getName());
			}
		}
		catch (Exception e)
		{
			log.error("Headless capture could not list characters", e);
		}
		return names;
	}

	/**
	 * Spawn health: every character should own a live scene object with a
	 * model after load. A character without one renders nothing; warn loudly
	 * instead of capturing an empty scene.
	 */
	private void reportSpawnHealth() throws Exception
	{
		AtomicReference<List<String>> ref = new AtomicReference<>(new ArrayList<>());
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				ref.get().add("camera xyz=" + client.getCameraX()
					+ "/" + client.getCameraY() + "/" + client.getCameraZ()
					+ " yaw=" + client.getCameraYaw()
					+ " pitch=" + client.getCameraPitch()
					+ " zoomVar=" + client.getVarcIntValue(
						VarClientID.CAMERA_ZOOM_SMALL));
				if (client.getLocalPlayer() != null)
				{
					ref.get().add("player tile="
						+ client.getLocalPlayer().getWorldLocation()
						+ " local=" + client.getLocalPlayer().getLocalLocation());
				}
				for (com.creatorskit.Character ch : creators.getCharacters())
				{
					boolean hasObject = ch.getCkObject() != null;
					boolean hasModel = hasObject && ch.getCkObject().getModel() != null;
					boolean active = hasObject && ch.getCkObject().isActive();
					ref.get().add(ch.getName()
						+ " object=" + hasObject
						+ " model=" + hasModel
						+ " active=" + active
						+ " inScene=" + ch.isInScene()
						+ " tile=" + ch.getNonInstancedPoint()
						+ " mapped=" + (ch.getNonInstancedPoint() == null ? "null"
							: WorldPoint.toLocalInstance(
								client.getTopLevelWorldView(),
								ch.getNonInstancedPoint())));
				}
			}
			catch (Exception e)
			{
				log.error("Headless capture spawn health check failed", e);
			}
			finally
			{
				latch.countDown();
			}
		});
		latch.await(30, TimeUnit.SECONDS);
		for (String line : ref.get())
		{
			log.warn("Headless capture spawn: {}", line);
			if (line.contains("object=false") || line.contains("model=false"))
			{
				log.error("Headless capture character without a rendered model: {}", line);
			}
		}
	}

	/**
	 * Logs the widget tree (group/child, bounds, text) for identifying UI
	 * to hide from captures. Gated behind ck.capture.dumpWidgets.
	 */
	private void dumpWidgets()
	{
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				net.runelite.api.widgets.Widget[] roots = client.getWidgetRoots();
				if (roots != null)
				{
					for (net.runelite.api.widgets.Widget r : roots)
					{
						dumpWidget(r, 0);
					}
				}
			}
			finally
			{
				latch.countDown();
			}
		});
		try
		{
			latch.await(30, TimeUnit.SECONDS);
		}
		catch (Exception e)
		{
			log.warn("Headless capture widget dump interrupted");
		}
	}

	private void dumpWidget(net.runelite.api.widgets.Widget w, int depth)
	{
		if (w == null || depth > 7)
		{
			return;
		}
		int id = w.getId();
		java.awt.Rectangle b = w.getBounds();
		if (b != null && b.width > 0 && b.height > 0 && !w.isHidden())
		{
			String text = w.getText();
			if (text == null)
			{
				text = "";
			}
			if (text.length() > 40)
			{
				text = text.substring(0, 40);
			}
			log.warn("Headless capture widget g={} c={} b={},{},{},{} hidden={} text='{}' name='{}'",
				id >>> 16, id & 0xFFFF, b.x, b.y, b.width, b.height,
				w.isHidden(), text.replace('\n', '|'), w.getName());
		}
		dumpWidgetKids(w.getChildren(), depth);
		try
		{
			dumpWidgetKids(w.getNestedChildren(), depth);
		}
		catch (Exception e)
		{
			// older shapes without nested children
		}
	}

	private void dumpWidgetKids(net.runelite.api.widgets.Widget[] kids, int depth)
	{
		if (kids != null)
		{
			for (net.runelite.api.widgets.Widget k : kids)
			{
				dumpWidget(k, depth + 1);
			}
		}
	}

	/**
	 * Park the pointer off the canvas so hover/menu text never leaks into
	 * frames. Best effort: a missing pointer device must not fail the run.
	 */
	private void parkMouseOffCanvas()
	{
		try
		{
			new java.awt.Robot().mouseMove(2, 2);
			log.warn("Headless capture parked mouse off canvas");
		}
		catch (Exception e)
		{
			log.warn("Headless capture could not park mouse: {}", e.toString());
		}
	}

	/**
	 * With the hardware-accelerated path the completed-draw image handed to
	 * frame listeners is empty, so capture runs on the CPU path. Overlay
	 * plugins that draw helper text (e.g. beginner tooltips) are stopped so
	 * frames contain only the scene.
	 */
	private void quietClientForCapture() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				String name = p.getClass().getSimpleName();
				if (name.equals("GpuPlugin") || name.equals("BeginnerTooltipsPlugin")
					|| name.equals("XpTrackerPlugin"))
				{
					try
					{
						pluginManager.setPluginEnabled(p, false);
						pluginManager.stopPlugin(p);
						log.warn("Headless capture stopped {} for clean capture", name);
					}
					catch (Exception e)
					{
						log.error("Headless capture stop of {} failed", name, e);
					}
				}
			}
		});
		Thread.sleep(5000);
		log.warn("Headless capture canvas now {}x{}", client.getCanvasWidth(),
			client.getCanvasHeight());
	}

	private static class FrameMeta
	{
		final int index;
		final double sceneTime;
		final double tick;
		final int canvasW;
		final int canvasH;
		final int viewportX;
		final int viewportY;
		final int viewportW;
		final int viewportH;
		final String gameState;
		final String file;

		FrameMeta(int index, double sceneTime, double tick, int canvasW, int canvasH,
			int viewportX, int viewportY, int viewportW, int viewportH,
			String gameState, String file)
		{
			this.index = index;
			this.sceneTime = sceneTime;
			this.tick = tick;
			this.canvasW = canvasW;
			this.canvasH = canvasH;
			this.viewportX = viewportX;
			this.viewportY = viewportY;
			this.viewportW = viewportW;
			this.viewportH = viewportH;
			this.gameState = gameState;
			this.file = file;
		}
	}

	private List<FrameMeta> captureAll(
		SetupSave save, double[] times, File out, CaptureOptions options) throws Exception
	{
		List<FrameMeta> frames = new ArrayList<>();
		// Orbit ramp spans the captured frames: the track completes exactly
		// on the final frame, so per-shot orbit ranges tile continuously.
		double rangeStartSec = times.length == 0 ? 0.0 : times[0];
		double rangeEndSec = times.length == 0 ? 0.0 : times[times.length - 1];
		for (int i = 0; i < times.length; i++)
		{
			double sceneTime = times[i];
			double tick = CaptureOptions.secToTick(sceneTime);
			BufferedImage img = seekAndCapture(tick, sceneTime, rangeStartSec, rangeEndSec, options);
			if (img == null)
			{
				log.error("Headless capture: no completed draw for t={} tick={}",
					sceneTime, tick);
				return null;
			}
			String name = String.format("frame_%05d.png", i);
			ImageIO.write(img, "png", new File(out, name));
			frames.add(new FrameMeta(i, sceneTime, tick,
				client.getCanvasWidth(), client.getCanvasHeight(),
				client.getViewportXOffset(), client.getViewportYOffset(),
				client.getViewportWidth(), client.getViewportHeight(),
				String.valueOf(client.getGameState()), name));
			if (i % 30 == 0)
			{
				log.warn("Headless capture {}/{} frames", i, times.length);
			}
		}
		return frames;
	}

	private int lastAimYaw = -1;

	private int lastAimPitch = -1;

	private boolean cameraAimed = false;

	/**
	 * Aim-time focal tile in scene coords (pv2 teaser: view-cone guard).
	 * Set by aimCameraAtActors, read per frame by checkCameraClear so the
	 * whole orbit arc is covered. Integer.MIN_VALUE while unset.
	 */
	private int focalSceneX = Integer.MIN_VALUE;

	private int focalSceneY = Integer.MIN_VALUE;

	/**
	 * View-cone scenery tiles already reported this job (pv2 teaser):
	 * the cone guard runs per frame, so without this the same object
	 * logs hundreds of times per shot.
	 */
	private final Set<Integer> coneSceneryLogged = new HashSet<>();

	/**
	 * Capture-mode camera override: focal point on the staged actors (they
	 * stay centred, the capturing player drops out of frame), yaw facing
	 * them, configured pitch/zoom. Disabled with ck.capture.aimCamera=false.
	 */
	private void aimCameraAtActors(CaptureOptions options) throws Exception
	{
		if (!options.aimCamera)
		{
			return;
		}
		int pitch = options.pitch;
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				if (client.getLocalPlayer() == null)
				{
					return;
				}
				WorldPoint pp = client.getLocalPlayer().getWorldLocation();
				double mx = 0;
				double my = 0;
				int n = 0;
				for (com.creatorskit.Character ch : creators.getCharacters())
				{
					if (ch.getNonInstancedPoint() == null)
					{
						continue;
					}
					mx += ch.getNonInstancedPoint().getX();
					my += ch.getNonInstancedPoint().getY();
					n++;
				}
				if (n == 0)
				{
					return;
				}
				int dx = (int) Math.round(mx / n - pp.getX());
				int dy = (int) Math.round(my / n - pp.getY());
				if (dx == 0 && dy == 0)
				{
					return;
				}
				// Client forward is (-sin yaw, +cos yaw): yaw 0 looks
				// north with the camera south of the focal point. Aim
				// from the bot side (pv2 021): negate dx so the camera
				// sits on the bot side looking outward at the actors,
				// instead of beyond them looking back.
				int yaw = (int) (Math.atan2(-dx, dy) * 325.94932345220167) & 0x7FF;
				client.setCameraYawTarget(yaw);
				client.setCameraPitchTarget(pitch);
				int zoom = options.zoom;
				if (zoom > 0)
				{
					client.runScript(ScriptID.CAMERA_DO_ZOOM, zoom, zoom);
				}
				// Focal setters require free camera mode; without it the
				// focal point stays locked on the capturing player.
				client.setCameraMode(1);
				String focal = "kept";
				LocalPoint lp = LocalPoint.fromWorld(
					client.getTopLevelWorldView(),
					new WorldPoint((int) Math.round(mx / n),
						(int) Math.round(my / n),
						client.getTopLevelWorldView().getPlane()));
				if (lp != null)
				{
					client.setCameraFocalPointX(lp.getX());
					client.setCameraFocalPointZ(lp.getY());
					// Raised look-at (pv2 teaser): aim aimHeightTiles
					// above the ground so a 45%-frame hero fits
					// head-to-feet; one tile = 128 world units.
					int tileH = client.getTopLevelWorldView().getTileHeight(
						lp.getX(), lp.getY(),
						client.getTopLevelWorldView().getPlane());
					int focalY = tileH - (int) Math.round(
						options.aimHeightTiles * 128.0);
					client.setCameraFocalPointY(focalY);
					focal = lp.getX() + "/" + lp.getY() + "/" + focalY;
					focalSceneX = lp.getSceneX();
					focalSceneY = lp.getSceneY();
					coneSceneryLogged.clear();
				}
				lastAimYaw = yaw;
				lastAimPitch = pitch;
				log.warn("Headless capture camera aim: yaw={} pitch={} zoom={} focal={} actors at +{}/{}",
					yaw, pitch, zoom, focal, dx, dy);
				try
				{
					// Calibrate the audit's camera model (pv2 021): the
					// live camera position and viewport scale pin down
					// the zoom->distance map and the Perspective divisor.
					log.warn("Headless capture camera posed: x={} y={} z={} scale={} view={}x{}",
						client.getCameraX(), client.getCameraY(), client.getCameraZ(),
						client.getScale(), client.getViewportWidth(), client.getViewportHeight());
				}
				catch (Exception poseEx)
				{
					log.warn("Headless capture camera pose unreadable: {}", poseEx.toString());
				}
			}
			finally
			{
				latch.countDown();
			}
		});
		latch.await(30, TimeUnit.SECONDS);
	}

	/**
	 * Waits until the camera eases onto the last aim (or a timeout), so
	 * every frame of a job shares one converged pose instead of catching
	 * mid-ease jitter.
	 */
	/**
	 * True when collision flags mark a wall/object sight-blocker tile.
	 * Pure for unit tests.
	 */
	static boolean cameraTileBlocked(int flags)
	{
		return (flags & (CollisionDataFlag.BLOCK_MOVEMENT_FULL
			| CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL)) != 0;
	}

	/**
	 * True when the camera height sits below the terrain (plus a small
	 * tolerance). Both inputs are height-down local units, so a larger
	 * camera value is a lower camera. Pure for unit tests.
	 */
	static boolean cameraBelowTerrain(int cameraDown, int tileDown)
	{
		return cameraDown > tileDown + 32;
	}

	/**
	 * Ambient-entity hiding for per-shot capture (pv2 teaser, 022).
	 * Each shot's capture job stages ONLY its subject actors as kit
	 * characters (RuneLiteObjects, not NPC/Player instances); world NPCs
	 * and other players sharing the area (the Warriors' Guild cyclopes,
	 * field goblins) would otherwise wander into frame. Registers a
	 * RenderCallback that refuses every NPC and every non-local player
	 * before it joins the scene — the same mechanism as the client's
	 * EntityHider, so spotanims, projectiles, TileObjects and the kit's
	 * own RuneLiteObjects (the staged pair, the flurry gfx) draw
	 * untouched. The local player stays visible: the compile-time
	 * staging audit already projects it fully outside the crop rect.
	 * (Actor.setDead does NOT suppress rendering — death state only —
	 * so a flag-based hide silently no-ops; the probe proved it.)
	 */
	private final RenderCallback ambientHideCallback = new RenderCallback()
	{
		@Override
		public boolean addEntity(Renderable renderable, boolean ui)
		{
			if (renderable instanceof NPC)
			{
				return false;
			}
			if (renderable instanceof Player
				&& renderable != client.getLocalPlayer())
			{
				return false;
			}
			return true;
		}
	};

	private boolean ambientHideRegistered = false;

	private void hideAmbientEntities(CaptureOptions options)
	{
		if (!options.hideEntities || ambientHideRegistered)
		{
			return;
		}
		renderCallbackManager.register(ambientHideCallback);
		ambientHideRegistered = true;
		log.warn("Headless capture ambient hide on: NPCs + non-local players suppressed");
	}

	/**
	 * Minimum scenery model height (local units, 128 per tile) that counts
	 * as a view-cone occluder. Walls, columns, trees and dense bushes all
	 * clear it; ground clutter (grass tufts, stalagmites, low fences) does
	 * not. Calibrated by the v12 probe render (lenient object log); bump
	 * only with a new probe measurement, never by eye.
	 */
	static final int TALL_SCENERY_MIN_HEIGHT = 224;

	/**
	 * 3-wide tile swath around the camera-to-focal segment (scene coords).
	 * Pure for unit tests: DDA walk plus the Chebyshev-1 ring, deduped in
	 * walk order, so beside-axis occluders (the home white column) fail as
	 * well as on-axis ones.
	 */
	static List<int[]> swathTiles(int x0, int y0, int x1, int y1)
	{
		Set<Integer> seen = new LinkedHashSet<>();
		List<int[]> out = new ArrayList<>();
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) * 2;
		for (int i = 0; i <= steps; i++)
		{
			double t = steps == 0 ? 0.0 : (double) i / steps;
			int px = (int) Math.round(x0 + (x1 - x0) * t);
			int py = (int) Math.round(y0 + (y1 - y0) * t);
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					int key = ((px + dx) << 16) | ((py + dy) & 0xFFFF);
					if (seen.add(key))
					{
						out.add(new int[]{px + dx, py + dy});
					}
				}
			}
		}
		return out;
	}

	/**
	 * True when a scenery model height occludes the view cone. Pure for
	 * unit tests.
	 */
	static boolean tallScenery(int modelHeight)
	{
		return modelHeight >= TALL_SCENERY_MIN_HEIGHT;
	}

	/**
	 * True when a cone-scan object id belongs to the kit, not the world.
	 * Kit characters stage as RuneLiteObjects and transient gfx carries
	 * no static id either; both report negative ids and must never trip
	 * the guard (the staged pair stands at the focal). Static occluders
	 * (walls, columns, trees, fences) always carry real ids. Pure for
	 * unit tests.
	 */
	static boolean skipConeObject(int id)
	{
		return id < 0;
	}

	static int maxModelHeight(Renderable... renderables)
	{
		int h = 0;
		for (Renderable r : renderables)
		{
			if (r != null)
			{
				h = Math.max(h, r.getModelHeight());
			}
		}
		return h;
	}

	/**
	 * Terrain/wall clearance for the converged capture camera (pv2 021
	 * fix 6). Reads the LIVE collision map and tile height at the
	 * camera's ground tile and fails loudly with the tile instead of
	 * rendering void frames. Roofs/overhangs are not detectable from
	 * these APIs and stay an eye-check.
	 */
	private void checkCameraClear()
	{
		// Probe mode (wall-mapping renders): report but do not fail, so
		// one render maps every candidate staging. Set by
		// -Dck.capture.cameraGuardLenient=true (runner: PV_CAMERA_GUARD).
		boolean lenient = Boolean.parseBoolean(
			System.getProperty("ck.capture.cameraGuardLenient", "false"));
		int plane = client.getTopLevelWorldView().getPlane();
		LocalPoint camLp = new LocalPoint(
			client.getCameraX(), client.getCameraY());
		int sx = camLp.getSceneX();
		int sy = camLp.getSceneY();
		CollisionData[] maps = client.getCollisionMaps();
		if (maps != null && plane >= 0 && plane < maps.length
			&& maps[plane] != null)
		{
			int[][] flags = maps[plane].getFlags();
			if (flags != null && sx >= 0 && sy >= 0
				&& sx < flags.length && sy < flags[sx].length)
			{
				int f = flags[sx][sy];
				if (cameraTileBlocked(f))
				{
					String msg = "capture camera inside wall/object at scene "
						+ sx + "," + sy + " plane " + plane
						+ " flags=0x" + Integer.toHexString(f);
					if (lenient)
					{
						log.error("Headless capture camera guard (lenient): {}", msg);
					}
					else
					{
						throw new IllegalStateException(msg);
					}
				}
			}
		}
		int tileH = Perspective.getTileHeight(client, camLp, plane);
		if (cameraBelowTerrain(client.getCameraZ(), tileH))
		{
			String msg = "capture camera below terrain at scene "
				+ sx + "," + sy + " plane " + plane
				+ " cameraDown=" + client.getCameraZ()
				+ " tileDown=" + tileH;
			if (lenient)
			{
				log.error("Headless capture camera guard (lenient): {}", msg);
			}
			else
			{
				throw new IllegalStateException(msg);
			}
		}
		checkViewConeClear(lenient);
	}

	/**
	 * View-cone guard (pv2 teaser, 022): every tile in a 3-wide swath
	 * around the camera-to-focal segment must hold no sight-blocking
	 * collision and no tall scenery object, and no live (non-hidden)
	 * NPC/player may stand on one. Runs per frame from checkCameraClear
	 * so the whole orbit arc is covered; fails naming the scene tile (or
	 * logs it in lenient probe mode) instead of shipping an occlusion.
	 */
	private void checkViewConeClear(boolean lenient)
	{
		if (focalSceneX == Integer.MIN_VALUE)
		{
			return;
		}
		int plane = client.getTopLevelWorldView().getPlane();
		LocalPoint camLp = new LocalPoint(
			client.getCameraX(), client.getCameraY());
		List<int[]> swath = swathTiles(
			camLp.getSceneX(), camLp.getSceneY(), focalSceneX, focalSceneY);
		// Live-actor presence only means visibility when nothing hides
		// them: with ambient hiding on, every NPC/non-local player is
		// refused at the scene gate by construction (and the frames prove
		// it), so listing them here would false-positive on every shot.
		// The local player is audited separately (compile-time rule 3).
		Set<Integer> liveActors = new HashSet<>();
		if (!ambientHideRegistered)
		{
			for (NPC npc : client.getNpcs())
			{
				if (npc != null && !npc.isDead()
					&& npc.getLocalLocation() != null)
				{
					liveActors.add((npc.getLocalLocation().getSceneX() << 16)
						| (npc.getLocalLocation().getSceneY() & 0xFFFF));
				}
			}
			for (Player p : client.getPlayers())
			{
				if (p != null && !p.isDead() && p != client.getLocalPlayer()
					&& p.getLocalLocation() != null)
				{
					liveActors.add((p.getLocalLocation().getSceneX() << 16)
						| (p.getLocalLocation().getSceneY() & 0xFFFF));
				}
			}
		}
		Tile[][][] tiles = client.getTopLevelWorldView().getScene().getTiles();
		CollisionData[] maps = client.getCollisionMaps();
		int[][] flags = (maps != null && plane >= 0 && plane < maps.length
			&& maps[plane] != null) ? maps[plane].getFlags() : null;
		for (int[] t : swath)
		{
			int x = t[0];
			int y = t[1];
			int key = (x << 16) | (y & 0xFFFF);
			if (liveActors.contains(key))
			{
				guardFail(lenient, "non-scene actor in view cone at scene "
					+ x + "," + y + " plane " + plane);
			}
			if (flags != null && x >= 0 && y >= 0
				&& x < flags.length && y < flags[x].length
				&& cameraTileBlocked(flags[x][y]))
			{
				guardFail(lenient, "sight-blocker in view cone at scene "
					+ x + "," + y + " plane " + plane
					+ " flags=0x" + Integer.toHexString(flags[x][y]));
			}
			if (tiles != null && plane >= 0 && plane < tiles.length
				&& x >= 0 && y >= 0 && x < tiles[plane].length
				&& y < tiles[plane][x].length)
			{
				Tile tile = tiles[plane][x][y];
				if (tile != null)
				{
					int h = 0;
					int id = -1;
					GameObject[] gos = tile.getGameObjects();
					if (gos != null)
					{
						for (GameObject go : gos)
						{
							if (go == null)
							{
								continue;
							}
							int gh = maxModelHeight(go.getRenderable());
							if (gh > h)
							{
								h = gh;
								id = go.getId();
							}
						}
					}
					WallObject wall = tile.getWallObject();
					if (wall != null)
					{
						int wh = maxModelHeight(
							wall.getRenderable1(), wall.getRenderable2());
						if (wh > h)
						{
							h = wh;
							id = wall.getId();
						}
					}
					DecorativeObject decor = tile.getDecorativeObject();
					if (decor != null)
					{
						int dh = maxModelHeight(decor.getRenderable(),
							decor.getRenderable2());
						if (dh > h)
						{
							h = dh;
							id = decor.getId();
						}
					}
					if (skipConeObject(id))
					{
						continue;
					}
					if (h > 0 && coneSceneryLogged.add(key))
					{
						log.warn("Headless capture view cone: scenery id={} h={} at scene {},{} plane {}",
							id, h, x, y, plane);
					}
					if (tallScenery(h))
					{
						guardFail(lenient, "tall scenery id=" + id + " h=" + h
							+ " in view cone at scene " + x + "," + y
							+ " plane " + plane);
					}
				}
			}
		}
	}

	private void guardFail(boolean lenient, String msg)
	{
		if (lenient)
		{
			log.error("Headless capture camera guard (lenient): {}", msg);
		}
		else
		{
			throw new IllegalStateException(msg);
		}
	}

	private void convergeCamera() throws Exception
	{
		if (lastAimYaw < 0)
		{
			return;
		}
		long end = System.currentTimeMillis() + 8000;
		while (System.currentTimeMillis() < end)
		{
			AtomicReference<int[]> ref = new AtomicReference<>();
			CountDownLatch latch = new CountDownLatch(1);
			clientThread.invokeLater(() ->
			{
				ref.set(new int[]{client.getCameraYaw(), client.getCameraPitch()});
				latch.countDown();
			});
			latch.await(30, TimeUnit.SECONDS);
			int[] cur = ref.get();
			int dyaw = Math.abs(cur[0] - lastAimYaw) % 2048;
			dyaw = Math.min(dyaw, 2048 - dyaw);
			if (dyaw <= 6 && Math.abs(cur[1] - lastAimPitch) <= 6)
			{
				log.warn("Headless capture camera converged");
				return;
			}
			Thread.sleep(150);
		}
		log.warn("Headless capture camera did not converge in time");
	}

	/**
	 * Per-frame orbit step: rotates the camera around the staged actors by
	 * the track offset for this frame's scene time, then waits for the yaw
	 * to converge before the draw is consumed, so every frame carries its
	 * own camera angle (a real camera track, not a post zoom). Best effort:
	 * a missed convergence logs and keeps the frame rather than failing
	 * the job.
	 */
	private void applyOrbitYaw(
		CaptureOptions options, double sceneSec, double rangeStartSec, double rangeEndSec)
		throws Exception
	{
		double offsetDeg = SceneResolver.orbitOffsetDeg(
			options.orbitDegrees, sceneSec, rangeStartSec, rangeEndSec);
		int target = SceneResolver.orbitYawTarget(lastAimYaw, offsetDeg);
		CountDownLatch set = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				client.setCameraYawTarget(target);
			}
			finally
			{
				set.countDown();
			}
		});
		set.await(30, TimeUnit.SECONDS);
		long end = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < end)
		{
			AtomicReference<Integer> ref = new AtomicReference<>();
			CountDownLatch latch = new CountDownLatch(1);
			clientThread.invokeLater(() ->
			{
				try
				{
					ref.set(client.getCameraYaw());
				}
				finally
				{
					latch.countDown();
				}
			});
			latch.await(30, TimeUnit.SECONDS);
			Integer cur = ref.get();
			if (cur != null)
			{
				int dyaw = Math.abs(cur - target) % 2048;
				dyaw = Math.min(dyaw, 2048 - dyaw);
				if (dyaw <= 3)
				{
					return;
				}
			}
			Thread.sleep(50);
		}
		log.warn("Headless capture orbit yaw did not converge: target={} offsetDeg={}",
			target, offsetDeg);
	}

	/**
	 * Resizes the game canvas (e.g. ck.capture.canvas=1540x900) so the
	 * captured viewport reaches the requested size 1:1. Best effort.
	 */
	private void ensureCanvasSize(CaptureOptions options)
	{
		String raw = options.canvas == null ? "" : options.canvas.trim();
		String[] wh = raw.split("x");
		if (wh.length != 2)
		{
			return;
		}
		try
		{
			int wantW = Integer.parseInt(wh[0].trim());
			int wantH = Integer.parseInt(wh[1].trim());
			for (int pass = 0; pass < 2; pass++)
			{
				final String[] winInfo = new String[1];
				SwingUtilities.invokeAndWait(() ->
				{
					java.awt.Window win =
						SwingUtilities.getWindowAncestor(client.getCanvas());
					if (win == null)
					{
						winInfo[0] = "no-ancestor-window";
						return;
					}
					winInfo[0] = win.getClass().getName()
						+ " frame=" + win.getSize().width + "x" + win.getSize().height;
					// The layout pins the canvas to its preferred size, so
					// growing the frame alone never reaches it: size the
					// canvas directly, then fit the frame around it.
					java.awt.Dimension want =
						new java.awt.Dimension(wantW, wantH);
					client.getCanvas().setPreferredSize(want);
					client.getCanvas().setMinimumSize(want);
					client.getCanvas().setSize(wantW, wantH);
					java.awt.Dimension fs = win.getSize();
					win.setSize(fs.width + (wantW - client.getCanvasWidth()),
						fs.height + (wantH - client.getCanvasHeight()));
					win.validate();
				});
				Thread.sleep(2000);
				log.warn("Headless capture canvas resize pass {}: win={} canvas={}x{}",
					pass, winInfo[0], client.getCanvasWidth(), client.getCanvasHeight());
				if (client.getCanvasWidth() == wantW
					&& client.getCanvasHeight() == wantH)
				{
					break;
				}
			}
			log.warn("Headless capture canvas now {}x{} viewport {}x{}",
				client.getCanvasWidth(), client.getCanvasHeight(),
				client.getViewportWidth(), client.getViewportHeight());
		}
		catch (Exception e)
		{
			log.warn("Headless capture canvas resize failed: {}", e.toString());
		}
	}

	/**
	 * Paused seek: set the time on the UI thread, settle on the client
	 * thread, then consume exactly one completed draw. Frames only ever come
	 * from completed draws, so there are no gaps.
	 */
	private BufferedImage seekAndCapture(
		double tick, double sceneSec, double rangeStartSec, double rangeEndSec,
		CaptureOptions options) throws Exception
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			throw new IllegalStateException(
				"capture requires login; game state is " + client.getGameState());
		}
		SwingUtilities.invokeAndWait(() ->
			creators.getCreatorsPanel().getToolBox().getTimeSheetPanel()
				.setCurrentTime(tick, false));

		// The panel seek above runs on the EDT while rendering reads game
		// objects on the client thread. Re-apply the simulation where it
		// belongs and barrier on it: only draws requested after this point
		// are accepted, so a torn EDT-interleaved draw is never captured.
		AtomicReference<Exception> simErr = new AtomicReference<>();
		CountDownLatch simDone = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				if (client.getGameState() != GameState.LOGGED_IN)
				{
					throw new IllegalStateException(
						"lost login during capture; game state is " + client.getGameState());
				}
				creators.getCreatorsPanel().getToolBox().getProgrammer()
					.updatePrograms(tick);
				hideAmbientEntities(options);
			}
			catch (Exception e)
			{
				simErr.set(e);
			}
			finally
			{
				simDone.countDown();
			}
		});
		if (!simDone.await(30, TimeUnit.SECONDS))
		{
			return null;
		}
		if (simErr.get() != null)
		{
			throw simErr.get();
		}
		if (!cameraAimed)
		{
			aimCameraAtActors(options);
			convergeCamera();
			cameraAimed = true;
			try
			{
				// Post-converge pose (pv2 021): the aim-time pose is the
				// stale spawn pose; only the converged pose calibrates
				// the audit's zoom->distance map and viewport scale.
				log.warn("Headless capture camera converged: yaw={} "
					+ "pitch={} zoom={} x={} y={} z={} scale={} view={}x{}",
					lastAimYaw, lastAimPitch, options.zoom,
					client.getCameraX(), client.getCameraY(),
					client.getCameraZ(), client.getScale(),
					client.getViewportWidth(),
					client.getViewportHeight());
			}
			catch (Exception poseEx)
			{
				log.warn("Headless capture converged pose unreadable: {}",
					poseEx.toString());
			}
		}
		if (options.orbitDegrees != 0.0 && lastAimYaw >= 0)
		{
			applyOrbitYaw(options, sceneSec, rangeStartSec, rangeEndSec);
		}
		// Terrain/wall clearance (pv2 021 fix 6): the converged camera
		// for THIS frame must not sit inside a wall/object or below
		// the terrain, or the frame renders void. Checked per frame
		// so the whole orbit arc is covered, failing loudly with the
		// tile instead of shipping black frames.
		checkCameraClear();
		if (options.settleMs > 0)
		{
			Thread.sleep(options.settleMs);
		}

		CountDownLatch got = new CountDownLatch(1);
		AtomicReference<Image> ref = new AtomicReference<>();
		drawManager.requestNextFrameListener(img ->
		{
			ref.set(img);
			got.countDown();
		});
		if (!got.await(options.drawTimeoutSec, TimeUnit.SECONDS))
		{
			return null;
		}
		Image img = ref.get();
		if (img == null)
		{
			return null;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			throw new IllegalStateException(
				"draw completed while not logged in; game state is "
					+ client.getGameState());
		}
		BufferedImage bi = new BufferedImage(
			img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB);
		bi.getGraphics().drawImage(img, 0, 0, null);
		if (options.cropViewport)
		{
			BufferedImage cropped = cropToViewport(bi);
			if (cropped != null)
			{
				return cropped;
			}
			log.warn("Headless capture: viewport crop fell back to full canvas");
		}
		return bi;
	}

	private BufferedImage cropToViewport(BufferedImage full)
	{
		try
		{
			int x = client.getViewportXOffset();
			int y = client.getViewportYOffset();
			int w = client.getViewportWidth();
			int h = client.getViewportHeight();
			if (w <= 0 || h <= 0)
			{
				return null;
			}
			x = Math.max(0, x);
			y = Math.max(0, y);
			if (x + w > full.getWidth())
			{
				w = full.getWidth() - x;
			}
			if (y + h > full.getHeight())
			{
				h = full.getHeight() - y;
			}
			if (w <= 0 || h <= 0)
			{
				return null;
			}
			return full.getSubimage(x, y, w, h);
		}
		catch (Exception e)
		{
			log.error("Headless capture viewport crop failed", e);
			return null;
		}
	}

	private void writeCaptureJson(
		File out, CaptureOptions options, String scenePath, List<FrameMeta> frames)
		throws Exception
	{
		writeCaptureJson(out, options, scenePath, frames, null);
	}

	private void writeCaptureJson(
		File out, CaptureOptions options, String scenePath,
		List<FrameMeta> frames, String error) throws Exception
	{
		// Built with Gson (never String.format): locale-independent numbers
		// and properly escaped strings.
		com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.addProperty("status", error == null ? "ok" : "error");
		root.addProperty("mode", options.mode.toString().toLowerCase());
		root.addProperty("scene", scenePath);
		root.addProperty("fps", options.fps);
		root.addProperty("crop_viewport", options.cropViewport);
		if (error != null)
		{
			root.addProperty("error", error);
		}
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (FrameMeta f : frames)
		{
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("index", f.index);
			o.addProperty("scene_time", Math.round(f.sceneTime * 10000.0) / 10000.0);
			o.addProperty("tick", Math.round(f.tick * 10000.0) / 10000.0);
			o.addProperty("canvas_w", f.canvasW);
			o.addProperty("canvas_h", f.canvasH);
			o.addProperty("viewport_x", f.viewportX);
			o.addProperty("viewport_y", f.viewportY);
			o.addProperty("viewport_w", f.viewportW);
			o.addProperty("viewport_h", f.viewportH);
			o.addProperty("game_state", f.gameState);
			o.addProperty("file", f.file);
			arr.add(o);
		}
		root.add("frames", arr);
		SceneResolver.atomicWrite(new File(out, "capture.json"),
			creators.getGson().toJson(root).getBytes(StandardCharsets.UTF_8));
	}

	private void writeSentinel(File out, String base, boolean ok, String message) throws Exception
	{
		String name = base == null ? (ok ? "DONE" : "ERROR") : (base + (ok ? ".done" : ".error"));
		SceneResolver.atomicWrite(new File(out, name),
			(message + "\n").getBytes(StandardCharsets.UTF_8));
	}
}
