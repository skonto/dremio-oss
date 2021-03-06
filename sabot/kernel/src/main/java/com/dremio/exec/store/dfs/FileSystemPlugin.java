/*
 * Copyright (C) 2017 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.dfs;

import static com.dremio.exec.store.dfs.easy.EasyDatasetXAttrSerDe.EASY_DATASET_SPLIT_XATTR_SERIALIZER;
import static com.dremio.exec.store.parquet.ParquetDatasetXAttrSerDe.PARQUET_DATASET_SPLIT_XATTR_SERIALIZER;
import static com.dremio.service.users.SystemUser.SYSTEM_USERNAME;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.apache.calcite.schema.Function;
import org.apache.directory.api.util.Strings;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.security.AccessControlException;
import org.apache.parquet.Preconditions;

import com.dremio.common.config.LogicalPlanPersistence;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.logical.FormatPluginConfig;
import com.dremio.common.utils.PathUtils;
import com.dremio.common.utils.SqlUtils;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.dotfile.DotFile;
import com.dremio.exec.dotfile.DotFileType;
import com.dremio.exec.dotfile.DotFileUtil;
import com.dremio.exec.dotfile.View;
import com.dremio.exec.planner.logical.ViewTable;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.ClassPathFileSystem;
import com.dremio.exec.store.DatasetSplitsPointer;
import com.dremio.exec.store.LocalSyncableFileSystem;
import com.dremio.exec.store.PartitionNotFoundException;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.SchemaEntity;
import com.dremio.exec.store.SchemaEntity.SchemaEntityType;
import com.dremio.exec.store.SplitsPointer;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.StoragePluginInstanceRulesFactory;
import com.dremio.exec.store.StoragePluginTypeRulesFactory;
import com.dremio.exec.store.TimedRunnable;
import com.dremio.exec.util.ImpersonationUtil;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.SourceTableDefinition;
import com.dremio.service.namespace.StoragePluginId;
import com.dremio.service.namespace.StoragePluginType;
import com.dremio.service.namespace.TableInstance;
import com.dremio.service.namespace.capabilities.BooleanCapabilityValue;
import com.dremio.service.namespace.capabilities.SourceCapabilities;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetSplit;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.PhysicalDataset;
import com.dremio.service.namespace.file.proto.FileSystemCachedEntity;
import com.dremio.service.namespace.file.proto.FileType;
import com.dremio.service.namespace.file.proto.FileUpdateKey;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.proto.NameSpaceContainer.Type;
import com.dremio.service.users.SystemUser;
import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.protostuff.ByteString;
import io.protostuff.ProtostuffIOUtil;

/**
 * Storage plugin for file system
 */
public class FileSystemPlugin implements StoragePlugin {
  /**
   * Default {@link Configuration} instance. Use this instance through {@link #getNewFsConf()} to create new copies
   * of {@link Configuration} objects.
   */
  private static final Configuration DEFAULT_CONFIGURATION = new Configuration();

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FileSystemPlugin.class);

  private static final BooleanCapabilityValue REQUIRES_HARD_AFFINITY = new BooleanCapabilityValue(SourceCapabilities.REQUIRES_HARD_AFFINITY, true);

  private static final int PERMISSION_CHECK_TASK_BATCH_SIZE = 10;

  public static final String FILESYSTEM_TYPE_NAME = "dfs";

  private final String name;
  private final LogicalPlanPersistence lpPersistance;
  private final FileSystemConfig config;
  private final SabotContext context;
  private final Path basePath;

  private FileSystemWrapper fs;
  private Configuration fsConf;
  private FormatPluginOptionExtractor optionExtractor;
  protected FormatCreator formatCreator;
  private ArrayList<FormatMatcher> matchers;
  private List<FormatMatcher> dropFileMatchers;
  private CompressionCodecFactory codecFactory;

  public FileSystemPlugin(final FileSystemConfig config, final SabotContext context, final String name) throws ExecutionSetupException {
    this.name = name;
    this.config = config;
    this.context = context;
    this.fsConf = getNewFsConf();
    this.lpPersistance = context.getLpPersistence();
    this.basePath = new Path(config.getPath());
  }

  protected FileSystemPlugin(final FileSystemConfig config, final SabotContext context, final String name, FileSystemWrapper fs) throws ExecutionSetupException {
    this.name = name;
    this.config = config;
    this.fs = fs;
    this.context = context;
    this.fsConf = getNewFsConf();
    this.lpPersistance = context.getLpPersistence();
    this.basePath = new Path(config.getPath());
  }

  @Override
  public Iterable<SourceTableDefinition> getDatasets(String user, boolean ignoreAuthErrors) throws Exception {
    return Collections.emptyList(); // file system does not know about physical datasets
  }

  @Override
  public StoragePluginId getId() {
    if(name == null){
      throw new IllegalStateException("Attempted to get the id for an ephemeral storage plugin.");
    }
    final StoragePluginType pluginType = new StoragePluginType(FILESYSTEM_TYPE_NAME, context.getConfig().getClass("dremio.plugins.dfs.rulesfactory", StoragePluginTypeRulesFactory.class, FileSystemRulesFactory.class));
    return fs.isPdfs() ?
        new StoragePluginId(name, config, new SourceCapabilities(REQUIRES_HARD_AFFINITY), pluginType) :
          new StoragePluginId(name, config, SourceCapabilities.NONE, pluginType);
  }

  public static final Configuration getNewFsConf() {
    return new Configuration(DEFAULT_CONFIGURATION);
  }

  public Configuration getFsConf() {
    return fsConf;
  }

  public FileSystemWrapper getFS(String userName) {
    return getFs(userName, null);
  }

  public FileSystemWrapper getFs(String userName, OperatorStats stats) {
    if (!config.isImpersonationEnabled()) {
      userName = ImpersonationUtil.getProcessUserName();
    }
    return ImpersonationUtil.createFileSystem(ImpersonationUtil.createProxyUgi(userName), getFsConf(), stats);
  }

  public Iterable<String> getSubPartitions(List<String> table,
                                           List<String> partitionColumns,
                                           List<String> partitionValues,
                                           SchemaConfig schemaConfig
  ) throws PartitionNotFoundException {
    List<FileStatus> fileStatuses;
    try {
      Path fullPath = PathUtils.toFSPath(resolveTableNameToValidPath(table));
      fileStatuses = getFS(schemaConfig.getUserName()).list(fullPath, false);
    } catch (IOException e) {
      throw new PartitionNotFoundException("Error finding partitions for table " + table, e);
    }
    return new SubDirectoryList(fileStatuses);
  }

  @Override
  public Class<? extends StoragePluginInstanceRulesFactory> getRulesFactoryClass() {
    return null;
  }

  @Override
  public SourceState getState() {
    final FileSystemWrapper fs = getFS(ImpersonationUtil.getProcessUserName());
    if (!fs.isPdfs()) {
      try {
        fs.listStatus(new Path(config.getPath()));
        return SourceState.GOOD;
      } catch (Exception e) {
        return SourceState.badState(e);
      }
    } else {
      return SourceState.GOOD;
    }
  }

  @Override
  public ViewTable getView(List<String> tableSchemaPath, SchemaConfig schemaConfig) {
    List<DotFile> files = Collections.emptyList();
    try {
      try {
        files = DotFileUtil.getDotFiles(getFS(schemaConfig.getUserName()), new Path(config.getPath()), tableSchemaPath.get(tableSchemaPath.size() - 1), DotFileType.VIEW);
      } catch (AccessControlException e) {
        if (!schemaConfig.getIgnoreAuthErrors()) {
          logger.debug(e.getMessage());
          throw UserException.permissionError(e)
                  .message("Not authorized to list or query tables in schema %s", tableSchemaPath)
                  .build(logger);
        }
      } catch (IOException e) {
        logger.warn("Failure while trying to list view tables in workspace [{}]", tableSchemaPath, e);
      }

      for (DotFile f : files) {
        switch (f.getType()) {
        case VIEW:
          try {
            return new ViewTable(f.getView(lpPersistance), f.getOwner(), schemaConfig.getViewExpansionContext());
          } catch (AccessControlException e) {
            if (!schemaConfig.getIgnoreAuthErrors()) {
              logger.debug(e.getMessage());
              throw UserException.permissionError(e)
                      .message("Not authorized to read view [%s] in schema %s", tableSchemaPath.get(tableSchemaPath.size() - 1), tableSchemaPath.subList(0, tableSchemaPath.size() - 1))
                      .build(logger);
            }
          } catch (IOException e) {
            logger.warn("Failure while trying to load {}.view.meta file in workspace [{}]", tableSchemaPath.get(tableSchemaPath.size() - 1), tableSchemaPath.subList(0, tableSchemaPath.size() - 1), e);
          }
        }
      }
    } catch (UnsupportedOperationException e) {
      logger.debug("The filesystem for this workspace does not support this operation.", e);
    }
    return null;
  }

  /**
   * Helper method which resolves the table name to actual file/folder on the filesystem.
   * If the resolved path refers to an entity not under the base of the source then a permission error is thrown.
   *
   * Ex. For given source named "dfs" which has base path of "/base" tableSchemaPath
   *    [dfs, tmp, a] -> "/base/tmp/a"
   *    [dfs, "/tmp/b"] -> "/base/tmp/b"
   *    [dfs, "value", tbl] -> "/base/value/tbl"
   * @param tableSchemaPath
   * @return
   */
  private List<String> resolveTableNameToValidPath(List<String> tableSchemaPath) {
    List<String> fullPath = new ArrayList<>();
    fullPath.addAll(PathUtils.toPathComponents(basePath));
    for (String pathComponent : tableSchemaPath.subList(1 /* need to skip the source name */, tableSchemaPath.size())) {
      fullPath.add(PathUtils.removeQuotes(pathComponent));
    }
    PathUtils.verifyNoAccessOutsideBase(basePath, PathUtils.toFSPath(fullPath));
    return fullPath;
  }

  /**
   * Resolve given table path relative to source resolve it to a valid path in filesystem.
   * If the resolved path refers to an entity not under the base of the source then a permission error is thrown.
   * @param tablePath
   * @return
   */
  public Path resolveTablePathToValidPath(String tablePath) {
    String relativePathClean = PathUtils.removeLeadingSlash(tablePath);
    Path combined = new Path(basePath, relativePathClean);
    PathUtils.verifyNoAccessOutsideBase(basePath, combined);
    return combined;
  }

  @Override
  public SourceTableDefinition getDataset(NamespaceKey datasetPath, DatasetConfig oldConfig, boolean ignoreAuthErrors) throws Exception {
    FormatPluginConfig formatPluginConfig = null;

    PhysicalDataset physicalDataset = oldConfig == null ? null : oldConfig.getPhysicalDataset();
    if(physicalDataset != null && physicalDataset.getFormatSettings() != null){
      formatPluginConfig = PhysicalDatasetUtils.toFormatPlugin(physicalDataset.getFormatSettings(), Collections.<String>emptyList());
    }

    return getDatasetWithFormat(datasetPath, oldConfig, formatPluginConfig, ignoreAuthErrors, null);
  }

  SourceTableDefinition getDatasetWithOptions(NamespaceKey datasetPath, TableInstance instance, boolean
    ignoreAuthErrors, String user) throws Exception{
    final FormatPluginConfig fconfig = optionExtractor.createConfigForTable(instance);
    return getDatasetWithFormat(datasetPath, null, fconfig, ignoreAuthErrors, user);
  }

  protected SourceTableDefinition getDatasetWithFormat(NamespaceKey datasetPath, DatasetConfig oldConfig, FormatPluginConfig formatPluginConfig,
                                                       boolean ignoreAuthErrors, String user) throws Exception {

    if(datasetPath.size() <= 1){
      return null;  // not a valid table schema path
    }
    final List<String> fullPath = resolveTableNameToValidPath(datasetPath.getPathComponents());
    try {
      // TODO: why do we need distinguish between system user and process user?
      final String userName = config.isImpersonationEnabled() ? SystemUser.SYSTEM_USERNAME : ImpersonationUtil.getProcessUserName();
      List<String> parentSchemaPath = new ArrayList<>(fullPath.subList(0, fullPath.size() - 1));
      FileSystemWrapper fs = getFS((user != null) ? user : userName);
      FileSelection fileSelection = FileSelection.create(fs, fullPath);
      String tableName = datasetPath.getName();

      if (fileSelection == null) {
        fileSelection = FileSelection.createWithFullSchema(fs, PathUtils.toFSPathString(parentSchemaPath), tableName);
        if (fileSelection == null) {
          return null; // no table found
        } else {
          // table name is a full schema path (tableau use case), parse it and append it to schemapath.
          final List<String> tableNamePathComponents = PathUtils.parseFullPath(tableName);
          tableName = tableNamePathComponents.remove(tableNamePathComponents.size() - 1);
          parentSchemaPath.addAll(tableNamePathComponents);
        }
      }

      final boolean hasDirectories = fileSelection.containsDirectories();
      final FileStatus rootStatus = fs.getFileStatus(new Path(fileSelection.getSelectionRoot()));

      // Get subdirectories under file selection before pruning directories
      final List<FileSystemCachedEntity> cachedEntities = Lists.newArrayList();
      if (rootStatus.isDirectory()) {
        // first entity is always a root
        cachedEntities.add(fromFileStatus(rootStatus));
      }

      for (FileStatus dirStatus: fileSelection.getAllDirectories()) {
        cachedEntities.add(fromFileStatus(dirStatus));
      }

      final FileUpdateKey updateKey = new FileUpdateKey().setCachedEntitiesList(cachedEntities);
      // Expand selection by copying it first used to check extensions of files in directory.
      final FileSelection fileSelectionWithoutDir =  hasDirectories? new FileSelection(fileSelection).minusDirectories(): fileSelection;
      if(fileSelectionWithoutDir == null || fileSelectionWithoutDir.isEmpty()){
        // no files in the found directory, not a table.
        return null;
      }

      SourceTableDefinition datasetAccessor = null;

      if (formatPluginConfig != null) {

        FormatPlugin formatPlugin = formatCreator.getFormatPluginByConfig(formatPluginConfig);
        if(formatPlugin == null){
          formatPlugin = formatCreator.newFormatPlugin(formatPluginConfig);
        }
        datasetAccessor = formatPlugin.getDatasetAccessor(oldConfig, fs, fileSelectionWithoutDir, this, datasetPath, tableName, updateKey);
      }

      if (datasetAccessor == null) {
        for (final FormatMatcher matcher : matchers) {
          try {
            if (matcher.matches(fs, fileSelection, codecFactory)) {
              datasetAccessor = matcher.getFormatPlugin().getDatasetAccessor(oldConfig, fs, fileSelectionWithoutDir, this, datasetPath, tableName, updateKey);
              if (datasetAccessor != null) {
                break;
              }
            }
          } catch (IOException e) {
            logger.debug("File read failed.", e);
          }
        }
      }

      return datasetAccessor;
    } catch (AccessControlException e) {
      if (!ignoreAuthErrors) {
        logger.debug(e.getMessage());
        throw UserException.permissionError(e)
          .message("Not authorized to read table %s at path ", datasetPath)
          .build(logger);
      }
    } catch (IOException e) {
      logger.debug("Failed to create table {}", datasetPath, e);
    }
    return null;
  }


  @Override
  public void start() throws IOException {
    if (config.getConfig() != null) {
      for (Entry<String, String> prop : config.getConfig().entrySet()) {
        fsConf.set(prop.getKey(), prop.getValue());
      }
    }

    if (!Strings.isEmpty(config.getConnection())) {
      FileSystem.setDefaultUri(fsConf, config.getConnection());
    }

    Map<String,String> map =  ImmutableMap.of(
            "fs.classpath.impl", ClassPathFileSystem.class.getName(),
            "fs.dremio-local.impl", LocalSyncableFileSystem.class.getName()
    );
    for(Entry<String, String> prop : map.entrySet()) {
      fsConf.set(prop.getKey(), prop.getValue());
    }

    this.optionExtractor = new FormatPluginOptionExtractor(context.getClasspathScan());
    this.matchers = Lists.newArrayList();
    this.formatCreator = new FormatCreator(context, config, context.getClasspathScan(), this);
    this.codecFactory = new CompressionCodecFactory(fsConf);

    for (FormatMatcher m : formatCreator.getFormatMatchers()) {
      matchers.add(m);
    }

//    boolean footerNoSeek = contetMutext.getOptionManager().getOption(ExecConstants.PARQUET_FOOTER_NOSEEK);

    // NOTE: Add fallback format matcher if given in the configuration. Make sure fileMatchers is an order-preserving list.
    if(fs == null) {
      this.fs = getFS(SYSTEM_USERNAME);
    }
    dropFileMatchers = matchers.subList(0, matchers.size());
  }

  public FormatPlugin getFormatPlugin(String name) {
    return formatCreator.getFormatPluginByName(name);
  }

  public FormatPlugin getFormatPlugin(FormatPluginConfig config) {
    FormatPlugin plugin = formatCreator.getFormatPluginByConfig(config);
    if (plugin == null) {
      plugin = formatCreator.newFormatPlugin(config);
    }
    return plugin;
  }

  protected FileSystemCachedEntity fromFileStatus(FileStatus status){
    return new FileSystemCachedEntity()
        .setPath(status.getPath().toString())
        .setLastModificationTime(status.getModificationTime());
  }

  @Override
  public boolean hasAccessPermission(String user, NamespaceKey key, DatasetConfig datasetConfig) {
    if (config.isImpersonationEnabled()) {
      if (datasetConfig.getReadDefinition() != null) { // allow accessing partial datasets
        final FileSystemWrapper userFs = getFS(user);
        final List<TimedRunnable<Boolean>> permissionCheckTasks = Lists.newArrayList();

        permissionCheckTasks.addAll(getUpdateKeyPermissionTasks(datasetConfig, userFs));
        permissionCheckTasks.addAll(getSplitPermissiomTasks(datasetConfig, userFs, user));

        try {
          Stopwatch stopwatch = Stopwatch.createStarted();
          final List<Boolean> accessPermissions = TimedRunnable.run("check access permission for " + key, logger, permissionCheckTasks, 16);
          stopwatch.stop();
          logger.debug("Checking access permission for {} took {} ms", key, stopwatch.elapsed(TimeUnit.MILLISECONDS));
          for (Boolean permission : accessPermissions) {
            if (!permission) {
              return false;
            }
          }
        } catch (IOException ioe) {
          throw UserException.dataReadError(ioe).build(logger);
        }
      }
    }
    return true;
  }

  // Check if all sub directories can be listed/read
  private Collection<FsPermissionTask> getUpdateKeyPermissionTasks(DatasetConfig datasetConfig, FileSystemWrapper userFs) {
    final FileUpdateKey fileUpdateKey = new FileUpdateKey();
    ProtostuffIOUtil.mergeFrom(datasetConfig.getReadDefinition().getReadSignature().toByteArray(), fileUpdateKey, fileUpdateKey.getSchema());
    if (fileUpdateKey.getCachedEntitiesList() == null || fileUpdateKey.getCachedEntitiesList().isEmpty()) {
      return Collections.emptyList();
    }
    final List<FsPermissionTask> fsPermissionTasks = Lists.newArrayList();
    final FsAction action;
    final List<Path> batch = Lists.newArrayList();

    //DX-7850 : remove once solution for maprfs is found
    if (userFs.isMapRfs()) {
      action = FsAction.READ;
    } else {
      action = FsAction.READ_EXECUTE;
    }

    for (FileSystemCachedEntity cachedEntity : fileUpdateKey.getCachedEntitiesList()) {
      batch.add(new Path(cachedEntity.getPath()));
      if (batch.size() == PERMISSION_CHECK_TASK_BATCH_SIZE) {
        // make a copy of batch
        fsPermissionTasks.add(new FsPermissionTask(userFs, Lists.newArrayList(batch), action));
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      fsPermissionTasks.add(new FsPermissionTask(userFs, batch, action));
    }
    return fsPermissionTasks;
  }

  // Check if all splits are accessible
  private Collection<FsPermissionTask> getSplitPermissiomTasks(DatasetConfig datasetConfig, FileSystemWrapper userFs, String user) {
    final SplitsPointer splitsPointer = DatasetSplitsPointer.of(context.getNamespaceService(user), datasetConfig);
    final boolean isParquet = datasetConfig.getPhysicalDataset().getFormatSettings().getType() == FileType.PARQUET;
    final List<FsPermissionTask> fsPermissionTasks = Lists.newArrayList();
    final List<Path> batch = Lists.newArrayList();

    for (DatasetSplit split:  splitsPointer.getSplitIterable()) {
      final Path filePath;
      if (isParquet) {
        filePath = new Path(PARQUET_DATASET_SPLIT_XATTR_SERIALIZER.revert(split.getExtendedProperty().toByteArray()).getPath());
      } else {
        filePath = new Path(EASY_DATASET_SPLIT_XATTR_SERIALIZER.revert(split.getExtendedProperty().toByteArray()).getPath());
      }

      batch.add(filePath);
      if (batch.size() == PERMISSION_CHECK_TASK_BATCH_SIZE) {
        // make a copy of batch
        fsPermissionTasks.add(new FsPermissionTask(userFs, new ArrayList<>(batch), FsAction.READ));
        batch.clear();
      }
    }

    if (!batch.isEmpty()) {
      fsPermissionTasks.add(new FsPermissionTask(userFs, batch, FsAction.READ));
    }

    return fsPermissionTasks;
  }

  private class FsPermissionTask extends TimedRunnable<Boolean> {
    private final FileSystemWrapper userFs;
    private final List<Path> cachedEntityPaths;
    private final FsAction permission;

    FsPermissionTask(FileSystemWrapper userFs, List<Path> cachedEntityPaths, FsAction permission) {
      this.userFs = userFs;
      this.cachedEntityPaths = cachedEntityPaths;
      this.permission = permission;
    }

    @Override
    protected IOException convertToIOException(Exception e) {
      if (e instanceof IOException) {
        return (IOException) e;
      }
      return new IOException(e);
    }

    @Override
    protected Boolean runInner() throws Exception {
      for (Path cachedEntityPath:  cachedEntityPaths) {
        try {
          userFs.access(cachedEntityPath, permission);
        } catch (AccessControlException ace) {
          return false;
        }
      }
      return true;
    }
  }

  public FileSystemConfig getFileSystemConfig() {
    return config;
  }

  public FileSystemWrapper getFs() {
    return fs;
  }

  public String getName() {
    return name;
  }

  @Override
  public boolean containerExists(NamespaceKey key) {
    final List<String> folderPath = key.getPathComponents();
    try {
      return getFS(SYSTEM_USERNAME).isDirectory(PathUtils.toFSPath(resolveTableNameToValidPath(folderPath)));
    } catch (IOException e) {
      logger.debug("Failure reading path.", e);
      return false;
    }
  }

  @Override
  public boolean datasetExists(NamespaceKey key) {
    final List<String> filePath = key.getPathComponents();
    try {
      return getFS(SYSTEM_USERNAME).exists(PathUtils.toFSPath(resolveTableNameToValidPath(filePath)));
    } catch (IOException e) {
      logger.debug("Failure reading path.", e);
      return false;
    }
  }

  protected boolean fileExists(String username, List<String> filePath) throws IOException {
    return getFS(username).isFile(PathUtils.toFSPath(resolveTableNameToValidPath(filePath)));
  }

  @Override
  public CheckResult checkReadSignature(ByteString key, DatasetConfig oldConfig) throws Exception {
    FileUpdateKey fileUpdateKey = new FileUpdateKey();
    ProtostuffIOUtil.mergeFrom(key.toByteArray(), fileUpdateKey, FileUpdateKey.getSchema());

    if (fileUpdateKey.getCachedEntitiesList() == null || fileUpdateKey.getCachedEntitiesList().isEmpty()) {
      // single file dataset
      Preconditions.checkArgument(oldConfig.getType() == DatasetType.PHYSICAL_DATASET_SOURCE_FILE, "only file based datasets can have empty read signature");
      if (!fileExists(SYSTEM_USERNAME, oldConfig.getFullPathList())) {
        return CheckResult.DELETED;
      } else {
        // assume file has changed
        final SourceTableDefinition newDatasetAccessor = getDataset(new NamespaceKey(oldConfig.getFullPathList()), oldConfig, false);
        return new CheckResult() {
          @Override
          public UpdateStatus getStatus() {
            return UpdateStatus.CHANGED;
          }

          @Override
          public SourceTableDefinition getDataset() {
            return newDatasetAccessor;
          }
        };
      }
    }

    final UpdateStatus status = checkMultifileStatus(fileUpdateKey);
    switch(status) {
    case DELETED:
      return CheckResult.DELETED;
    case UNCHANGED:
      return CheckResult.UNCHANGED;
    case CHANGED:
      // continue below.
      break;
    default:
      throw new UnsupportedOperationException(status.name());
    }


    final SourceTableDefinition newDatasetAccessor = getDataset(new NamespaceKey(oldConfig.getFullPathList()), oldConfig, false);
    return new CheckResult() {
      @Override
      public UpdateStatus getStatus() {
        return UpdateStatus.CHANGED;
      }

      @Override
      public SourceTableDefinition getDataset() {
        return newDatasetAccessor;
      }
    };

  }

  /**
   * Given a file update key, determine whether the source system has changed since we last read the status.
   * @param fileUpdateKey
   * @return The type of status change.
   */
  private UpdateStatus checkMultifileStatus(FileUpdateKey fileUpdateKey) {
    final List<FileSystemCachedEntity> cachedEntities = fileUpdateKey.getCachedEntitiesList();
    for (int i = 0; i < cachedEntities.size(); ++i) {
      final FileSystemCachedEntity cachedEntity = cachedEntities.get(i);
      final Path cachedEntityPath =  new Path(cachedEntity.getPath());
      try {

        final Optional<FileStatus> optionalStatus = fs.getFileStatusSafe(cachedEntityPath);
        if(!optionalStatus.isPresent()) {
          // if first entity (root) is missing then table is deleted
          if (i == 0) {
            return UpdateStatus.DELETED;
          }
          // missing directory force update for this dataset
          return UpdateStatus.CHANGED;
        }

        if(cachedEntity.getLastModificationTime() == 0) {
          // this system doesn't support modification times, no need to further probe (S3)
          return UpdateStatus.CHANGED;
        }

        final FileStatus updatedFileStatus = optionalStatus.get();
        final long updatedModificationTime = updatedFileStatus.getModificationTime();
        Preconditions.checkArgument(updatedFileStatus.isDirectory(), "fs based dataset update key must be composed of directories");
        if (cachedEntity.getLastModificationTime() < updatedModificationTime) {
          // the file/folder has been changed since our last check.
          return UpdateStatus.CHANGED;
        }

      } catch (IOException ioe) {
        // continue with other cached entities
        logger.error("Failed to get status for {}", cachedEntityPath, ioe);
        return UpdateStatus.CHANGED;
      }
    }

    return UpdateStatus.UNCHANGED;
  }

  public SabotContext getContext() {
    return context;
  }

  @Override
  public void close() {
  }

  public boolean createView(List<String> tableSchemaPath, View view, SchemaConfig schemaConfig) throws IOException {
    Path viewPath = getViewPath(tableSchemaPath);
    FileSystemWrapper fs = getFS(schemaConfig.getUserName());
    boolean replaced = fs.exists(viewPath);
    final FsPermission viewPerms =
            new FsPermission(schemaConfig.getOption(ExecConstants.NEW_VIEW_DEFAULT_PERMS_KEY).string_val);
    try (OutputStream stream = FileSystemWrapper.create(fs, viewPath, viewPerms)) {
      lpPersistance.getMapper().writeValue(stream, view);
    }
    return replaced;
  }

  public void dropView(SchemaConfig schemaConfig, List<String> tableSchemaPath) throws IOException {
    getFS(schemaConfig.getUserName()).delete(getViewPath(tableSchemaPath), false);
  }

  private Path getViewPath(List<String> tableSchemaPath) {
    List<String> fullPath = resolveTableNameToValidPath(tableSchemaPath);
    String parentPath = PathUtils.toFSPathString(fullPath.subList(0, fullPath.size() - 1));
    return DotFileType.VIEW.getPath(parentPath, tableSchemaPath.get(tableSchemaPath.size() - 1));
  }

  public FormatPluginOptionExtractor getOptionExtractor() {
    return optionExtractor;
  }

  public List<Function> getFunctions(List<String> tableSchemaPath, SchemaConfig schemaConfig) {
    return optionExtractor.getFunctions(tableSchemaPath, this, schemaConfig);
  }


  private FormatMatcher findMatcher(FileSystemWrapper fs, FileSelection file) {
    FormatMatcher matcher = null;
    try {
      for (FormatMatcher m : dropFileMatchers) {

        if (m.matches(fs, file, codecFactory)) {
          return m;
        }
      }
    } catch (IOException e) {
      logger.debug("Failed to find format matcher for file: %s", file, e);
    }
    return matcher;
  }

  private boolean isHomogeneous(FileSystemWrapper fs, FileSelection fileSelection) throws IOException {
    FormatMatcher matcher = null;
    FileSelection noDir = fileSelection.minusDirectories();

    for(FileStatus s : noDir.getStatuses()) {
      FileSelection subSelection = FileSelection.create(s);
      if (matcher == null) {
        matcher = findMatcher(fs, subSelection);
        if(matcher == null) {
          return false;
        }
      }

      if(!matcher.matches(fs, subSelection, codecFactory)) {
        return false;
      }
    }
    return true;
  }

  /**
   * We check if the table contains homogeneous file formats that Dremio can read. Once the checks are performed
   * we rename the file to start with an "_". After the rename we issue a recursive delete of the directory.
   */
  public void dropTable(List<String> tableSchemaPath, SchemaConfig schemaConfig) {
    FileSystemWrapper fs = getFS(schemaConfig.getUserName());
    String defaultLocation = config.getPath();
    List<String> fullPath = resolveTableNameToValidPath(tableSchemaPath);
    FileSelection fileSelection;
    try {
      fileSelection = FileSelection.create(fs, fullPath);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    if (fileSelection == null) {
      throw UserException
          .validationError()
          .message(String.format("Table [%s] not found", SqlUtils.quotedCompound(tableSchemaPath)))
          .build(logger);
    }

    try {
      if (!isHomogeneous(fs, fileSelection)) {
        throw UserException
                .validationError()
                .message("Table contains different file formats. \n" +
                        "Drop Table is only supported for directories that contain homogeneous file formats consumable by Dremio")
                .build(logger);
      }

      // Generate unique identifier which will be added as a suffix to the table name
      ThreadLocalRandom r = ThreadLocalRandom.current();
      long time =  (System.currentTimeMillis()/1000);
      Long p1 = ((Integer.MAX_VALUE - time) << 32) + r.nextInt();
      Long p2 = r.nextLong();
      final String fileNameDelimiter = FileSystemWrapper.HIDDEN_FILE_PREFIX;
      final String newFileName = FileSystemWrapper.HIDDEN_FILE_PREFIX + fullPath.get(fullPath.size() - 1) + fileNameDelimiter
              + p1 + fileNameDelimiter + p2;

      List<String> newFullPath = ImmutableList.<String>builder().addAll(fullPath.subList(0, fullPath.size() - 1)).add(newFileName).build();

      fs.rename(new Path(defaultLocation, PathUtils.toFSPath(fullPath)), new Path(defaultLocation, PathUtils.toFSPath(newFullPath)));
      fs.delete(new Path(defaultLocation, PathUtils.toFSPath(newFullPath)), true);
    } catch (AccessControlException e) {
      throw UserException
              .permissionError(e)
              .message("Unauthorized to drop table")
              .build(logger);
    } catch (IOException e) {
      throw UserException
              .dataWriteError(e)
              .message("Failed to drop table: " + e.getMessage())
              .build(logger);
    }
  }


  public List<SchemaEntity> list(List<String> folderPath, String userName) {
    try {
      final List<FileStatus> files = Lists.newArrayList(getFS(userName).listStatus(PathUtils.toFSPath(resolveTableNameToValidPath(folderPath))));
      final Set<List<String>> tableNames = Sets.newHashSet();
      final NamespaceService ns = context.getNamespaceService(userName);
      final NamespaceKey folderNSKey = new NamespaceKey(folderPath);
      if (ns.exists(folderNSKey, Type.DATASET)) {
        // if the folder is a dataset, then there is nothing to list
        return ImmutableList.of();
      }
      if (ns.exists(folderNSKey)) {
        for(NameSpaceContainer entity : ns.list(folderNSKey)) {
          if (entity.getType() == Type.DATASET) {
            tableNames.add(resolveTableNameToValidPath(entity.getDataset().getFullPathList()));
          }
        }
      }

      Iterable<SchemaEntity> itr = Iterables.transform(files, new com.google.common.base.Function<FileStatus, SchemaEntity>() {
        @Nullable
        @Override
        public SchemaEntity apply(@Nullable FileStatus input) {
          List<String> p = PathUtils.toPathComponents(input.getPath());
          if (input.isDirectory()) {
            if (tableNames.contains(p)) {
              return new SchemaEntity(PathUtils.getQuotedFileName(input.getPath()), SchemaEntityType.FOLDER_TABLE, input.getOwner());
            } else {
              return new SchemaEntity(PathUtils.getQuotedFileName(input.getPath()), SchemaEntityType.FOLDER, input.getOwner());
            }
          } else {
            if (tableNames.contains(p)) {
              return new SchemaEntity(PathUtils.getQuotedFileName(input.getPath()), SchemaEntityType.FILE_TABLE, input.getOwner());
            } else {
              return new SchemaEntity(PathUtils.getQuotedFileName(input.getPath()), SchemaEntityType.FILE, input.getOwner());
            }
          }
        }
      });
      return ImmutableList.<SchemaEntity>builder().addAll(itr).build();
    } catch (IOException e) {
      throw new RuntimeException(e);
    } catch (NamespaceException e) {
      throw new RuntimeException(e);
    }
  }


  public SchemaMutability getMutability() {
    return config.getSchemaMutability();
  }

  public FormatPluginConfig createConfigForTable(String tableName, Map<String, Object> storageOptions) {
    return optionExtractor.createConfigForTable(tableName, storageOptions);
  }

  public FileSystem getProcessFs() {
    return fs;
  }


}
