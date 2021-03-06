package pt.go2.fileio;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pt.go2.response.AbstractResponse;
import pt.go2.response.GzipResponse;
import pt.go2.response.RedirectResponse;

public class LocalFiles implements FileSystemInterface, Runnable {

	static final Logger LOG = LogManager.getLogger(FileSystemInterface.class);

	private final Map<String, AbstractResponse> pages = new ConcurrentHashMap<>();
	private final Map<WatchKey, Path> keys = new HashMap<>();
	private final Set<String> directories = new HashSet<>();

	private final int trim;

	private volatile boolean running;

	private final WatchService watchService;

	private final Configuration config;

	public LocalFiles(Configuration config) throws IOException {

		this.config = config;

		this.trim = config.PUBLIC.length();

		this.watchService = FileSystems.getDefault().newWatchService();

		final List<Path> files = new ArrayList<>();
		final Set<Path> dir = new HashSet<>();

		FileCrawler.crawl(config.PUBLIC, dir, files);

		for (Path path : files) {

			addStaticPage(path);
		}

		for (Path path : dir) {
			try {
				register(path);
			} catch (IOException e) {
				LOG.warn("Could not register directory: " + path.toString()
						+ ".", e);
			}
		}
	}

	public List<String> browse() {
		List<String> result = new ArrayList<>(directories.size() + pages.size());

		result.addAll(directories);
		result.addAll(pages.keySet());

		return result;
	}

	private void register(Path path) throws IOException {
		final WatchKey key = path.register(watchService,
				StandardWatchEventKinds.ENTRY_CREATE,
				StandardWatchEventKinds.ENTRY_MODIFY,
				StandardWatchEventKinds.ENTRY_DELETE);

		keys.put(key, path);
		this.directories.add(path.toString());
	}

	@Override
	public void start() {
		new Thread(this).start();
	}

	@Override
	public void stop() {
		running = false;
	}

	@Override
	public AbstractResponse getFile(String filename) {

		if ("/".equals(filename) && config.PUBLIC != null) {
			filename += config.PUBLIC_ROOT;
		}

		filename = filename.replace('/', File.separatorChar);

		final AbstractResponse page = pages.get(filename);

		if (page != null) {
			return page;
		}

		if (!filename.endsWith("/")) {

			final String directory = config.PUBLIC + File.separator + filename;

			if (directories.contains(directory)) {
				return new RedirectResponse("/" + filename + "/", 301);
			}
		}

		return null;
	}

	@Override
	public void run() {

		running = true;

		while (watchService != null && running) {

			WatchKey key;
			try {
				key = watchService.poll(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				LOG.warn("Interrupted.", e);
				continue;
			}

			if (key == null) {
				continue;
			}

			for (WatchEvent<?> watchEvent : key.pollEvents()) {

				final Kind<?> kind = watchEvent.kind();

				if (kind == StandardWatchEventKinds.OVERFLOW) {
					continue;
				}

				// get the filename for the event
				@SuppressWarnings("unchecked")
				final WatchEvent<Path> watchEventPath = (WatchEvent<Path>) watchEvent;

				final Path parent = keys.get(key);

				if (parent == null) {
					LOG.error("No parent for key: " + key.toString());
					continue;
				}

				final Path entry = parent.resolve(watchEventPath.context()
						.getFileName());

				final String filename = entry.toString();

				LOG.info("KIND: " + kind.name() + " file:" + filename);

				if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
					try {
						if (entry.toFile().isDirectory()) {
							LOG.info("REG: " + filename);
							register(entry);
						} else {
							LOG.info("ADD: " + filename);
							addStaticPage(entry);
						}
					} catch (IOException e) {
						LOG.error("Exception on add/reg.", e);
					}
				} else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {

					LOG.info("CHG: " + filename);
					addStaticPage(entry);
				} else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {

					removePage(filename);
				}
			}

			if (!key.reset()) {
				Path filename = keys.remove(key);
				key.cancel();
				LOG.info("Removing: " + filename);
				if (directories.remove(filename.toString())) {
					LOG.info("Removed: " + filename);
				}
			}
		}
	}

	private void removePage(String filename) {
		filename = filename.substring(trim);
		boolean removed = pages.remove(filename) != null;
		LOG.info("Removed " + filename + " " + (removed ? "Y" : "N"));
	}

	private void addStaticPage(final Path path) {

		addStaticPage(path.toString());
	}

	private void addStaticPage(final String filename) {

		try {

			this.pages.put(filename.substring(trim), new GzipResponse(
					SmartTagParser.read(filename), filename));

		} catch (IOException e) {

			LOG.error("Failed loading: " + filename + ".", e);
		}
	}
}
