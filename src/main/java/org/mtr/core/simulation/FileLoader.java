package org.mtr.core.simulation;

import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.objects.Object2IntAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import org.mtr.core.Main;
import org.mtr.core.data.NameColorDataBase;
import org.mtr.core.data.SerializedDataBase;
import org.mtr.core.reader.MessagePackHelper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Stream;

public class FileLoader<T extends SerializedDataBase> {

	public final String key;
	private final Set<T> dataSet;
	private final Path path;
	private final boolean skipVerifyNameIsNotEmpty;
	private final Object2IntAVLTreeMap<String> fileHashes = new Object2IntAVLTreeMap<>();

	public FileLoader(Set<T> dataSet, Function<MessagePackHelper, T> getData, Path rootPath, String key, boolean skipVerifyNameIsNotEmpty) {
		this.key = key;
		this.dataSet = dataSet;
		this.skipVerifyNameIsNotEmpty = skipVerifyNameIsNotEmpty;
		path = rootPath.resolve(key);
		createDirectory(path);
		readMessagePackFromFile(getData);
	}

	public IntIntImmutablePair save(boolean useReducedHash) {
		final ObjectArrayList<T> dirtyData = new ObjectArrayList<>(dataSet);
		final ObjectImmutableList<ObjectArrayList<String>> checkFilesToDelete = createEmptyList256();
		fileHashes.keySet().forEach(fileName -> checkFilesToDelete.get(getParentInt(fileName)).add(fileName));

		final int filesWritten = writeDirtyDataToFile(checkFilesToDelete, dirtyData, SerializedDataBase::getHexId, useReducedHash);
		int filesDeleted = 0;

		for (final ObjectArrayList<String> checkFilesToDeleteForParent : checkFilesToDelete) {
			for (final String fileName : checkFilesToDeleteForParent) {
				try {
					if (Files.deleteIfExists(path.resolve(fileName))) {
						filesDeleted++;
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
				fileHashes.removeInt(fileName);
			}
		}

		return new IntIntImmutablePair(filesWritten, filesDeleted);
	}

	private void readMessagePackFromFile(Function<MessagePackHelper, T> getData) {
		final Object2ObjectArrayMap<String, Future<T>> futureDataMap = new Object2ObjectArrayMap<>();
		final ExecutorService executorService = Executors.newCachedThreadPool();

		try (final Stream<Path> pathStream = Files.list(path)) {
			pathStream.forEach(idFolder -> {
				try (final Stream<Path> folderStream = Files.list(idFolder)) {
					folderStream.forEach(idFile -> futureDataMap.put(combineAsPath(idFolder, idFile), executorService.submit(() -> {
						try (final InputStream inputStream = Files.newInputStream(idFile)) {
							try (final MessageUnpacker messageUnpacker = MessagePack.newDefaultUnpacker(inputStream)) {
								final int size = messageUnpacker.unpackMapHeader();
								final Object2ObjectArrayMap<String, Value> result = new Object2ObjectArrayMap<>(size);

								for (int i = 0; i < size; i++) {
									result.put(messageUnpacker.unpackString(), messageUnpacker.unpackValue());
								}

								return getData.apply(new MessagePackHelper(result));
							} catch (Exception e) {
								e.printStackTrace();
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
						return null;
					})));
				} catch (Exception e) {
					e.printStackTrace();
				}

				try {
					Files.deleteIfExists(idFolder);
					Main.LOGGER.info(String.format("Deleted empty folder: %s", idFolder));
				} catch (DirectoryNotEmptyException ignored) {
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
		} catch (Exception e) {
			e.printStackTrace();
		}

		futureDataMap.forEach((fileName, futureData) -> {
			try {
				final T data = futureData.get();
				if (data != null) {
					dataSet.add(data);
					fileHashes.put(fileName, getHash(data, true));
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	private int writeDirtyDataToFile(ObjectImmutableList<ObjectArrayList<String>> checkFilesToDelete, ObjectArrayList<T> dirtyData, Function<T, String> getFileName, boolean useReducedHash) {
		int filesWritten = 0;
		while (!dirtyData.isEmpty()) {
			final T data = dirtyData.remove(0);

			if (data != null && (skipVerifyNameIsNotEmpty || !(data instanceof NameColorDataBase) || !((NameColorDataBase) data).name.isEmpty())) {
				final String fileName = getFileName.apply(data);
				final String parentFolderName = getParent(fileName);
				final String parentAndFileName = combineAsPath(parentFolderName, fileName);
				final int hash = getHash(data, useReducedHash);

				if (!fileHashes.containsKey(parentAndFileName) || hash != fileHashes.getInt(parentAndFileName)) {
					createDirectory(path.resolve(parentFolderName));

					try (final MessagePacker messagePacker = MessagePack.newDefaultPacker(Files.newOutputStream(path.resolve(parentAndFileName), StandardOpenOption.CREATE))) {
						packMessage(messagePacker, data, useReducedHash);
					} catch (Exception e) {
						e.printStackTrace();
					}

					fileHashes.put(fileName, hash);
					filesWritten++;
				}

				checkFilesToDelete.get(getParentInt(fileName)).remove(parentAndFileName);
			}
		}

		return filesWritten;
	}

	private static String getParent(String fileName) {
		return fileName.substring(Math.max(0, fileName.length() - 2));
	}

	private static int getParentInt(String fileName) {
		try {
			return Integer.parseInt(getParent(fileName), 16);
		} catch (Exception e) {
			e.printStackTrace();
			return 0;
		}
	}

	private static ObjectImmutableList<ObjectArrayList<String>> createEmptyList256() {
		final ObjectArrayList<ObjectArrayList<String>> list = new ObjectArrayList<>();
		for (int i = 0; i <= 0xFF; i++) {
			list.add(new ObjectArrayList<>());
		}
		return new ObjectImmutableList<>(list);
	}

	private static String combineAsPath(Path parentFolderPath, Path filePath) {
		return combineAsPath(parentFolderPath.getFileName().toString(), filePath.getFileName().toString());
	}

	private static String combineAsPath(String parentFolderName, String fileName) {
		return String.format("%s/%s", parentFolderName, fileName);
	}

	private static int getHash(SerializedDataBase data, boolean useReducedHash) {
		int hash = 0;
		try (final MessageBufferPacker messageBufferPacker = MessagePack.newDefaultBufferPacker()) {
			packMessage(messageBufferPacker, data, useReducedHash);
			hash = Arrays.hashCode(messageBufferPacker.toByteArray());
		} catch (Exception e) {
			e.printStackTrace();
		}
		return hash;
	}

	private static void packMessage(MessagePacker messagePacker, SerializedDataBase data, boolean useReducedHash) throws IOException {
		if (useReducedHash) {
			messagePacker.packMapHeader(data.messagePackLength());
			data.toMessagePack(messagePacker);
		} else {
			messagePacker.packMapHeader(data.fullMessagePackLength());
			data.toFullMessagePack(messagePacker);
		}
	}

	private static void createDirectory(Path path) {
		if (!Files.exists(path)) {
			try {
				Files.createDirectories(path);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
}
