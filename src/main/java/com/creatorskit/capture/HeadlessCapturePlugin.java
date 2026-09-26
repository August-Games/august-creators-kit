package com.creatorskit.capture;

import com.creatorskit.CreatorsPlugin;
import com.creatorskit.models.CustomModelComp;
import com.creatorskit.models.CustomModelType;
import com.creatorskit.models.DataFinder;
import com.creatorskit.models.ModelStats;
import com.creatorskit.saves.CharacterSave;
import com.creatorskit.saves.SetupSave;
import com.google.inject.Inject;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.PlayerComposition;
import net.runelite.api.coords.WorldPoint;
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
		worker = new Thread(() -> runCapture(options), "headless-capture");
		worker.setDaemon(true);
		worker.start();
	}

	@Override
	protected void shutDown()
	{
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
		catch (Exception e)
		{
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
		try
		{
			String json = new String(Files.readAllBytes(req.toPath()), StandardCharsets.UTF_8);
			@SuppressWarnings("unchecked")
			Map<String, Object> map = creators.getGson().fromJson(json, Map.class);
			Properties props = new Properties();
			putIfPresent(props, "ck.capture.scene", map.get("scene"));
			putIfPresent(props, "ck.capture.out", map.get("out"));
			putIfPresent(props, "ck.capture.fps", map.get("fps"));
			putIfPresent(props, "ck.capture.start", map.get("start"));
			putIfPresent(props, "ck.capture.end", map.get("end"));
			putIfPresent(props, "ck.capture.settleMs", map.get("settleMs"));
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
			log.error("Headless capture daemon request {} failed", req.getName(), e);
		}
		finally
		{
			try
			{
				Files.move(req.toPath(), new File(handled, req.getName()).toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			catch (Exception e)
			{
				log.error("Headless capture could not archive {}", req.getName(), e);
			}
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

			if (!resolveComps(save))
			{
				List<String> blockers = new ArrayList<>();
				CustomModelComp[] comps = save.getComps();
				for (int i = 0; i < comps.length; i++)
				{
					if (SceneResolver.blocksPlayback(comps[i]))
					{
						blockers.add(SceneResolver.describeBlocker(i, comps[i]));
					}
				}
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
			// Stage first: the resolved file must match what actually plays,
			// or the actors end up at the authored tiles while the camera
			// follows the capturing player elsewhere.
			stageOnPlayer(save);
			File resolved = new File(out, "resolved_scene.json");
			Files.write(resolved.toPath(),
				creators.getGson().toJson(save).getBytes(StandardCharsets.UTF_8));

			resetScene();
			loadSceneFile(resolved);
			disableGpuForCpuCapture();

			List<String> spawned = spawnedCharacterNames();
			log.warn("Headless capture spawned {} characters: {}",
				spawned.size(), spawned);
			reportSpawnHealth();

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
	 * Resolves every comp carrying a resolve request against the live cache.
	 * Returns false when any comp remains unresolved; the caller must then
	 * refuse the scene.
	 */
	private boolean resolveComps(SetupSave save) throws Exception
	{
		CustomModelComp[] comps = save.getComps();
		List<Integer> pending = SceneResolver.unresolvedIndices(comps);
		if (pending.isEmpty())
		{
			return true;
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
				return false;
			}
		}
		return true;
	}

	private boolean resolvePlayer(CustomModelComp comp, int[] base, int[] colours) throws Exception
	{
		if (base == null)
		{
			return false;
		}
		int[] equipment = SceneResolver.buildEquipmentArray(
			base, comp.getEquipmentSlots(), PlayerComposition.ITEM_OFFSET);
		boolean maleItem = !Boolean.TRUE.equals(comp.getFemale());
		AtomicReference<ModelStats[]> ref = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				ref.set(creators.getDataFinder().findModelsForPlayer(
					false, maleItem, equipment, -1, -1, -1, new int[0]));
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
	private void stageOnPlayer(SetupSave save) throws Exception
	{
		if (!Boolean.parseBoolean(System.getProperty("ck.capture.stageOnPlayer", "true")))
		{
			return;
		}
		int[] dxdy = parseOffset(System.getProperty("ck.capture.stageOffset", "2,0"));
		int[] pp = readStablePlayerTile();
		if (pp == null)
		{
			log.warn("Headless capture: no local player for staging; using authored tiles");
			return;
		}
		CharacterSave[] chars = save.getMasterFolderNode() != null
			? save.getMasterFolderNode().getCharacterSaves()
			: null;
		if (chars == null || chars.length == 0 || chars[0].getNonInstancedPoint() == null)
		{
			return;
		}
		WorldPoint anchor = chars[0].getNonInstancedPoint();
		int dx = pp[0] - anchor.getX() + dxdy[0];
		int dy = pp[1] - anchor.getY() + dxdy[1];
		int dz = pp[2] - anchor.getPlane();
		for (CharacterSave ch : chars)
		{
			if (ch.getNonInstancedPoint() == null)
			{
				continue;
			}
			WorldPoint p = ch.getNonInstancedPoint();
			WorldPoint moved = new WorldPoint(
				p.getX() + dx, p.getY() + dy, p.getPlane() + dz);
			ch.setNonInstancedPoint(moved);
			log.warn("Headless capture staged {} at {}/{}/{} (player at {}/{}/{})",
				ch.getName(), moved.getX(), moved.getY(), moved.getPlane(),
				pp[0], pp[1], pp[2]);
		}
		log.warn("Headless capture staged {} characters near {}/{}/{}",
			chars.length, pp[0], pp[1], pp[2]);
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
				for (com.creatorskit.Character ch : creators.getCharacters())
				{
					boolean hasObject = ch.getCkObject() != null;
					boolean hasModel = hasObject && ch.getCkObject().getModel() != null;
					boolean active = hasObject && ch.getCkObject().isActive();
					ref.get().add(ch.getName()
						+ " object=" + hasObject
						+ " model=" + hasModel
						+ " active=" + active);
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
	 * With the hardware-accelerated path the completed-draw image handed to
	 * frame listeners is empty, so capture runs on the CPU path.
	 */
	private void disableGpuForCpuCapture() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				if (p.getClass().getSimpleName().equals("GpuPlugin"))
				{
					try
					{
						pluginManager.setPluginEnabled(p, false);
						pluginManager.stopPlugin(p);
						log.warn("Headless capture stopped GPU plugin for CPU capture");
					}
					catch (Exception e)
					{
						log.error("Headless capture GPU stop failed", e);
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
		for (int i = 0; i < times.length; i++)
		{
			double sceneTime = times[i];
			double tick = CaptureOptions.secToTick(sceneTime);
			BufferedImage img = seekAndCapture(tick, options);
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

	/**
	 * Paused seek: set the time on the UI thread, settle on the client
	 * thread, then consume exactly one completed draw. Frames only ever come
	 * from completed draws, so there are no gaps.
	 */
	private BufferedImage seekAndCapture(double tick, CaptureOptions options) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
			creators.getCreatorsPanel().getToolBox().getTimeSheetPanel()
				.setCurrentTime(tick, false));

		CountDownLatch settled = new CountDownLatch(1);
		clientThread.invokeLater(settled::countDown);
		if (!settled.await(30, TimeUnit.SECONDS))
		{
			return null;
		}
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
		StringBuilder sb = new StringBuilder();
		sb.append("{\n");
		field(sb, "status", error == null ? "ok" : "error", true);
		field(sb, "mode", options.mode.toString().toLowerCase(), true);
		field(sb, "scene", scenePath, true);
		sb.append("  \"fps\": ").append(options.fps).append(",\n");
		sb.append("  \"crop_viewport\": ").append(options.cropViewport).append(",\n");
		if (error != null)
		{
			field(sb, "error", error, true);
		}
		sb.append("  \"frames\": [\n");
		for (int i = 0; i < frames.size(); i++)
		{
			FrameMeta f = frames.get(i);
			sb.append("    {");
			sb.append("\"index\": ").append(f.index).append(", ");
			sb.append("\"scene_time\": ").append(String.format("%.4f", f.sceneTime)).append(", ");
			sb.append("\"tick\": ").append(String.format("%.4f", f.tick)).append(", ");
			sb.append("\"canvas_w\": ").append(f.canvasW).append(", ");
			sb.append("\"canvas_h\": ").append(f.canvasH).append(", ");
			sb.append("\"viewport_x\": ").append(f.viewportX).append(", ");
			sb.append("\"viewport_y\": ").append(f.viewportY).append(", ");
			sb.append("\"viewport_w\": ").append(f.viewportW).append(", ");
			sb.append("\"viewport_h\": ").append(f.viewportH).append(", ");
			sb.append("\"game_state\": \"").append(f.gameState).append("\", ");
			sb.append("\"file\": \"").append(f.file).append("\"}");
			sb.append(i + 1 < frames.size() ? ",\n" : "\n");
		}
		sb.append("  ]\n}\n");
		Files.write(new File(out, "capture.json").toPath(),
			sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static void field(StringBuilder sb, String key, String value, boolean comma)
	{
		sb.append("  \"").append(key).append("\": \"")
			.append(value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""))
			.append("\"").append(comma ? ",\n" : "\n");
	}

	private void writeSentinel(File out, String base, boolean ok, String message) throws Exception
	{
		String name = base == null ? (ok ? "DONE" : "ERROR") : (base + (ok ? ".done" : ".error"));
		Files.write(new File(out, name).toPath(),
			(message + "\n").getBytes(StandardCharsets.UTF_8));
	}
}
