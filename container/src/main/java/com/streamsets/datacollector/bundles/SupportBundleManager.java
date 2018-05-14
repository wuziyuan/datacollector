/*
 * Copyright 2017 StreamSets Inc.
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
package com.streamsets.datacollector.bundles;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.internal.StaticCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.streamsets.datacollector.execution.PipelineStateStore;
import com.streamsets.datacollector.execution.SnapshotStore;
import com.streamsets.datacollector.json.ObjectMapperFactory;
import com.streamsets.datacollector.main.BuildInfo;
import com.streamsets.datacollector.main.RuntimeInfo;
import com.streamsets.datacollector.task.AbstractTask;
import com.streamsets.datacollector.util.Configuration;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.datacollector.store.PipelineStoreTask;
import com.streamsets.pipeline.lib.executor.SafeScheduledExecutorService;
import org.apache.commons.lang3.ArrayUtils;
import org.cloudera.log4j.redactor.StringRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Main manager that is taking care of bundle creation.
 */
public class SupportBundleManager extends AbstractTask implements BundleContext {

  private static final Logger LOG = LoggerFactory.getLogger(SupportBundleManager.class);

  /**
   * Executor service for generating new bundles.
   *
   * They are generated by a different thread and piped out via generateNewBundle() call.
   */
  private final ExecutorService executor;

  private final Configuration configuration;
  private final PipelineStoreTask pipelineStore;
  private final PipelineStateStore stateStore;
  private final SnapshotStore snapshotStore;
  private final RuntimeInfo runtimeInfo;
  private final BuildInfo buildInfo;

  /**
   * List describing auto discovered content generators.
   */
  private List<BundleContentGeneratorDefinition> definitions;
  private Map<String, BundleContentGeneratorDefinition> definitionMap;

  /**
   * Redactor to remove sensitive data.
   */
  private StringRedactor redactor;

  @Inject
  public SupportBundleManager(
    @Named("supportBundleExecutor") SafeScheduledExecutorService executor,
    Configuration configuration,
    PipelineStoreTask pipelineStore,
    PipelineStateStore stateStore,
    SnapshotStore snapshotStore,
    RuntimeInfo runtimeInfo,
    BuildInfo buildInfo
  ) {
    super("Support Bundle Manager");
    this.executor = executor;
    this.configuration = configuration;
    this.pipelineStore = pipelineStore;
    this.stateStore = stateStore;
    this.snapshotStore = snapshotStore;
    this.runtimeInfo = runtimeInfo;
    this.buildInfo = buildInfo;
  }

  @Override
  protected void initTask() {
    Set<String> ids = new HashSet<>();

    ImmutableList.Builder builder = new ImmutableList.Builder();
    try {
      InputStream generatorResource = Thread.currentThread().getContextClassLoader().getResourceAsStream(SupportBundleContentGeneratorProcessor.RESOURCE_NAME);
      BufferedReader reader = new BufferedReader(new InputStreamReader(generatorResource));
      String className;
      while((className = reader.readLine()) != null) {
        Class<? extends BundleContentGenerator> bundleClass = (Class<? extends BundleContentGenerator>) Class.forName(className);

        BundleContentGeneratorDef def = bundleClass.getAnnotation(BundleContentGeneratorDef.class);
        if(def == null) {
          LOG.error("Bundle creator class {} is missing required annotation", bundleClass.getName());
          continue;
        }

        String id = bundleClass.getSimpleName();
        if(!def.id().isEmpty()) {
          id = def.id();
        }

        if(ids.contains(id)) {
          LOG.error("Ignoring duplicate id {} for generator {}.", id, bundleClass.getName());
        } else {
          ids.add(id);
        }

        builder.add(new BundleContentGeneratorDefinition(
          bundleClass,
          def.name(),
          id,
          def.description(),
          def.version(),
          def.enabledByDefault(),
          def.order()
        ));
      }
    } catch (Exception e) {
      LOG.error("Was not able to initialize support bundle generator classes.", e);
    }

    definitions = builder.build();

    definitionMap = new HashMap<>();
    for (BundleContentGeneratorDefinition definition : definitions) {
      definitionMap.put(definition.getId(), definition);
    }

    // Create shared instance of redactor
    try {
      redactor = StringRedactor.createFromJsonFile(runtimeInfo.getConfigDir() + "/" + Constants.REDACTOR_CONFIG);
    } catch (IOException e) {
      LOG.error("Can't load redactor configuration, bundles will not be redacted", e);
      redactor = StringRedactor.createEmpty();
    }
  }

  /**
   * Returns immutable list with metadata of registered content generators.
   */
  public List<BundleContentGeneratorDefinition> getContentDefinitions() {
    return definitions;
  }

  /**
   * Return InputStream from which a new generated resource bundle can be retrieved.
   */
  public SupportBundle generateNewBundle(List<String> generators) throws IOException {
    List<BundleContentGeneratorDefinition> defs = getRequestedDefinitions(generators);
    return generateNewBundle(defs.stream().map(def -> def.createInstance()).collect(Collectors.toList()), true);
  }

  /**
   * Return InputStream from which a new generated resource bundle can be retrieved.
   */
  public SupportBundle generateNewBundle(List<BundleContentGenerator> generators, boolean includeMetadata)
      throws IOException {
    PipedInputStream inputStream = new PipedInputStream();
    PipedOutputStream outputStream = new PipedOutputStream();
    inputStream.connect(outputStream);
    ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);

    executor.submit(() -> generateNewBundleInternal(generators, includeMetadata, zipOutputStream));

    String bundleName = generateBundleName();
    String bundleKey = generateBundleDate() + "/" + bundleName;

    return new SupportBundle(
      bundleKey,
      bundleName,
      inputStream
    );
  }

  /**
   * Instead of providing support bundle directly to user, upload it to StreamSets backend services.
   */
  public void uploadNewBundle(List<String> generators) throws IOException {
    boolean enabled = configuration.get(Constants.UPLOAD_ENABLED, Constants.DEFAULT_UPLOAD_ENABLED);
    String accessKey = configuration.get(Constants.UPLOAD_ACCESS, Constants.DEFAULT_UPLOAD_ACCESS);
    String secretKey = configuration.get(Constants.UPLOAD_SECRET, Constants.DEFAULT_UPLOAD_SECRET);
    String bucket = configuration.get(Constants.UPLOAD_BUCKET, Constants.DEFAULT_UPLOAD_BUCKET);
    int bufferSize = configuration.get(Constants.UPLOAD_BUFFER_SIZE, Constants.DEFAULT_UPLOAD_BUFFER_SIZE);

    if(!enabled) {
      throw new IOException("Uploading support bundles was disabled by administrator.");
    }

    AWSCredentialsProvider credentialsProvider = new StaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey));
    AmazonS3Client s3Client = new AmazonS3Client(credentialsProvider, new ClientConfiguration());
    s3Client.setS3ClientOptions(new S3ClientOptions().withPathStyleAccess(true));
    s3Client.setRegion(Region.getRegion(Regions.US_WEST_2));

    // Object Metadata
    ObjectMetadata metadata = new ObjectMetadata();
    for(Map.Entry<Object, Object> entry: getMetadata().entrySet()) {
      metadata.addUserMetadata((String)entry.getKey(), (String)entry.getValue());
    }

    // Generate bundle
    SupportBundle bundle = generateNewBundle(generators);

    // Uploading part by part
    LOG.info("Initiating multi-part support bundle upload");
    List<PartETag> partETags = new ArrayList<>();
    InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucket, bundle.getBundleKey());
    initRequest.setObjectMetadata(metadata);
    InitiateMultipartUploadResult initResponse = s3Client.initiateMultipartUpload(initRequest);

    try {
      byte[] buffer = new byte[bufferSize];
      int partId = 1;
      int size = -1;
      while ((size = readFully(bundle.getInputStream(), buffer)) != -1) {
        LOG.debug("Uploading part {} of size {}", partId, size);
        UploadPartRequest uploadRequest = new UploadPartRequest()
          .withBucketName(bucket)
          .withKey(bundle.getBundleKey())
          .withUploadId(initResponse.getUploadId())
          .withPartNumber(partId++)
          .withInputStream(new ByteArrayInputStream(buffer))
          .withPartSize(size);

        partETags.add(s3Client.uploadPart(uploadRequest).getPartETag());
      }

      CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest(
        bucket,
        bundle.getBundleKey(),
        initResponse.getUploadId(),
        partETags
      );

      s3Client.completeMultipartUpload(compRequest);
      LOG.info("Support bundle upload finished");
    } catch (Exception e) {
      LOG.error("Support bundle upload failed", e);
      s3Client.abortMultipartUpload(new AbortMultipartUploadRequest(
        bucket,
        bundle.getBundleKey(),
        initResponse.getUploadId())
      );

      throw new IOException("Can't upload support bundle", e);
    } finally {
      // Close the client
      s3Client.shutdown();
    }
  }

  /**
   * Try to upload bundle as part of internal SDC error (for example failing pipeline).
   */
  public void uploadNewBundleOnError() {
    boolean enabled = configuration.get(Constants.UPLOAD_ON_ERROR, Constants.DEFAULT_UPLOAD_ON_ERROR);
    LOG.info("Upload bundle on error: {}", enabled);

    // We won't upload the bundle unless it's explicitly allowed
    if(!enabled) {
      return;
    }

    try {
      uploadNewBundle(Collections.emptyList());
    } catch (IOException e) {
      LOG.error("Failed to upload error bundle", e);
    }
  }

  /**
   * This method will read from the input stream until the whole buffer is loaded up with actual bytes or end of stream
   * has been reached. Hence it will return buffer.length of all executions except the last two - one to the last call
   * will return less then buffer.length (reminder of the data) and returns -1 on any subsequent calls.
   */
  private int readFully(InputStream inputStream, byte []buffer) throws IOException {
    int readBytes = 0;

    while(readBytes < buffer.length) {
      int loaded = inputStream.read(buffer, readBytes, buffer.length - readBytes);
      if(loaded == -1) {
        return readBytes == 0 ? -1 : readBytes;
      }

      readBytes += loaded;
    }

    return readBytes;
  }

  private String getCustomerId() {
    File customerIdFile = new File(runtimeInfo.getDataDir(), Constants.CUSTOMER_ID_FILE);
    if(!customerIdFile.exists()) {
      return Constants.DEFAULT_CUSTOMER_ID;
    }

    try {
      return com.google.common.io.Files.readFirstLine(customerIdFile, StandardCharsets.UTF_8).trim();
    } catch (IOException ex) {
      throw new RuntimeException(Utils.format("Could not read customer ID file '{}': {}", customerIdFile, ex.toString()), ex);
    }
  }

  private String generateBundleDate() {
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    return dateFormat.format(new Date());
  }

  private String generateBundleName() {
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");
    StringBuilder builder = new StringBuilder("bundle_");
    builder.append(getCustomerId());
    builder.append("_");
    builder.append(runtimeInfo.getId());
    builder.append("_");
    builder.append(dateFormat.format(new Date()));
    builder.append(".zip");

    return builder.toString();
  }

  /**
   * Orchestrate what definitions should be used for this bundle.
   *
   * Either get all definitions that should be used by default or only those specified in the generators argument.
   */
  private List<BundleContentGeneratorDefinition> getRequestedDefinitions(List<String> generators) {
    Stream<BundleContentGeneratorDefinition> stream = definitions.stream();
    if(generators == null || generators.isEmpty()) {
      // Filter out default generators
      stream = stream.filter(BundleContentGeneratorDefinition::isEnabledByDefault);
    } else {
      stream = stream.filter(def -> generators.contains(def.getId()));
    }
    return stream
      .sorted(Comparator.comparingInt(BundleContentGeneratorDefinition::getOrder))
      .collect(Collectors.toList());
  }

  private void generateNewBundleInternal(
      List<BundleContentGenerator> generators, boolean includeMetadata, ZipOutputStream zipStream
  ) {
    try {
      Properties runGenerators = new Properties();
      Properties failedGenerators = new Properties();

      // Let each individual content generator run to generate it's content
      for(BundleContentGenerator generator : generators) {
        BundleContentGeneratorDefinition definition = definitionMap.get(generator.getClass().getSimpleName());
        BundleWriterImpl writer = new BundleWriterImpl(
          definition.getKlass().getName(),
          redactor,
          zipStream
        );

        try {
          LOG.debug("Generating content with {} generator", definition.getKlass().getName());
          generator.generateContent(this, writer);
          runGenerators.put(definition.getKlass().getName(), String.valueOf(definition.getVersion()));
        } catch (Throwable t) {
          LOG.error("Generator {} failed", definition.getName(), t);
          failedGenerators.put(definition.getKlass().getName(), String.valueOf(definition.getVersion()));
          writer.ensureEndOfFile();
        }
      }

      if (includeMetadata) {
        // generators.properties
        zipStream.putNextEntry(new ZipEntry("generators.properties"));
        runGenerators.store(zipStream, "");
        zipStream.closeEntry();

        // failed_generators.properties
        zipStream.putNextEntry(new ZipEntry("failed_generators.properties"));
        failedGenerators.store(zipStream, "");
        zipStream.closeEntry();

        // metadata.properties
        zipStream.putNextEntry(new ZipEntry("metadata.properties"));
        getMetadata().store(zipStream, "");
        zipStream.closeEntry();
      }

    } catch (Exception e) {
      LOG.error("Failed to generate resource bundle", e);
    } finally {
      // And that's it
      try {
        zipStream.close();
      } catch (IOException e) {
        LOG.error("Failed to finish generating the bundle", e);
      }
    }
  }

  private Properties getMetadata() {
    Properties metadata = new Properties();
    metadata.put("version", "1");
    metadata.put("sdc.version", buildInfo.getVersion());
    metadata.put("sdc.id", runtimeInfo.getId());
    metadata.put("sdc.acl.enabled", String.valueOf(runtimeInfo.isAclEnabled()));
    metadata.put("customer.id", getCustomerId());

    return metadata;
  }

  @Override
  public Configuration getConfiguration() {
    return configuration;
  }

  @Override
  public BuildInfo getBuildInfo() {
    return buildInfo;
  }

  @Override
  public RuntimeInfo getRuntimeInfo() {
    return runtimeInfo;
  }

  @Override
  public PipelineStoreTask getPipelineStore() {
    return pipelineStore;
  }

  @Override
  public PipelineStateStore getPipelineStateStore() {
    return stateStore;
  }

  @Override
  public SnapshotStore getSnapshotStore() {
    return snapshotStore;
  }

  private static class BundleWriterImpl implements BundleWriter {

    private boolean insideFile;
    private final String prefix;
    private final StringRedactor redactor;
    private final ZipOutputStream zipOutputStream;

    public BundleWriterImpl(
      String prefix,
      StringRedactor redactor,
      ZipOutputStream outputStream
    ) {
      this.prefix = prefix + File.separator;
      this.redactor = redactor;
      this.zipOutputStream = outputStream;
      this.insideFile = false;
    }

    @Override
    public void markStartOfFile(String name) throws IOException {
      zipOutputStream.putNextEntry(new ZipEntry(prefix + name));
      insideFile = true;
    }

    @Override
    public void markEndOfFile() throws IOException {
      zipOutputStream.closeEntry();
      insideFile = false;
    }

    public void ensureEndOfFile() throws IOException {
      if(insideFile) {
        markEndOfFile();
      }
    }

    public void writeInternal(String string, boolean ln) throws IOException {
      zipOutputStream.write(redactor.redact(string).getBytes());
      if(ln) {
        zipOutputStream.write('\n');
      }
    }

    @Override
    public void write(String str) throws IOException {
      writeInternal(str, false);
    }

    @Override
    public void writeLn(String str) throws IOException {
      writeInternal(str, true);
    }

    @Override
    public void write(String fileName, Properties properties) throws IOException {
      markStartOfFile(fileName);

      for(Map.Entry<Object, Object> entry: properties.entrySet()) {
        String key = (String) entry.getKey();
        String value = (String) entry.getValue();

        writeLn(Utils.format("{}={}", key, value));
      }

      markEndOfFile();
    }

    @Override
    public void write(String fileName, InputStream inputStream) throws IOException {
      try(BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
        copyReader(reader, fileName, 0);
      }
    }

    @Override
    public void write(String dir, Path path) throws IOException {
      write(dir, path, 0);
    }

    @Override
    public void write(String dir, Path path, long startOffset) throws IOException {
      // We're not interested in serializing non-existing files
      if(!Files.exists(path)) {
        return;
      }

      try (BufferedReader reader = Files.newBufferedReader(path)) {
        copyReader(reader, dir + "/" + path.getFileName(), startOffset);
      }
    }

    @Override
    public void writeJson(String fileName, Object object) throws IOException {
      ObjectMapper objectMapper = ObjectMapperFactory.get();
      markStartOfFile(fileName);
      write(objectMapper.writeValueAsString(object));
      markEndOfFile();
    }

    @Override
    public JsonGenerator createGenerator(String fileName) throws IOException {
      markStartOfFile(fileName);
      return new JsonFactory().createGenerator(new JsonGeneratorOutputStream(zipOutputStream, redactor));
    }

    private void copyReader(BufferedReader reader, String path, long startOffset) throws IOException {
      markStartOfFile(path);

      if(startOffset > 0) {
        reader.skip(startOffset);
      }

      String line = null;
      while ((line = reader.readLine()) != null) {
        writeLn(line);
      }

      markEndOfFile();
    }
  }

  private static class JsonGeneratorOutputStream extends OutputStream {

    private final ArrayList<Byte> bytes;
    private final ZipOutputStream zipOutputStream;
    private final StringRedactor redactor;

    public JsonGeneratorOutputStream(ZipOutputStream stream, StringRedactor redactor) {
      this.bytes = new ArrayList<>();
      this.zipOutputStream = stream;
      this.redactor = redactor;
    }

    @Override
    public void write(int b) throws IOException {
      // Add the byte to the line
      bytes.add((byte)b);

      // If it's final line, write the data out
      if(b == '\n') {
        writeOut();
      }
    }

    private void writeOut() throws IOException {
      byte[] byteLine = ArrayUtils.toPrimitive(bytes.toArray(new Byte[bytes.size()]));
      String string = new String(byteLine, Charset.defaultCharset());
      zipOutputStream.write(redactor.redact(string).getBytes());

      bytes.clear();
    }

    @Override
    public void close() throws IOException {
      // Write down remaining bytes, but do not close the underlying zip stream
      writeOut();
    }
  }
}
