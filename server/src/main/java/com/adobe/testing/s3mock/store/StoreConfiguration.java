/*
 *  Copyright 2017-2023 Adobe.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.adobe.testing.s3mock.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StoreProperties.class)
public class StoreConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(StoreConfiguration.class);
  static final DateTimeFormatter S3_OBJECT_DATE_FORMAT = DateTimeFormatter
      .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneId.of("UTC"));

  @Bean
  ObjectStore objectStore(StoreProperties properties, List<String> bucketNames,
      BucketStore bucketStore, ObjectMapper objectMapper) {
    var objectStore = new ObjectStore(properties.retainFilesOnExit(),
        S3_OBJECT_DATE_FORMAT, objectMapper);
    for (var bucketName : bucketNames) {
      var bucketMetadata = bucketStore.getBucketMetadata(bucketName);
      if (bucketMetadata != null) {
        objectStore.loadObjects(bucketMetadata, bucketMetadata.objects().values());
      }
    }
    return objectStore;
  }

  @Bean
  BucketStore bucketStore(StoreProperties properties, File rootFolder, List<String> bucketNames,
      ObjectMapper objectMapper) {
    var bucketStore = new BucketStore(rootFolder, properties.retainFilesOnExit(),
        S3_OBJECT_DATE_FORMAT, objectMapper);
    if (bucketNames.isEmpty()) {
      properties
          .initialBuckets()
          .forEach(bucketName -> {
            bucketStore.createBucket(bucketName, false);
            LOG.info("Creating initial bucket {}.", bucketName);
          });
    } else {
      bucketStore.loadBuckets(bucketNames);
    }

    return bucketStore;
  }

  @Bean
  List<String> bucketNames(File rootFolder) {
    var buckets = rootFolder.listFiles();
    if (buckets != null) {
      return Arrays.stream(buckets).map(File::getName).toList();
    } else {
      return Collections.emptyList();
    }
  }

  @Bean
  MultipartStore multipartStore(StoreProperties properties, ObjectStore objectStore) {
    return new MultipartStore(properties.retainFilesOnExit(), objectStore);
  }

  @Bean
  KmsKeyStore kmsKeyStore(StoreProperties properties) {
    return new KmsKeyStore(properties.validKmsKeys());
  }

  @Bean
  File rootFolder(StoreProperties properties) {
    File root;
    var createTempDir = properties.root() == null || properties.root().isEmpty();

    if (createTempDir) {
      var baseTempDir = FileUtils.getTempDirectory().toPath();
      try {
        root = Files.createTempDirectory(baseTempDir, "s3mockFileStore").toFile();
      } catch (IOException e) {
        throw new IllegalStateException("Root folder could not be created. Base temp dir: "
            + baseTempDir, e);
      }

      LOG.info("Successfully created \"{}\" as root folder. Will retain files on exit: {}",
          root.getAbsolutePath(), properties.retainFilesOnExit());
    } else {
      root = new File(properties.root());

      if (root.exists()) {
        LOG.info("Using existing folder \"{}\" as root folder. Will retain files on exit: {}",
            root.getAbsolutePath(), properties.retainFilesOnExit());
        //TODO: need to validate folder structure here?
      } else if (!root.mkdir()) {
        throw new IllegalStateException("Root folder could not be created. Path: "
            + root.getAbsolutePath());
      } else {
        LOG.info("Successfully created \"{}\" as root folder. Will retain files on exit: {}",
            root.getAbsolutePath(), properties.retainFilesOnExit());
      }
    }

    if (!properties.retainFilesOnExit()) {
      root.deleteOnExit();
    }

    return root;
  }
}
